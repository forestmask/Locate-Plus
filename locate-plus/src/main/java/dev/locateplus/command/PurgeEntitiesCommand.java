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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.report.ExportWriter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /purgeentities <entity_id|#tag> [radius] [export]}, remove matching entities, with a log
 * of exactly what was deleted.
 *
 * This command destroys data, so it refuses ambiguous input rather than trusting the operator
 * to have typed carefully.
 */
public final class PurgeEntitiesCommand {

    private static final DynamicCommandExceptionType UNKNOWN_ENTITY = new DynamicCommandExceptionType(
                    id -> Text.literal("No entity type or entity tag matching '" + id + "'"));

    private static final SimpleCommandExceptionType NO_PLAYERS = new SimpleCommandExceptionType(
            Text.literal("Refusing to purge players. Use /kick or /ban for that."));

    private PurgeEntitiesCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("purgeentities")
                .requires(source -> source.hasPermissionLevel(LPConfig.get().permissionLevel()))

                // Branch 1: entity id or #tag, including modded namespaces.
                .then(radiusTail(argument("type", RegistryPredicateArgumentType
                        .registryPredicate(RegistryKeys.ENTITY_TYPE)), true))

                // Branch 2: any vanilla selector.
                .then(radiusTail(argument("selector",
                        EntityArgumentType.entities()), false)));
    }

    /**
     * {@code <target> <n> block|chunk [export]}.
     *
     * No {@code forceload}: pulling unloaded chunks in purely to delete what is inside them is a
     * footgun, and force-load exists for scanning, not destruction.
     */
    private static <T extends ArgumentBuilder<ServerCommandSource, T>>
    T radiusTail(T target, boolean byType) {
        var number = argument("radius",
                IntegerArgumentType.integer(0, Radius.hardMaxBlocks()));

        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");
            number.then(literal(word)
                    .executes(ctx -> purge(ctx, read(ctx, isBlocks), false, byType))
                    .then(literal("export")
                            .executes(ctx -> purge(ctx, read(ctx, isBlocks), true, byType))));
        }

        // No radius means every loaded chunk, the same as the scanning commands. Leaving this out
        // made the one destructive command behave unlike all the others.
        return target
                .executes(ctx -> purge(ctx, Radius.ofLoaded(), false, byType))
                .then(literal("export")
                        .executes(ctx -> purge(ctx, Radius.ofLoaded(), true, byType)))
                .then(number);
    }

    private static Radius read(CommandContext<ServerCommandSource> ctx, boolean isBlocks)
            throws CommandSyntaxException {
        return RadiusArg.read(ctx, isBlocks);
    }

    private static int purge(CommandContext<ServerCommandSource> ctx, Radius radius,
                             boolean export, boolean byType) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        final String label;
        final EntityQuery query;

        if (byType) {
            var predicate = RegistryPredicateArgumentType.getPredicate(
                    ctx, "type", RegistryKeys.ENTITY_TYPE, UNKNOWN_ENTITY);
            var key = predicate.getKey();
            label = key.map(k -> k.getValue().toString(), tag -> "#" + tag.id());
            query = key.map(
                    k -> EntityQuery.ofType(
                            Registries.ENTITY_TYPE.get(k)),
                    EntityQuery::ofTag);
        } else {
            var matched = EntityArgumentType.getOptionalEntities(ctx, "selector");
            Set<UUID> ids = new HashSet<>();
            for (Entity e : matched) {
                ids.add(e.getUuid());
            }
            label = "selection";
            query = EntityQuery.ofPredicate(
                    label, e -> ids.contains(e.getUuid()), false);
        }

        // Players are never a valid purge target, however the filter was spelled.
        if (label.equals("minecraft:player")) {
            throw NO_PLAYERS.create();
        }

        // Block mode is bounded by the typed distance.
        boolean byBlocks = radius.typedUnit() == Radius.Unit.BLOCKS;
        ScanRegion region = ScanRegion.forRadius(originBlock, radius, false, world);
        int blocks = byBlocks ? radius.blocks() : region.blockExtent() + 16;
        Box box = new Box(
                origin.x - blocks, LPConfig.get().scanMinY(world), origin.z - blocks,
                origin.x + blocks, LPConfig.get().scanMaxY(world) + 1.0, origin.z + blocks);
        double radiusSq = (double) blocks * blocks;

        // Snapshot first: once discarded, the entity is gone and cannot be described.
        List<Entity> doomed = new ArrayList<>();
        ScanResult record = new ScanResult(ScanResult.Kind.ENTITIES,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                radius.chunks(), blocks, "purge radius " + radius.describeBlocksFirst(), false);

        for (Entity entity : world.getOtherEntities(null, box, e -> true)) {
            if (entity.isRemoved() || entity instanceof PlayerEntity) {
                continue; // players are never touched
            }
            if (BlockBeacon.isMarker(entity)) {
                continue; // this mod's own block marker, cleared with /lp clear instead
            }
            if (!query.matches(entity)) {
                continue;
            }
            boolean inRange = byBlocks
                    ? horizontalDistanceSq(origin, entity) <= radiusSq
                    : region.containsPosition(entity.getX(), entity.getZ());
            if (!inRange) {
                continue;
            }
            doomed.add(entity);
            record.addEntity(EntityRecord.of(entity, origin));
        }

        if (doomed.isEmpty()) {
            Msg.warn(source, "No " + label + " within " + describeRequest(radius)
                    + ". Nothing removed.");
            return 0;
        }

        Map<Identifier, Integer> counts = new LinkedHashMap<>();
        int removed = 0;
        for (Entity entity : doomed) {
            Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
            try {
                entity.discard(); // no drops, no XP, no death message
                counts.merge(id, 1, Integer::sum);
                removed++;
            } catch (Throwable t) {
                // a modded entity that refuses removal must not abort the whole purge
            }
        }
        record.finish(0);

        int total = removed;
        Msg.success(source, "Removed " + Msg.count(total, "entity", "entities")
                + " matching " + label + ".");
        Msg.field(source, "Searched", describeRequest(radius));

        List<Map.Entry<Identifier, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Identifier, Integer>comparingByValue().reversed());
        int shown = Math.min(LPConfig.get().chatTopN(), sorted.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<Identifier, Integer> entry = sorted.get(i);
            int rank = i + 1;
            source.sendFeedback(() -> Msg.result(rank, entry.getKey().toString(),
                    entry.getValue(), -1), false);
        }
        if (sorted.size() > shown) {
            Msg.more(source, sorted.size() - shown, "types");
        }

        if (export) {
            exportAsync(source, record);
        } else {
            Msg.note(source, "Add 'export' to write the full list to a file.");
        }
        return total;
    }

    /** Same background export path as {@code /lp analyze}. */
    private static void exportAsync(ServerCommandSource source, ScanResult record) {
        Msg.info(source, "Writing purge log in the background...");
        LPScheduler.background().execute(() -> ExportWriter.writeAsyncLogged(record,
                paths -> {
                    Msg.success(source, "Purge log written.");
                    Msg.field(source, "File", String.valueOf(paths[0].getFileName()));
                    Msg.note(source, "in " + Msg.exportPath());
                },
                error -> Msg.error(source, "Purge log failed: " + error.getMessage())));
    }

    /** Horizontal (X/Z) distance only, ignoring Y. */
    private static double horizontalDistanceSq(Vec3d origin, Entity entity) {
        double dx = origin.x - entity.getX();
        double dz = origin.z - entity.getZ();
        return dx * dx + dz * dz;
    }

    /** The radius as typed: a chunk count bare, a block radius with its chunk equivalent. */
    private static String describeRequest(Radius radius) {
        if (radius.isLoadedArea()) {
            return "the loaded area";
        }
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? Msg.chunkReach(radius.chunks())
                : radius.describeBlocksFirst();
    }
}
