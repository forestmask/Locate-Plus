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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Either;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.ChunkAccessPolicy;
import dev.locateplus.scan.Highlights;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.command.argument.EntityArgumentType;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import java.util.function.Predicate;

/**
 * {@code /glow <target> <n> chunks|blocks}
 *
 * <p>There is no {@code forceload}. Glowing only means anything for entities a player can
 * actually see, and entities in chunks nobody has loaded are not being watched by anyone --
 * force-loading them would generate terrain and spend real time to light up mobs that vanish
 * again the moment the tickets are released. Use {@code /analyzechunks entities ... forceload}
 * to survey distant chunks instead.</p>
 *
 * <p>Applies the vanilla Glowing effect to every matching entity for one minute, so they can be
 * tracked through terrain.</p>
 *
 * <h2>Target parsing</h2>
 *
 * <p>The target sits in two sibling branches with genuinely different vanilla argument types, and
 * Brigadier falls through from the first to the second when the first cannot parse:</p>
 *
 * <ol>
 *   <li>{@link RegistryPredicateArgumentType}, entity ids and tags, including modded ones:
 *       {@code minecraft:cow}, {@code #minecraft:skeletons}, {@code somemod:wraith}</li>
 *   <li>{@link EntityArgumentType}, every vanilla selector plus player names:
 *       {@code @e}, {@code @a}, {@code @s}, {@code @p}, {@code @r},
 *       {@code @e[type=zombie,distance=..50]}, {@code Steve}</li>
 * </ol>
 *
 * <p>A single {@code word()} argument cannot cover both: Brigadier defines a "word" as
 * {@code [A-Za-z0-9_.+-]}, which excludes the {@code ':'} in a namespaced id, so
 * {@code minecraft:cow} parsed as just {@code minecraft} and left {@code :cow} as trailing data.
 * {@code string()} would demand quotes around every id. Two typed branches avoid both problems and
 * keep the tree serialisable to vanilla clients.</p>
 *
 * <p><b>Entities only.</b> There is no block form: outlining blocks would need either a particle
 * per match, tens of thousands of packets for one ore scan, or a client mod to render them.
 * {@code /locate block} already marks its nearest hit with particles.</p>
 *
 * <p>There is no cap on how many entities may glow. Glowing is ordinary tracked entity data, so it
 * costs one small packet per entity per client that can already see it, the same traffic vanilla
 * sends for any status effect.</p>
 */
public final class GlowCommand {

    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Text.literal("No entity type or entity tag matching '" + id + "'. "
                    + "Use an id (minecraft:cow), a tag (#minecraft:skeletons), "
                    + "or a selector (@e)."));

    private GlowCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(build());
    }

    private static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("glow")
                .requires(source -> source.hasPermissionLevel(LPConstants.PERMISSION_LEVEL))

                // Branch 1: entity id or #tag, including modded namespaces.
                .then(RadiusArg.attach(
                        argument("type", RegistryPredicateArgumentType
                                .registryPredicate(RegistryKeys.ENTITY_TYPE))
                                .executes(ctx -> byType(ctx,
                                        Radius.ofBlocks(LPConstants.DEFAULT_BLOCK_RADIUS), false)),
                        false,
                        GlowCommand::byType))

                // Branch 2: any vanilla selector, or a player name.
                .then(RadiusArg.attach(
                        argument("selector", EntityArgumentType.entities())
                                .executes(ctx -> bySelector(ctx,
                                        Radius.ofBlocks(LPConstants.DEFAULT_BLOCK_RADIUS), false)),
                        false,
                        GlowCommand::bySelector));
    }

    // ---- branch 1: id / tag ----------------------------------------------------------------------

    private static int byType(CommandContext<ServerCommandSource> ctx, Radius radius, boolean forceload)
            throws CommandSyntaxException {
        RegistryPredicateArgumentType.RegistryPredicate<EntityType<?>> predicate =
                RegistryPredicateArgumentType.getPredicate(
                        ctx, "type", RegistryKeys.ENTITY_TYPE, UNKNOWN_TYPE);

        Either<RegistryKey<EntityType<?>>, TagKey<EntityType<?>>> key = predicate.getKey();
        String label = key.map(k -> k.getValue().toString(), tag -> "#" + tag.id());

        // registryPredicate() accepts any well-formed id, so a typo builds a predicate that simply
        // matches nothing and the player is told "none found nearby" rather than "no such type".
        // Check the registry explicitly so a mistake is reported as a mistake.
        var missing = key.map(
                k -> !Registries.ENTITY_TYPE.contains(k),
                tag -> Registries.ENTITY_TYPE.getEntryList(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_TYPE.create(label);
        }

        EntityQuery query = key.map(
                k -> EntityQuery.ofType(Registries.ENTITY_TYPE.get(k)),
                EntityQuery::ofTag);

        return run(ctx.getSource(), label, radius, forceload, world -> query::matches);
    }

    // ---- branch 2: selector ------------------------------------------------------------------------

    private static int bySelector(CommandContext<ServerCommandSource> ctx, Radius radius,
                                  boolean forceload) throws CommandSyntaxException {
        String label = describeSelector(ctx);

        // Anything that looks like an id or tag but reached this branch never matched the
        // registry, so the selector parser has fallen back to treating it as a player name.
        // Saying "no such entity type" is far clearer than "none found nearby".
        if (!label.startsWith("@") && (label.indexOf(':') >= 0 || label.startsWith("#"))) {
            throw UNKNOWN_TYPE.create(label);
        }

        // Resolved lazily, after any force-loading, so the selector sees the chunks that were
        // just brought into memory rather than the empty world it would have seen before.
        return run(ctx.getSource(), label, radius, forceload, world -> {
            Collection<? extends Entity> matched;
            try {
                matched = EntityArgumentType.getOptionalEntities(ctx, "selector");
            } catch (CommandSyntaxException e) {
                return entity -> false;
            }
            Set<UUID> ids = new HashSet<>(Math.max(16, matched.size() * 2));
            for (Entity entity : matched) {
                ids.add(entity.getUuid());
            }
            return entity -> ids.contains(entity.getUuid());
        });
    }

    /** The raw selector text the player typed, for chat output. */
    private static String describeSelector(CommandContext<ServerCommandSource> ctx) {
        for (var node : ctx.getNodes()) {
            if ("selector".equals(node.getNode().getName())) {
                var range = node.getRange();
                String input = ctx.getInput();
                int start = Math.max(0, Math.min(range.getStart(), input.length()));
                int end = Math.max(start, Math.min(range.getEnd(), input.length()));
                return input.substring(start, end);
            }
        }
        return "selection";
    }

    // ---- shared implementation -----------------------------------------------------------------

    /** Builds the match test once the world is in its final (possibly force-loaded) state. */
    @FunctionalInterface
    private interface MatcherFactory {
        Predicate<Entity> create(ServerWorld world);
    }

    private static int run(ServerCommandSource source, String label, Radius radius,
                           boolean forceload, MatcherFactory factory) {
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);
        ScanRegion region = ScanRegion.forRadius(originBlock, radius, forceload);

        // In chunk mode the region defines the area, so derive the search box from the chunks
        // actually chosen. In block mode the typed distance is the real bound.
        int blocks = radius.typedUnit() == Radius.Unit.BLOCKS
                ? radius.blocks()
                : region.blockExtent();
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ChunkAccessPolicy access = new ChunkAccessPolicy(world, forceload);
        try {
            if (forceload) {
                for (ChunkPos pos : region.chunks()) {
                    access.acquire(pos);
                }
            }

            Predicate<Entity> matches = factory.create(world);

            Box box = new Box(
                    origin.x - blocks, world.getBottomY(), origin.z - blocks,
                    origin.x + blocks, world.getTopY(), origin.z + blocks);
            double radiusSq = (double) blocks * blocks;

            List<Entity> found = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();

            for (Entity entity : world.getOtherEntities(null, box, e -> true)) {
                consider(entity, matches, origin, radiusSq, seen, found);
            }
            // Players are tracked separately from chunk entity storage; sweep them in too.
            for (Entity player : world.getPlayers()) {
                consider(player, matches, origin, radiusSq, seen, found);
            }

            if (found.isEmpty()) {
                Msg.warn(source, "No " + label + " within " + describeRequest(radius) + ".");
                return 0;
            }

            Map<Identifier, Integer> counts = new LinkedHashMap<>();
            int glowed = 0;
            for (Entity entity : found) {
                try {
                    Highlights.glow(world, entity);
                    counts.merge(Registries.ENTITY_TYPE.getId(entity.getType()), 1, Integer::sum);
                    glowed++;
                } catch (Throwable t) {
                    LPLog.error("Failed to apply glow to " + entity.getType(), t);
                }
            }

            int total = glowed;
            Msg.success(source, "Glowing " + Msg.count(total, "entity", "entities")
                    + " matching " + label + " for "
                    + (LPConstants.GLOW_DURATION_TICKS / 20) + " seconds.");
            Msg.field(source, "Searched", describeRequest(radius));

            List<Map.Entry<Identifier, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Map.Entry.<Identifier, Integer>comparingByValue().reversed());
            int shown = Math.min(LPConstants.CHAT_TOP_N, sorted.size());
            for (int i = 0; i < shown; i++) {
                Map.Entry<Identifier, Integer> entry = sorted.get(i);
                int rank = i + 1;
                source.sendFeedback(() -> Msg.result(rank, entry.getKey().toString(),
                        entry.getValue(), -1), false);
            }
            if (sorted.size() > shown) {
                Msg.more(source, sorted.size() - shown, "types");
            }
            return total;
        } finally {
            access.close();
        }
    }

    private static void consider(Entity entity, Predicate<Entity> matches,
                                 Vec3d origin, double radiusSq, Set<UUID> seen, List<Entity> out) {
        if (entity == null || entity.isRemoved() || !seen.add(entity.getUuid())) {
            return;
        }
        if (!matches.test(entity)) {
            return;
        }
        if (horizontalDistanceSq(origin, entity) <= radiusSq) {
            out.add(entity);
        }
    }

    /**
     * Horizontal (X/Z) distance only, ignoring Y.
     *
     * <p>A radius is what a player draws on a map, not a sphere around their eyes. Using true 3D
     * distance meant flying thirty blocks above a mob put it outside a sixteen-block radius, so
     * looking down at something and asking to highlight it did nothing. Every entity in the
     * column counts, from bedrock to build limit.</p>
     */
    private static double horizontalDistanceSq(Vec3d origin, Entity entity) {
        double dx = origin.x - entity.getX();
        double dz = origin.z - entity.getZ();
        return dx * dx + dz * dz;
    }

    /** The radius as typed: a chunk count bare, a block radius with its chunk equivalent. */
    private static String describeRequest(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? Msg.count(radius.chunks(), "chunk")
                : radius.describeBlocksFirst();
    }

}
