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
package dev.locateplus.report;

import dev.locateplus.core.LPConfig;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.teleport.TeleportService;
import dev.locateplus.util.Radius;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turns a {@link ScanResult} into the chat report a player reads. */
public final class ChatReporter {

    private ChatReporter() {
    }

    // ---- /lp analyze blocks -------------------------------------------------------------------

    public static void blockAnalysis(ServerCommandSource source, ScanResult result, boolean exported) {
        blockAnalysis(source, result, exported, "");
    }

    /**
     *
     * @param suffix appended to the heading, e.g. {@code " (1 of 2)"} during a combined run, so
     *               two reports arriving back to back are obviously two reports
     */
    public static void blockAnalysis(ServerCommandSource source, ScanResult result,
                                     boolean exported, String suffix) {
        if (suffix.isEmpty()) {
            Msg.heading(source, "Block analysis");
        } else {
            Msg.section(source, "Blocks");
        }
        scanScope(source, result);
        Msg.field(source, "Blocks checked", Msg.number(result.positionsScanned()));

        List<BlockTally> tallies = result.blocksByCount();
        if (tallies.isEmpty()) {
            Msg.note(source, "No blocks found.");
            return;
        }

        long total = result.totalMatches();
        int shown = Math.min(LPConfig.get().chatTopN(), tallies.size());
        Msg.blank(source);

        for (int i = 0; i < shown; i++) {
            BlockTally tally = tallies.get(i);
            double share = total == 0 ? -1 : (double) tally.count() / total;
            int rank = i + 1;

            BlockPos nearest = tally.nearest();
            source.sendFeedback(() -> Msg.resultLine(rank, tally.id().toString(),
                    tally.count(), share, null,
                    nearest == null ? null : TeleportService.teleportButton(nearest)), false);
        }

        if (tallies.size() > shown) {
            Msg.more(source, tallies.size() - shown, "block types",
                    "showing the " + shown + " biggest; add 'export' for the whole list");
        }
        footer(source, result, exported, "blocks");
    }

    // ---- /lp analyze entities -----------------------------------------------------------------

    public static void entityAnalysis(ServerCommandSource source, ScanResult result, boolean exported) {
        entityAnalysis(source, result, exported, "");
    }

    /**
     *
     * @param suffix appended to the heading, as in {@link #blockAnalysis}.
     */
    public static void entityAnalysis(ServerCommandSource source, ScanResult result,
                                      boolean exported, String suffix) {
        if (suffix.isEmpty()) {
            Msg.heading(source, "Entity analysis");
        } else {
            Msg.blank(source);
            Msg.section(source, "Entities");
        }
        scanScope(source, result);

        List<ScanResult.TypeCount> counts = result.entitiesByCount();
        if (counts.isEmpty()) {
            Msg.note(source, "No entities found.");
            return;
        }

        long total = result.entities().size();
        Msg.field(source, "Entities found", Msg.number(total));

        int shown = Math.min(LPConfig.get().chatTopN(), counts.size());
        Msg.blank(source);

        for (int i = 0; i < shown; i++) {
            ScanResult.TypeCount entry = counts.get(i);
            double share = total == 0 ? -1 : (double) entry.count() / total;
            int rank = i + 1;

            EntityRecord nearest = entry.nearest();
            source.sendFeedback(() -> Msg.resultLine(rank, entry.id().toString(),
                    entry.count(), share, null,
                    nearest == null ? null
                            : TeleportService.teleportButton(nearest.blockPos())), false);
        }

        if (counts.size() > shown) {
            Msg.more(source, counts.size() - shown, "entity types",
                    "showing the " + shown + " most common; add 'export' for the whole list");
        }
        footer(source, result, exported, "entities");
    }

    // ---- /locate block ------------------------------------------------------------------------

    /** Result of {@code /locate block}. */
    public static void locateBlock(ServerCommandSource source, ScanResult result,
                                   String label, Radius radius) {
        List<BlockTally> tallies = result.blocksByCount();
        if (tallies.isEmpty()) {
            Msg.warn(source, "No " + label + " within " + describe(radius) + ".");
            suggestForceload(source, result);
            return;
        }

        Msg.heading(source, "Nearest " + label);
        Msg.field(source, "Found", Msg.count(result.totalMatches(), "match", "matches")
                + " within " + describe(radius));

        if (tallies.size() == 1) {
            singleBlockResult(source, tallies.get(0));
        } else {
            multipleBlockResults(source, tallies);
        }
        Msg.note(source, LPConfig.get().blockMarkerNeverExpires()
                ? "The nearest one is glowing through the ground until you mine it."
                : "The nearest one is glowing through the ground for "
                        + (LPConfig.get().blockMarkerTicks() / 20) + " seconds.");
    }

    /** One type: a single line naming it, where it is, and how far. */
    private static void singleBlockResult(ServerCommandSource source, BlockTally tally) {
        BlockPos pos = tally.nearest();
        if (pos == null) {
            return;
        }
        String id = tally.id().toString();
        double distance = tally.nearestDistance();

        source.sendFeedback(() -> Text.literal("  ")
                .append(Text.literal(id).formatted(Formatting.WHITE))
                .append(Text.literal(" at " + Msg.coords(pos)).formatted(Formatting.GRAY))
                .append(Text.literal("  " + Msg.distance(distance) + " away  ")
                        .formatted(Formatting.DARK_GRAY))
                .append(TeleportService.teleportButton(pos)), false);
    }

    /**
     * Several types, as happens with a tag: a per-type breakdown, closest type first.
     *
     * Ordered by distance rather than count, because "what is nearest" is the question {@code
     * /locate} is asked. The count still appears on each line.
     */
    private static void multipleBlockResults(ServerCommandSource source, List<BlockTally> tallies) {
        List<BlockTally> byDistance = new ArrayList<>(tallies);
        byDistance.sort(Comparator.comparingDouble(BlockTally::nearestDistance));

        Msg.blank(source);
        int shown = Math.min(LPConfig.get().chatTopN(), byDistance.size());

        for (int i = 0; i < shown; i++) {
            BlockTally tally = byDistance.get(i);
            BlockPos pos = tally.nearest();
            if (pos == null) {
                continue;
            }
            int rank = i + 1;
            double distance = tally.nearestDistance();

            source.sendFeedback(() -> Msg.resultLine(rank, tally.id().toString(),
                    tally.count(), -1, Msg.distance(distance),
                    TeleportService.teleportButton(pos)), false);
        }

        if (byDistance.size() > shown) {
            Msg.more(source, byDistance.size() - shown, "block types");
        }
    }

    // ---- /locate entity -----------------------------------------------------------------------

    public static void locateEntity(ServerCommandSource source, ScanResult result,
                                    String label, Radius radius) {
        EntityRecord nearest = result.nearestEntity();
        if (nearest == null) {
            Msg.warn(source, "No " + label + " within " + describe(radius) + ".");
            suggestForceload(source, result);
            return;
        }

        Msg.heading(source, "Nearest " + label);
        Msg.field(source, "Found", Msg.count(result.entities().size(), "match", "matches")
                + " within " + describe(radius));

        MutableText line = Text.literal("  ")
                .append(Text.literal(nearest.typeId().toString()).formatted(Formatting.WHITE));
        if (nearest.customName() != null) {
            line.append(Text.literal(" \"" + nearest.customName() + "\"")
                    .formatted(Formatting.YELLOW));
        }
        line.append(Text.literal(" at " + Msg.coords(nearest.blockPos())).formatted(Formatting.GRAY))
                .append(Text.literal("  " + Msg.distance(nearest.distance()) + " away  ")
                        .formatted(Formatting.DARK_GRAY))
                .append(TeleportService.teleportButton(nearest.blockPos()));
        source.sendFeedback(() -> line, false);

        if (nearest.health() != null) {
            Msg.field(source, "Health", String.format("%.1f / %.1f",
                    nearest.health(), nearest.maxHealth()));
        }
        if (nearest.itemId() != null) {
            Msg.field(source, "Item", nearest.itemCount() + "x " + nearest.itemId());
        }
        if (LPConfig.get().glowLocatedEntities()) {
            Msg.note(source, "It is glowing for a minute. Everyone nearby can see that.");
        }
    }

    // ---- shared pieces ------------------------------------------------------------------------

    /** The radius as the player expressed it, so the echo matches what they typed. */
    private static String describe(Radius radius) {
        return Msg.area(radius);
    }

    /** The one or two lines describing what a scan covered. */
    private static void scanScope(ServerCommandSource source, ScanResult result) {
        String scanned = Msg.count(result.chunksScanned(), "chunk");
        int requested = result.requestedChunks();
        if (requested > 0 && result.chunksScanned() != requested) {
            scanned += " of " + Msg.number(requested) + " requested";
        }
        Msg.field(source, "Scanned", scanned);
        if (result.chunksSkipped() > 0) {
            Msg.field(source, "Skipped", Msg.count(result.chunksSkipped(), "unloaded chunk"),
                    Formatting.YELLOW);
        }
    }

    /**
     * When a search comes up empty without force-loading, the usual reason is simply that the
     * chunks were not in memory. Say so, rather than letting it look like the mod is broken.
     */
    private static void suggestForceload(ServerCommandSource source, ScanResult result) {
        if (!result.forceload() && result.chunksSkipped() > 0) {
            Msg.note(source, Msg.count(result.chunksSkipped(), "chunk")
                    + " in range were not loaded. Add 'forceload' to include them.");
        }
    }

    private static void footer(ServerCommandSource source, ScanResult result,
                               boolean exported, String kind) {
        if (result.truncated()) {
            Msg.blank(source);
            Msg.warn(source, "Result set was very large; some coordinates were left out "
                    + "of the export.");
        }
        Msg.blank(source);
        if (exported) {
            Msg.note(source, "Full data written to " + Msg.exportPath());
        } else {
            // Echo the radius exactly as it was typed.
            String radius = result.requestedRadius();
            Msg.note(source, "Add 'export' for the full list: /lp analyze " + kind + " "
                    + (radius.isEmpty() ? "<radius>" : radius) + " export");
        }
        Msg.note(source, "Took " + Msg.duration(result.durationMillis()) + ".");
    }
}
