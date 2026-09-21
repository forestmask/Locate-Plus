/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.ChatReporter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.BlockMatcher;
import dev.locateplus.scan.BlockScanJob;
import dev.locateplus.scan.EntityScanJob;
import dev.locateplus.scan.Highlights;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.block.Block;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /locate block} and {@code /locate entity}.
 *
 * The command tree mirrors vanilla {@code /locate structure}.
 */
public final class LocateCommand {

    /**
     * Chunk count above which a scan is called out as large before it starts.
     *
     * Set where the wait stops being momentary. Below this a scan finishes while you are still
     * reading the line that announced it.
     */
    private static final int LARGE_SCAN_CHUNKS = 2_000;

    /** Raised when the given id/tag exists in no registry, mirrors vanilla /locate wording. */
    private static final DynamicCommandExceptionType UNKNOWN_BLOCK = new DynamicCommandExceptionType(
            id -> Text.literal("No block or block tag matching '" + id + "'"));

    private static final DynamicCommandExceptionType UNKNOWN_ENTITY = new DynamicCommandExceptionType(
            id -> Text.literal("No entity type or entity tag matching '" + id + "'"));

    private LocateCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LPConfig config = LPConfig.get();
        int defaultRadius = config.defaultBlockRadius();

        var locate = literal("locate")
                .requires(source -> source.hasPermissionLevel(LPConfig.get().permissionLevel()));

        // /locate block <block_id|#tag> [<n> block|chunk [forceload]]
        if (config.commandLocateBlock()) {
            locate.then(literal("block")
                    .then(RadiusArg.attach(
                            argument("block", RegistryPredicateArgumentType
                                    .registryPredicate(RegistryKeys.BLOCK))
                                    .executes(ctx -> locateBlock(ctx, Radius.ofLoaded(), false)),
                            true,
                            LocateCommand::locateBlock)));
        }

        // /locate entity <entity_id|#tag> [<n> block|chunk [forceload]]
        if (config.commandLocateEntity()) {
            locate.then(literal("entity")
                    .then(RadiusArg.attach(
                            argument("entity", RegistryPredicateArgumentType
                                    .registryPredicate(RegistryKeys.ENTITY_TYPE))
                                    .executes(ctx -> locateEntity(ctx, Radius.ofLoaded(), false)),
                            true,
                            LocateCommand::locateEntity)));
        }

        if (config.commandLocateItem()) {
            locate.then(LocateItemCommand.build());
        }

        dispatcher.register(locate);
    }

    // ---- /locate block ------------------------------------------------------------------------

    private static int locateBlock(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                                   boolean forceload) throws CommandSyntaxException {
        return runBlockScan(ctx, radiusSpec, forceload, toBlockMatcher(
                RegistryPredicateArgumentType.getPredicate(ctx, "block", RegistryKeys.BLOCK,
                        UNKNOWN_BLOCK)));
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's block-state matcher. */
    private static BlockMatcher toBlockMatcher(
            RegistryPredicateArgumentType.RegistryPredicate<Block> predicate)
            throws CommandSyntaxException {
        com.mojang.datafixers.util.Either<net.minecraft.registry.RegistryKey<Block>, TagKey<Block>>
                key = predicate.getKey();
        boolean missing = key.map(
                k -> !Registries.BLOCK.contains(k),
                tag -> Registries.BLOCK.getEntryList(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_BLOCK.create(key.map(k -> k.getValue().toString(),
                    tag -> "#" + tag.id()));
        }
        return key.map(
                blockKey -> BlockMatcher.ofBlock(blockKey.getValue().toString(),
                        Registries.BLOCK.get(blockKey)),
                BlockMatcher::ofTag);
    }

    private static int runBlockScan(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                                    boolean forceload, BlockMatcher matcher)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload, world);
        if (!confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        // A chunk count bounds the scan by chunk, so no extra per-block distance filter applies.
        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ScanResult result = new ScanResult(ScanResult.Kind.BLOCKS,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                region.chunkRadius(), radius, region.boundsDescription(), forceload);

        Msg.info(source, "Searching for " + matcher.label() + " within "
                + describeRequest(radiusSpec) + " (" + Msg.count(region.totalChunks(), "chunk")
                + ")...");

        LPScheduler.submit(new BlockScanJob(world, region, matcher, result, false, radius, origin, false,
                done -> {
                    ChatReporter.locateBlock(source, done, matcher.label(), radiusSpec);
                    BlockTally best = null;
                    for (BlockTally t : done.blocksByCount()) {
                        if (best == null || t.nearestDistance() < best.nearestDistance()) {
                            best = t;
                        }
                    }
                    if (best != null && best.nearest() != null) {
                        Highlights.markBlock(world, best.nearest(), source.getPlayer());
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                },
                error -> Chat.error(source, "Block scan failed: " + error.getMessage())));
        return 1;
    }

    // ---- /locate entity -----------------------------------------------------------------------

    private static int locateEntity(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                                    boolean forceload) throws CommandSyntaxException {
        return runEntityScan(ctx, radiusSpec, forceload, toTargetSpec(
                RegistryPredicateArgumentType.getPredicate(ctx, "entity",
                        RegistryKeys.ENTITY_TYPE, UNKNOWN_ENTITY)));
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's entity target. */
    private static TargetSpec toTargetSpec(
            RegistryPredicateArgumentType.RegistryPredicate<EntityType<?>> predicate)
            throws CommandSyntaxException {
        com.mojang.datafixers.util.Either<net.minecraft.registry.RegistryKey<EntityType<?>>,
                TagKey<EntityType<?>>> key = predicate.getKey();
        boolean missing = key.map(
                k -> !Registries.ENTITY_TYPE.contains(k),
                tag -> Registries.ENTITY_TYPE.getEntryList(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_ENTITY.create(key.map(k -> k.getValue().toString(),
                    tag -> "#" + tag.id()));
        }
        return key.map(
                typeKey -> {
                    EntityType<?> type = Registries.ENTITY_TYPE.get(typeKey);
                    return fixed(typeKey.getValue().toString(), EntityQuery.ofType(type),
                            type == EntityType.PLAYER);
                },
                tag -> fixed("#" + tag.id(), EntityQuery.ofTag(tag), false));
    }

    private static int runEntityScan(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                                     boolean forceload, TargetSpec spec)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload, world);
        if (!confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ScanResult result = new ScanResult(ScanResult.Kind.ENTITIES,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                region.chunkRadius(), radius, region.boundsDescription(), forceload);

        Msg.info(source, "Searching for " + spec.label() + " within "
                + describeRequest(radiusSpec) + " (" + Msg.count(region.totalChunks(), "chunk")
                + ")...");

        LPScheduler.submit(new EntityScanJob(world, source, region, spec, result, radius, origin,
                done -> {
                    ChatReporter.locateEntity(source, done, spec.label(), radiusSpec);
                    EntityRecord nearest = done.nearestEntity();
                    // Glowing is entity state, so everyone nearby sees it.
                    if (nearest != null && LPConfig.get().glowLocatedEntities()) {
                        Entity live = world.getEntity(nearest.uuid());
                        if (live != null && !live.isRemoved()) {
                            Highlights.glow(world, live);
                        }
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                },
                error -> Chat.error(source, "Entity scan failed: " + error.getMessage())));
        return 1;
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's entity query. */
    private static TargetSpec fixed(String label, EntityQuery query, boolean playersOnly) {
        return new TargetSpec() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public EntityQuery bind(ServerCommandSource source) {
                return query;
            }

            @Override
            public boolean playersOnly() {
                return playersOnly;
            }
        };
    }

        /**
         * The radius as the player expressed it.
         *
         * A chunk count is printed bare. Showing its block equivalent, "100 chunks (1600 blocks)",
         * reads as though a 1600-block radius were being scanned, which is a very different area.
         */
    /** A rough wall-clock estimate for a scan of this many chunks. */
    private static String estimate(int chunks) {
        long seconds = chunks / 60;
        if (seconds < 60) {
            return "under a minute";
        }
        if (seconds < 3600) {
            return Msg.count(Math.max(1, seconds / 60), "minute");
        }
        return Msg.count(Math.max(1, seconds / 3600), "hour");
    }

    private static String describeRequest(Radius radius) {
        return radius.isLoadedArea() ? "the loaded area"
                : radius.typedUnit() == Radius.Unit.CHUNKS
                        ? Msg.chunkReach(radius.chunks())
                        : radius.describeBlocksFirst();
    }

    /**
     * Warn before a force-load scan and refuse absurd requests outright.
     *
     * @return {@code false} if the scan must not proceed
     */
    static boolean confirmForceload(ServerCommandSource source, ServerWorld world,
                                    ScanRegion region, boolean forceload) {
        // Warn on size before warning on force-loading, because the two are independent and the
        // size is the one people misjudge.
        int chunks = region.totalChunks();
        if (chunks > LARGE_SCAN_CHUNKS) {
            // Slow and laggy are the same word to most people, so the one thing worth saying is
            // which of the two this is. It belongs on the line with the time, not on its own.
            Msg.warn(source, "That is " + Msg.number(chunks) + " chunks, roughly "
                    + estimate(chunks) + " to scan. No lag, just slow.");
            Msg.note(source, "Run /lp stop to cancel it. A smaller radius covers far less: "
                    + "the area grows with the square of the number.");
        }

        if (!forceload) {
            return true;
        }
        int total = region.totalChunks();
        int unloaded = total - region.countLoaded(world);

        // Nothing is refused.
        if (total > LPConfig.get().forceloadWarnThreshold()) {
            Msg.warn(source, "Large scan: " + Msg.number(total) + " chunks in range. "
                    + "This can take a while and use a lot of memory.");
        }
        // Report both numbers, and never call a chunk count a "radius": 100 chunks and a 100-chunk
        // radius read alike in a message but differ by a factor of 320.
        Msg.warn(source, "Force-loading " + Msg.number(unloaded) + " of "
                + Msg.number(total) + " chunks. This may cause lag or generate terrain.");
        return true;
    }
}
