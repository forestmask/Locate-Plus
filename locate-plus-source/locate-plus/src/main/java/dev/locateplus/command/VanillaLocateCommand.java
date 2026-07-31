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
import dev.locateplus.report.Chat;
import dev.locateplus.core.LPLog;
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
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Replaces vanilla {@code /locate biome} and {@code /locate structure} so their results carry a
 * safe-teleport button instead of a raw {@code /tp} link.
 *
 * <h2>How the override works</h2>
 *
 * <p>Vanilla registers {@code /locate} with {@code biome}, {@code structure} and {@code poi}
 * subcommands during its own command setup. Fabric's {@code CommandRegistrationCallback} fires
 * <em>after</em> that, and Brigadier's {@code register} merges a second tree with the same root
 * rather than rejecting it: for any literal defined in both, the child registered later replaces
 * the earlier one. Registering {@code /locate biome} here therefore overrides vanilla's, while
 * {@code poi}, which this mod does not define, is left untouched.</p>
 *
 * <p>The argument types must match vanilla's <em>exactly</em>, and in 1.20.1 the two subcommands do
 * not agree with each other, verified by disassembling {@code LocateCommand}:</p>
 *
 * <ul>
 *   <li>{@code biome} and {@code poi} use {@code RegistryEntryPredicateArgumentType}
 *       (which needs a {@link CommandRegistryAccess})</li>
 *   <li>{@code structure} uses the older {@code RegistryPredicateArgumentType}</li>
 * </ul>
 *
 * <p>Getting either wrong means the node does not match vanilla's, both trees survive the merge,
 * and vanilla wins the lookup, so the override silently does nothing.</p>
 *
 * <p>Both types are vanilla, so the command tree still serialises correctly to every client and no
 * client-side installation is required.</p>
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
     * <p>Registered as its own {@code /locate} root rather than being attached to this mod's
     * builder, so the merge happens against vanilla's existing tree.</p>
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess access) {
        dispatcher.register(literal("locate")
                .requires(source -> source.hasPermissionLevel(2))

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

    // ---- biome ---------------------------------------------------------------------------------

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

    // ---- structure -----------------------------------------------------------------------------

    private static int locateStructure(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = BlockPos.ofFloored(source.getPosition());

        RegistryPredicateArgumentType.RegistryPredicate<Structure> predicate =
                RegistryPredicateArgumentType.getPredicate(
                        ctx, "structure", RegistryKeys.STRUCTURE, STRUCTURE_INVALID);

        // getEntry() already yields either a single reference or a named tag list.
        // RegistryPredicate exposes a key-or-tag Either; resolve it against the world registry.
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

    // ---- point of interest ---------------------------------------------------------------------

    /**
     * {@code /locate poi}, replacing vanilla's.
     *
     * <p>Vanilla only consults the point-of-interest index for chunks that happen to be in
     * memory, so away from spawn it reports "could not find" even when the thing exists a few
     * hundred blocks away. This version pulls the chunks in first, which is what makes the search
     * actually reach, then releases them again.</p>
     */
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
     * <p>Chunks are pulled to FULL so their point-of-interest data is populated, checked, then
     * released. Working outward in rings means a nearby hit still returns quickly instead of
     * paying for the whole radius every time.</p>
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

    // ---- shared output -------------------------------------------------------------------------

    /**
     * Print the hit with a safe-teleport button.
     *
     * <p>Biome and structure searches return only X/Z, the Y they report is a placeholder.
     * Sending a player there with vanilla {@code /tp} is exactly what drops people inside terrain.
     * The button runs {@code /safetp}, which searches the column for somewhere to stand and then
     * says whether the landing was above, below, or away from the target.</p>
     */
    private static void report(ServerCommandSource source, String kind, String name,
                               BlockPos origin, BlockPos target) {
        report(source, kind, name, origin, target, null);
    }

    /**
     * @param world when given, the placeholder Y in {@code target} is replaced with the real
     *              surface height at that X/Z before the teleport button is built
     */
    private static void report(ServerCommandSource source, String kind, String name,
                               BlockPos origin, BlockPos target, ServerWorld world) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));

        // Biome and structure searches only resolve X/Z; the Y they hand back is a placeholder
        // (which is why the text prints "~"). Feeding that Y to the teleport button makes
        // /safetp measure its offset against a number that means nothing, so it reports a tiny
        // drop when the real one is hundreds of blocks. Resolve the surface here instead.
        BlockPos buttonTarget = target;
        if (world != null) {
            int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    target.getX(), target.getZ());
            buttonTarget = new BlockPos(target.getX(), surface, target.getZ());
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
