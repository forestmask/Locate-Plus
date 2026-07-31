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
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Every message this mod sends is built here.
 *
 * <h2>Why one class</h2>
 *
 * <p>Chat output was previously assembled inline at each call site, which meant the same idea --
 * a heading, a labelled statistic, a numbered result, was written five slightly different ways
 * and drifted apart as commands were added. Centralising it gives one place to change the look of
 * the whole mod, and one place to read to understand it.</p>
 *
 * <h2>The visual grammar</h2>
 *
 * <pre>
 *   [LP] status text            aqua tag, then grey/green/yellow/red by severity
 *
 *   Heading                     bold aqua, blank line before it
 *     Label: value              grey label, white value, indented two spaces
 *     1. thing - 402,101        numbered result, count in aqua
 *        at 120 63 -35 [TP]     detail line, indented under its result
 * </pre>
 *
 * <p>Two rules keep it readable: <b>indent shows structure</b> (nothing relies on colour alone),
 * and <b>numbers are always grouped</b> ({@code 402,101}, never {@code 402101}).</p>
 */
public final class Msg {

    /** Prefix on single-line status messages, so mod output is distinguishable from vanilla. */
    private static final String TAG = "[LP] ";

    private Msg() {
    }

    // ---- status lines ----------------------------------------------------------------------------

    /** Neutral progress, e.g. "Searching for ...". */
    public static void info(ServerCommandSource source, String text) {
        source.sendFeedback(() -> tagged(text, Formatting.GRAY), false);
    }

    /** Something finished successfully. */
    public static void success(ServerCommandSource source, String text) {
        source.sendFeedback(() -> tagged(text, Formatting.GREEN), false);
    }

    /** A caution the player should read before continuing. */
    public static void warn(ServerCommandSource source, String text) {
        source.sendFeedback(() -> tagged(text, Formatting.YELLOW), false);
    }

    /** A failure. Uses sendError so it is styled and routed like any vanilla command error. */
    public static void error(ServerCommandSource source, String text) {
        source.sendError(tagged(text, Formatting.RED));
    }

    private static MutableText tagged(String text, Formatting colour) {
        return Text.literal("")
                .append(Text.literal(TAG).formatted(Formatting.DARK_AQUA))
                .append(Text.literal(text).formatted(colour));
    }

    /** Prefix arbitrary text with the mod tag, for messages that need mixed styling. */
    public static MutableText prefixed(Text body) {
        return Text.literal("")
                .append(Text.literal(TAG).formatted(Formatting.DARK_AQUA))
                .append(body);
    }

    // ---- report structure ------------------------------------------------------------------------

    /** A blank spacer line. Used sparingly, to separate a report from the chat above it. */
    public static void blank(ServerCommandSource source) {
        source.sendFeedback(Text::empty, false);
    }

    /** Section title: blank line, then bold aqua. Opens every multi-line report. */
    public static void heading(ServerCommandSource source, String title) {
        blank(source);
        source.sendFeedback(() -> Text.literal(title)
                .formatted(Formatting.AQUA, Formatting.BOLD), false);
    }

    /** A sub-heading inside a report, e.g. "Redstone" within /inspect. */
    public static void section(ServerCommandSource source, String title) {
        source.sendFeedback(() -> Text.literal(title).formatted(Formatting.GOLD), false);
    }

    /** {@code   Label: value}, grey label, white value. */
    public static void field(ServerCommandSource source, String label, String value) {
        source.sendFeedback(() -> fieldText(label, value, Formatting.WHITE), false);
    }

    /** A field whose value needs emphasis, e.g. a warning count in yellow. */
    public static void field(ServerCommandSource source, String label, String value,
                             Formatting colour) {
        source.sendFeedback(() -> fieldText(label, value, colour), false);
    }

    private static MutableText fieldText(String label, String value, Formatting colour) {
        return Text.literal("  ")
                .append(Text.literal(label + ": ").formatted(Formatting.GRAY))
                .append(Text.literal(value).formatted(colour));
    }

    /** A dim continuation line, for hints and secondary detail. */
    public static void note(ServerCommandSource source, String text) {
        source.sendFeedback(() -> Text.literal("  " + text)
                .formatted(Formatting.DARK_GRAY), false);
    }

    /**
     * A numbered result line: {@code   1. minecraft:stone - 402,101 (49.9%)}.
     *
     * @param share pass a negative value to omit the percentage
     */
    public static MutableText result(int rank, String name, long count, double share) {
        MutableText line = Text.literal("  ")
                .append(Text.literal(rank + ". ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(name).formatted(Formatting.WHITE))
                .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(number(count)).formatted(Formatting.AQUA));
        if (share >= 0) {
            line.append(Text.literal("  " + percent(share)).formatted(Formatting.DARK_GRAY));
        }
        return line;
    }

    /** The indented detail line that follows a result, e.g. its nearest coordinate. */
    public static MutableText detail(String text) {
        return Text.literal("     ").append(Text.literal(text).formatted(Formatting.GRAY));
    }

    /** "... and 12 more", closes a truncated list. */
    public static void more(ServerCommandSource source, int remaining, String noun) {
        source.sendFeedback(() -> Text.literal("  ... and " + remaining + " more " + noun)
                .formatted(Formatting.DARK_GRAY), false);
    }

    // ---- value formatting ------------------------------------------------------------------------

    /** Thousands separators: {@code 402101} becomes {@code 402,101}. */
    public static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** {@code 0.4991} becomes {@code 49.9%}. One decimal is enough to compare at a glance. */
    public static String percent(double fraction) {
        return String.format(Locale.US, "%.1f%%", fraction * 100.0);
    }

    /** {@code 120 63 -35}, the form players can paste straight into a command. */
    public static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static String coords(Vec3d pos) {
        return String.format(Locale.US, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
    }

    /**
     * A distance in blocks, e.g. {@code 12.3 blocks}.
     *
     * <p>Spelled out rather than abbreviated: "m" reads as metres, and players think in blocks.</p>
     */
    public static String distance(double blocks) {
        return String.format(Locale.US, "%.1f blocks", blocks);
    }

    /** {@code 1,240 ms} or {@code 3.4 s}, whichever reads better. */
    public static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.US, "%.1f s", millis / 1000.0);
    }

    /** Pluralise a noun against a count: {@code 1 entity}, {@code 4 entities}. */
    public static String count(long value, String singular, String plural) {
        return number(value) + " " + (value == 1 ? singular : plural);
    }

    /** Convenience for the common regular-plural case. */
    public static String count(long value, String singular) {
        return count(value, singular, singular + "s");
    }

    /** Reminder of the export directory, shown after a report that could have been exported. */
    public static String exportPath() {
        return "config/" + LPConstants.MOD_ID_PATH + "/exports/";
    }
}
