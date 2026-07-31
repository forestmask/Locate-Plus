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
import com.mojang.datafixers.util.Either;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.Msg;
import dev.locateplus.report.ChatReporter;
import dev.locateplus.scan.BlockMatcher;
import dev.locateplus.scan.BlockScanJob;
import dev.locateplus.scan.EntityScanJob;
import dev.locateplus.scan.Highlights;
import dev.locateplus.scan.ScanRegion;
import net.minecraft.block.Block;
import dev.locateplus.util.Radius;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
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
 * <h2>Argument design</h2>
 *
 * <p>The command tree mirrors vanilla {@code /locate structure} exactly:</p>
 *
 * <pre>
 *   /locate block  &lt;block_id|#tag&gt;  [radius] [forceload]
 *   /locate entity &lt;entity_id|#tag&gt; [radius] [forceload]
 * </pre>
 *
 * <p>The target uses {@link RegistryPredicateArgumentType}, the same vanilla type behind
 * {@code /locate structure}. That gives id-or-tag input, registry-backed tab completion, and
 * vanilla error messages for free, and, being vanilla, it serialises correctly to every client.</p>
 *
 * <p>Radius and {@code forceload} are separate, structured nodes rather than free text. Brigadier
 * then enforces arity itself: {@code /locate block minecraft:iron_ore 64 64 64} simply fails to
 * parse, because after the radius node the only permitted continuation is {@code forceload}.</p>
 *
 * <p>Custom {@code ArgumentType} classes are deliberately avoided here. An unregistered one throws
 * while the server serialises its command tree at login, kicking the player with the misleading
 * message "Invalid player data"; registering one properly would require the mod on the client too.
 * {@code CommandTreeSerializationTest} enforces that only vanilla types are used.</p>
 */
public final class LocateCommand {

    /** Raised when the given id/tag exists in no registry, mirrors vanilla /locate wording. */
    private static final DynamicCommandExceptionType UNKNOWN_BLOCK = new DynamicCommandExceptionType(
            id -> Text.literal("No block or block tag matching '" + id + "'"));

    private static final DynamicCommandExceptionType UNKNOWN_ENTITY = new DynamicCommandExceptionType(
            id -> Text.literal("No entity type or entity tag matching '" + id + "'"));

    private LocateCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var locate = literal("locate")
                .requires(source -> source.hasPermissionLevel(LPConstants.PERMISSION_LEVEL))

                // /locate block <block_id|#tag> [<n> block|chunk [forceload]]
                .then(literal("block")
                        .then(RadiusArg.attach(
                                argument("block", RegistryPredicateArgumentType
                                        .registryPredicate(RegistryKeys.BLOCK))
                                        .executes(ctx -> locateBlock(ctx,
                                                Radius.ofBlocks(LPConstants.DEFAULT_BLOCK_RADIUS), false)),
                                true,
                                LocateCommand::locateBlock)))

                // /locate entity <entity_id|#tag> [<n> block|chunk [forceload]]
                .then(literal("entity")
                        .then(RadiusArg.attach(
                                argument("entity", RegistryPredicateArgumentType
                                        .registryPredicate(RegistryKeys.ENTITY_TYPE))
                                        .executes(ctx -> locateEntity(ctx,
                                                Radius.ofBlocks(LPConstants.DEFAULT_BLOCK_RADIUS), false)),
                                true,
                                LocateCommand::locateEntity)));

        dispatcher.register(locate);
    }

    // ---- /locate block -------------------------------------------------------------------------

    private static int locateBlock(CommandContext<ServerCommandSource> ctx, Radius radiusSpec, boolean forceload)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        BlockMatcher matcher = toBlockMatcher(
                RegistryPredicateArgumentType.getPredicate(ctx, "block", RegistryKeys.BLOCK, UNKNOWN_BLOCK));

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload);
        if (!confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        // A chunk count bounds the scan by chunk, so no extra per-block distance filter applies.
        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ScanResult result = new ScanResult(ScanResult.Kind.BLOCKS,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                region.chunkRadius(), radius, region.boundsDescription(), forceload);

        Msg.info(source, "Searching for " + matcher.label() + " within "
                + describeRequest(radiusSpec) + "...");

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
                        Highlights.markBlock(world, best.nearest());
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                },
                error -> Chat.error(source, "Block scan failed: " + error.getMessage())));
        return 1;
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's block-state matcher. */
    private static BlockMatcher toBlockMatcher(
            RegistryPredicateArgumentType.RegistryPredicate<Block> predicate)
            throws CommandSyntaxException {
        Either<RegistryKey<Block>, TagKey<Block>> key = predicate.getKey();
        boolean missing = key.map(
                k -> !Registries.BLOCK.contains(k),
                tag -> Registries.BLOCK.getEntryList(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_BLOCK.create(key.map(k -> k.getValue().toString(),
                    tag -> "#" + tag.id()));
        }
        return key.map(
                blockKey -> {
                    Block block = Registries.BLOCK.get(blockKey);
                    return BlockMatcher.ofBlock(blockKey.getValue().toString(), block);
                },
                tag -> BlockMatcher.ofTag(tag));
    }

    // ---- /locate entity ------------------------------------------------------------------------

    private static int locateEntity(CommandContext<ServerCommandSource> ctx, Radius radiusSpec, boolean forceload)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        TargetSpec spec = toTargetSpec(
                RegistryPredicateArgumentType.getPredicate(ctx, "entity", RegistryKeys.ENTITY_TYPE, UNKNOWN_ENTITY));

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload);
        if (!confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ScanResult result = new ScanResult(ScanResult.Kind.ENTITIES,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                region.chunkRadius(), radius, region.boundsDescription(), forceload);

        Msg.info(source, "Searching for " + spec.label() + " within "
                + describeRequest(radiusSpec) + "...");

        LPScheduler.submit(new EntityScanJob(world, source, region, spec, result, radius, origin,
                done -> {
                    ChatReporter.locateEntity(source, done, spec.label(), radiusSpec);
                    EntityRecord nearest = done.nearestEntity();
                    if (nearest != null) {
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
    private static TargetSpec toTargetSpec(
            RegistryPredicateArgumentType.RegistryPredicate<EntityType<?>> predicate)
            throws CommandSyntaxException {
        Either<RegistryKey<EntityType<?>>, TagKey<EntityType<?>>> key = predicate.getKey();
        // registryPredicate() does not verify the id exists; a typo would silently match nothing.
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
                    String label = typeKey.getValue().toString();
                    return fixed(label, EntityQuery.ofType(type), type == EntityType.PLAYER);
                },
                tag -> fixed("#" + tag.id(), EntityQuery.ofTag(tag), false));
    }

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
     * <p>A chunk count is printed bare. Showing its block equivalent, "100 chunks (1600 blocks)",
     * reads as though a 1600-block radius were being scanned, which is a very different area.</p>
     */
    private static String describeRequest(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? Msg.count(radius.chunks(), "chunk")
                : radius.describeBlocksFirst();
    }

    /**
     * Warn before a force-load scan, as the guide requires, and refuse absurd requests outright.
     *
     * @return {@code false} if the scan must not proceed
     */
    static boolean confirmForceload(ServerCommandSource source, ServerWorld world,
                                    ScanRegion region, boolean forceload) {
        if (!forceload) {
            return true;
        }
        int total = region.totalChunks();
        int unloaded = total - region.countLoaded(world);

        // Nothing is refused. A very large scan gets a louder warning so the cost is obvious,
        // but whether to run it is the operator's decision, not the mod's.
        if (total > LPConstants.FORCELOAD_WARN_THRESHOLD) {
            Msg.warn(source, "Large scan: " + Msg.number(total) + " chunks in range. "
                    + "This can take a while and use a lot of memory.");
        }
        // Report both numbers, and never call a chunk count a "radius": 100 chunks and a
        // 100-chunk radius read alike in a message but differ by a factor of 320.
        Msg.warn(source, "Force-loading " + Msg.number(unloaded) + " of "
                + Msg.number(total) + " chunks. This may cause lag or generate terrain.");
        return true;
    }
}
