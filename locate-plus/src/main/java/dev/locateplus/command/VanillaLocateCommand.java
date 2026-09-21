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
import com.mojang.datafixers.util.Pair;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.report.Chat;
import dev.locateplus.report.Msg;
import dev.locateplus.teleport.TeleportService;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.RegistryEntryPredicateArgumentType;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Replaces vanilla {@code /locate biome} and {@code /locate structure} so their results carry a
 * safe-teleport button instead of a raw {@code /tp} link.
 *
 * The argument types must match vanilla's <em>exactly</em>. In 1.20.1 the two subcommands do not
 * agree with each other, so each is registered against the type vanilla actually uses.
 */
public final class VanillaLocateCommand {

    /** Vanilla's search budget for /locate biome. */
    private static final int BIOME_SEARCH_RADIUS = 6400;
    private static final int BIOME_SEARCH_STEP = 32;
    private static final int BIOME_BLOCK_CHECK_INTERVAL = 64;

    /** Vanilla's search budget for /locate structure, in chunks. */
    private static final int STRUCTURE_SEARCH_CHUNKS = 100;

    private static final DynamicCommandExceptionType BIOME_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Text.literal("Could not find a biome of type \"" + id + "\" within "
                    + BIOME_SEARCH_RADIUS + " blocks"));

    private static final DynamicCommandExceptionType STRUCTURE_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Text.literal("Could not find a structure of type \"" + id + "\" nearby"));

    /** Blocks searched outward from the caller for a point of interest. */
    private static final int POI_SEARCH_RADIUS = 512;

    /** Chunk rings loaded when the in-memory index comes up empty. */
    private static final int POI_SEARCH_CHUNK_RINGS = 12;

    private static final DynamicCommandExceptionType POI_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Text.literal("Could not find a point of interest of type \"" + id
                    + "\" within " + POI_SEARCH_RADIUS + " blocks"));

    private static final DynamicCommandExceptionType STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Text.literal("There is no structure with type \"" + id + "\" in this world"));

    private VanillaLocateCommand() {
    }

    /**
     * Register the overriding {@code /locate biome} and {@code /locate structure}.
     *
     * Registered as its own {@code /locate} root rather than being attached to this mod's builder,
     * so the merge happens against vanilla's existing tree.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess access) {
        dispatcher.register(literal("locate")
                .requires(source -> source.hasPermissionLevel(LPConfig.get().permissionLevel()))

                .then(literal("biome")
                        .then(argument("biome",
                                RegistryEntryPredicateArgumentType.registryEntryPredicate(access, RegistryKeys.BIOME))
                                .executes(VanillaLocateCommand::locateBiome)))

                .then(literal("structure")
                        .then(argument("structure",
                                RegistryPredicateArgumentType.registryPredicate(RegistryKeys.STRUCTURE))
                                .executes(VanillaLocateCommand::locateStructure)))

                .then(literal("poi")
                        .then(argument("poi", RegistryEntryPredicateArgumentType
                                .registryEntryPredicate(access, RegistryKeys.POINT_OF_INTEREST_TYPE))
                                .executes(VanillaLocateCommand::locatePoi))));
    }

    // ---- biome --------------------------------------------------------------------------------

    private static int locateBiome(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = BlockPos.ofFloored(source.getPosition());

        RegistryEntryPredicateArgumentType.EntryPredicate<Biome> predicate =
                RegistryEntryPredicateArgumentType.getRegistryEntryPredicate(
                        ctx, "biome", RegistryKeys.BIOME);

        Chat.info(source, "Searching for biome " + predicate.asString() + "...");

        Pair<BlockPos, RegistryEntry<Biome>> found = world.locateBiome(
                predicate, origin, BIOME_SEARCH_RADIUS, BIOME_SEARCH_STEP, BIOME_BLOCK_CHECK_INTERVAL);

        if (found == null) {
            throw BIOME_NOT_FOUND.create(predicate.asString());
        }

        BlockPos target = found.getFirst();
        String name = found.getSecond().getKey()
                .map(key -> key.getValue().toString())
                .orElse(predicate.asString());

        report(source, "biome", name, origin, target, world);
        return 1;
    }

    // ---- structure ----------------------------------------------------------------------------

    private static int locateStructure(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = BlockPos.ofFloored(source.getPosition());

        RegistryPredicateArgumentType.RegistryPredicate<Structure> predicate =
                RegistryPredicateArgumentType.getPredicate(
                        ctx, "structure", RegistryKeys.STRUCTURE, STRUCTURE_INVALID);

        // getEntry() already yields either a single reference or a named tag list.
        Registry<Structure> registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        RegistryEntryList<Structure> entries = predicate.getKey()
                .<java.util.Optional<RegistryEntryList<Structure>>>map(
                        key -> registry.getEntry(key)
                                .map(entry -> (RegistryEntryList<Structure>) RegistryEntryList.of(entry)),
                        tag -> registry.getEntryList(tag)
                                .map(named -> (RegistryEntryList<Structure>) named))
                .orElseThrow(() -> STRUCTURE_INVALID.create(predicate.asString()));

        Chat.warn(source, "Searching for structure " + predicate.asString()
                + ". This may generate terrain and take a moment...");

        Pair<BlockPos, RegistryEntry<Structure>> found = world.getChunkManager()
                .getChunkGenerator()
                .locateStructure(world, entries, origin, STRUCTURE_SEARCH_CHUNKS, false);

        if (found == null) {
            throw STRUCTURE_NOT_FOUND.create(predicate.asString());
        }

        BlockPos target = found.getFirst();
        String name = found.getSecond().getKey()
                .map(key -> key.getValue().toString())
                .orElse(predicate.asString());

        report(source, "structure", name, origin, target, world);
        return 1;
    }

    // ---- point of interest --------------------------------------------------------------------

    /** {@code /locate poi}, replacing vanilla's. */
    private static int locatePoi(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = BlockPos.ofFloored(source.getPosition());

        RegistryEntryPredicateArgumentType.EntryPredicate<PointOfInterestType> predicate =
                RegistryEntryPredicateArgumentType.getRegistryEntryPredicate(
                        ctx, "poi", RegistryKeys.POINT_OF_INTEREST_TYPE);

        Msg.warn(source, "Searching for " + predicate.asString()
                + ". Loading chunks as it goes, this may take a moment.");

        // Try what is already loaded first: usually instant, and costs nothing if it hits.
        Optional<BlockPos> found = world.getPointOfInterestStorage().getNearestPosition(
                predicate, origin, POI_SEARCH_RADIUS, PointOfInterestStorage.OccupationStatus.ANY);

        if (found.isEmpty()) {
            found = searchWithChunkLoading(world, origin, predicate);
        }

        if (found.isEmpty()) {
            throw POI_NOT_FOUND.create(predicate.asString());
        }
        report(source, "point of interest", predicate.asString(), origin, found.get(), world);
        return 1;
    }

    /**
     * Widen the search by loading chunks in rings around the origin.
     *
     * Chunks are pulled to FULL so their point-of-interest data is populated, checked, then
     * released. Working outward in rings means a nearby hit still returns quickly instead of paying
     * for the whole radius every time.
     */
    private static Optional<BlockPos> searchWithChunkLoading(
            ServerWorld world, BlockPos origin,
            RegistryEntryPredicateArgumentType.EntryPredicate<PointOfInterestType> predicate) {
        ChunkPos centre = new ChunkPos(origin);
        List<ChunkPos> forced = new ArrayList<>();
        try {
            for (int ring = 1; ring <= POI_SEARCH_CHUNK_RINGS; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue; // perimeter of this ring only
                        }
                        ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
                        if (world.isChunkLoaded(pos.x, pos.z)) {
                            continue;
                        }
                        try {
                            world.setChunkForced(pos.x, pos.z, true);
                            forced.add(pos);
                            world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
                        } catch (Throwable t) {
                            LPLog.error("Could not load chunk " + pos + " for POI search", t);
                        }
                    }
                }

                Optional<BlockPos> hit = world.getPointOfInterestStorage().getNearestPosition(
                        predicate, origin, POI_SEARCH_RADIUS,
                        PointOfInterestStorage.OccupationStatus.ANY);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            return Optional.empty();
        } finally {
            for (ChunkPos pos : forced) {
                try {
                    world.setChunkForced(pos.x, pos.z, false);
                } catch (Throwable t) {
                    LPLog.error("Could not release forced chunk " + pos, t);
                }
            }
        }
    }

    // ---- shared output ------------------------------------------------------------------------

    /**
     * Y the teleport button should aim at for a biome or structure hit.
     *
     * The surface heightmap answers this in the overworld, but a dimension with a ceiling has
     * bedrock as its highest block, so the heightmap points at the roof. Aiming there sends the
     * teleport looking for a floor it cannot find above the roof, and it ends up at the bottom of
     * the world. Halfway up is the honest answer instead: it is the open space people build and
     * travel in, and the teleport finds the real floor from there.
     *
     * No blocks are read. A biome or structure hit is usually in terrain that does not exist yet,
     * and touching it here would generate chunks on the server thread before the player has even
     * decided to click the button.
     */
    private static int aimHeight(ServerWorld world, BlockPos target) {
        int bottom = world.getBottomY();
        int top = world.getTopY();

        if (world.getDimension().hasCeiling()) {
            return bottom + ((top - bottom) / 2);
        }
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        return Math.max(bottom + 1, Math.min(top - 1, surface));
    }

    /** Print the hit with a safe-teleport button. */
    private static void report(ServerCommandSource source, String kind, String name,
                               BlockPos origin, BlockPos target) {
        report(source, kind, name, origin, target, null);
    }

    /**
     *
     * @param world when given, the placeholder Y in {@code target} is replaced with the real
     *              surface height at that X/Z before the teleport button is built
     */
    private static void report(ServerCommandSource source, String kind, String name,
                               BlockPos origin, BlockPos target, ServerWorld world) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));

        BlockPos buttonTarget = target;
        if (world != null) {
            buttonTarget = new BlockPos(target.getX(), aimHeight(world, target), target.getZ());
        }
        final BlockPos tpTarget = buttonTarget;

        Msg.heading(source, "Nearest " + kind);
        Msg.field(source, "Type", name);
        source.sendFeedback(() -> Msg.detail("")
                .append(Text.literal("at " + target.getX() + " ~ " + target.getZ())
                        .formatted(Formatting.WHITE))
                .append(Text.literal("  " + Msg.number(distance) + " blocks away  ")
                        .formatted(Formatting.DARK_GRAY))
                .append(TeleportService.teleportButton(tpTarget)), false);
        Msg.note(source, "The teleport button finds somewhere safe to stand and tells you "
                + "where you landed.");
    }
}
