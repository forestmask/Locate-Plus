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

import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.platform.Services;
import dev.locateplus.util.LongVec;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes the {@code .txt} export.
 *
 * <p>Always runs on a background thread against an already-snapshotted {@link ScanResult}, so a
 * multi-million-line export never blocks a tick. Text is streamed straight to disk rather than
 * assembled in memory, which matters when a single scan can produce millions of coordinates.</p>
 */
public final class ExportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HUMAN_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ExportWriter() {
    }

    /** @return the written report path. */
    public static Path[] write(ScanResult result) throws IOException {
        Path dir = Services.platform().exportDir();
        Files.createDirectories(dir);

        String kind = result.kind() == ScanResult.Kind.BLOCKS ? "blocks" : "entities";
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        String base = kind + "_" + stamp;

        Path txt = uniquePath(dir, base, ".txt");
        writeText(result, txt);
        return new Path[]{txt};
    }

    private static Path uniquePath(Path dir, String base, String extension) {
        Path candidate = dir.resolve(base + extension);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "_" + suffix++ + extension);
        }
        return candidate;
    }

    // ---- text -----------------------------------------------------------------------------

    private static void writeText(ScanResult result, Path path) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            String kind = result.kind() == ScanResult.Kind.BLOCKS ? "BLOCK" : "ENTITY";
            out.write("=".repeat(72));
            out.newLine();
            out.write(LPConstants.MOD_NAME + " " + kind + " ANALYSIS");
            out.newLine();
            out.write("=".repeat(72));
            out.newLine();
            out.newLine();

            writeHeader(result, out);
            out.newLine();

            if (result.kind() == ScanResult.Kind.BLOCKS) {
                writeBlockBody(result, out);
            } else {
                writeEntityBody(result, out);
            }
        }
    }

    private static void writeHeader(ScanResult result, BufferedWriter out) throws IOException {
        LocalDateTime when = LocalDateTime.now();
        line(out, "Scan date/time", when.format(HUMAN_STAMP));
        line(out, "Minecraft version", Services.platform().minecraftVersion());
        line(out, "Mod version", Services.platform().modVersion()
                + " (" + Services.platform().loaderName() + ")");
        line(out, "Dimension", result.dimensionId());
        line(out, "Source position", Chat.coords(result.originBlock()));
        line(out, "Chunk radius", String.valueOf(result.chunkRadius()));
        line(out, "Block radius", result.blockRadius() > 0
                ? String.valueOf(result.blockRadius())
                : String.valueOf(result.chunkRadius() * 16));
        line(out, "Chunk bounds", result.boundsDescription());
        line(out, "Chunks scanned", Chat.number(result.chunksScanned()));
        line(out, "Chunks skipped (unloaded)", Chat.number(result.chunksSkipped()));
        line(out, "Force-load used", String.valueOf(result.forceload()));
        if (result.kind() == ScanResult.Kind.BLOCKS) {
            line(out, "Block positions scanned", Chat.number(result.positionsScanned()));
        }
        line(out, "Total matches", Chat.number(result.totalMatches()));
        line(out, "Scan duration", result.durationMillis() + " ms");
        if (result.truncated()) {
            line(out, "NOTE", "Coordinate list truncated at "
                    + Chat.number(LPConstants.MAX_EXPORT_POSITIONS) + " entries per type.");
        }
    }

    private static void line(BufferedWriter out, String label, String value) throws IOException {
        out.write(String.format("%-28s %s", label + ":", value));
        out.newLine();
    }

    private static void writeBlockBody(ScanResult result, BufferedWriter out) throws IOException {
        List<BlockTally> tallies = result.blocksByCount();
        long total = result.totalMatches();

        out.write("-".repeat(72));
        out.newLine();
        out.write("SUMMARY (" + tallies.size() + " block types)");
        out.newLine();
        out.write("-".repeat(72));
        out.newLine();

        for (int i = 0; i < tallies.size(); i++) {
            BlockTally tally = tallies.get(i);
            double share = total == 0 ? 0 : (double) tally.count() / total;
            out.write(String.format(Locale.US, "%4d. %-48s %14s  %7s",
                    i + 1, tally.id(), Chat.number(tally.count()), Chat.percent(share)));
            out.newLine();
            if (tally.nearest() != null) {
                out.write(String.format(Locale.US, "      nearest: %s  (%.1f m)",
                        Chat.coords(tally.nearest()), tally.nearestDistance()));
                out.newLine();
            }
        }

        out.newLine();
        out.write("-".repeat(72));
        out.newLine();
        out.write("ALL COORDINATES");
        out.newLine();
        out.write("-".repeat(72));
        out.newLine();

        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (BlockTally tally : tallies) {
            LongVec positions = tally.positions();
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            out.newLine();
            out.write(tally.id() + "  (" + Chat.number(tally.count()) + ")");
            out.newLine();
            long[] raw = positions.rawData();
            for (int i = 0; i < positions.size(); i++) {
                cursor.set(BlockPos.unpackLongX(raw[i]),
                        BlockPos.unpackLongY(raw[i]),
                        BlockPos.unpackLongZ(raw[i]));
                out.write("  " + cursor.getX() + " " + cursor.getY() + " " + cursor.getZ());
                out.newLine();
            }
            if (tally.truncated()) {
                out.write("  ... truncated");
                out.newLine();
            }
        }
    }

    private static void writeEntityBody(ScanResult result, BufferedWriter out) throws IOException {
        List<ScanResult.TypeCount> counts = result.entitiesByCount();
        long total = result.totalMatches();

        out.write("-".repeat(72));
        out.newLine();
        out.write("SUMMARY (" + counts.size() + " entity types)");
        out.newLine();
        out.write("-".repeat(72));
        out.newLine();

        for (int i = 0; i < counts.size(); i++) {
            ScanResult.TypeCount entry = counts.get(i);
            double share = total == 0 ? 0 : (double) entry.count() / total;
            out.write(String.format(Locale.US, "%4d. %-48s %14s  %7s",
                    i + 1, entry.id(), Chat.number(entry.count()), Chat.percent(share)));
            out.newLine();
        }

        out.newLine();
        out.write("-".repeat(72));
        out.newLine();
        out.write("ENTITY DETAIL");
        out.newLine();
        out.write("-".repeat(72));
        out.newLine();

        // Copy before sorting: this runs on a background thread and the server thread may still
        // be reading the same list to build the chat report. Sorting it in place is a data race.
        List<EntityRecord> entities = new java.util.ArrayList<>(result.entities());
        entities.sort(java.util.Comparator.comparingDouble(EntityRecord::distance));

        for (EntityRecord record : entities) {
            out.newLine();
            out.write(record.typeId().toString());
            out.newLine();
            line(out, "  uuid", record.uuid().toString());
            line(out, "  name", record.displayName());
            if (record.customName() != null) {
                line(out, "  custom name", record.customName());
            }
            line(out, "  position", String.format(Locale.US, "%.3f %.3f %.3f",
                    record.pos().x, record.pos().y, record.pos().z));
            line(out, "  block position", Chat.coords(record.blockPos()));
            line(out, "  distance", String.format(Locale.US, "%.2f m", record.distance()));
            line(out, "  glowing", String.valueOf(record.glowing()));
            if (record.health() != null) {
                line(out, "  health", String.format(Locale.US, "%.1f / %.1f",
                        record.health(), record.maxHealth()));
            }
            if (record.itemId() != null) {
                line(out, "  item", record.itemCount() + "x " + record.itemId());
            }
        }
    }


    /** Convenience used by the command layer's async export path. */
    public static void writeAsyncLogged(ScanResult result, java.util.function.Consumer<Path[]> onDone,
                                        java.util.function.Consumer<Throwable> onFail) {
        try {
            Path[] paths = write(result);
            onDone.accept(paths);
        } catch (Throwable t) {
            LPLog.error("Export failed", t);
            onFail.accept(t);
        }
    }
}
