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
 * <pre> [LP] status text aqua tag, then grey/green/yellow/red by severity
 */
public final class Msg {

    /** Prefix on single-line status messages, so mod output is distinguishable from vanilla. */
    private static final String TAG = "[LP] ";

    private Msg() {
    }

    // ---- status lines -------------------------------------------------------------------------

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

    // ---- report structure ---------------------------------------------------------------------

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

    /** A sub-heading inside a report, e.g. "Redstone" within /lp inspect. */
    public static void section(ServerCommandSource source, String title) {
        source.sendFeedback(() -> Text.literal(title).formatted(Formatting.GOLD), false);
    }

    /** {@code Label: value}, grey label, white value. */
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
     * A numbered result line: {@code 1. minecraft:stone - 402,101 (49.9%)}.
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

    /**
     * A whole result on one line: rank, name, count, share, then where it is.
     *
     * @param where trailing detail such as a coordinate, or null for none
     * @param share pass a negative value to omit the percentage
     */
    public static MutableText resultLine(int rank, String name, long count, double share,
                                         String where, Text button) {
        MutableText line = Text.literal("  ")
                .append(Text.literal(rank + ". ").formatted(Formatting.DARK_GRAY))
                .append(shortName(name))
                .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(number(count)).formatted(Formatting.AQUA));
        if (share >= 0) {
            line.append(Text.literal(" " + percent(share)).formatted(Formatting.DARK_GRAY));
        }
        if (where != null) {
            line.append(Text.literal("  " + where).formatted(Formatting.GRAY));
        }
        if (button != null) {
            line.append(Text.literal(" ")).append(button);
        }
        return line;
    }

    /** Longest an id may be before it is shortened. */
    private static final int NAME_LIMIT = 26;

    /**
     * A block or entity id trimmed to fit one chat line, with the full value on hover.
     *
     * Nothing is lost: hovering shows the id in full, and it is written out complete in an export.
     */
    public static MutableText shortName(String id) {
        String display = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (display.length() <= NAME_LIMIT) {
            return Text.literal(display).formatted(Formatting.WHITE);
        }
        String cut = display.substring(0, NAME_LIMIT - 1) + "\u2026";
        return Text.literal(cut).styled(style -> style
                .withColor(Formatting.WHITE)
                .withHoverEvent(new net.minecraft.text.HoverEvent(
                        net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal(id))));
    }

    /** The indented detail line that follows a result, e.g. its nearest coordinate. */
    public static MutableText detail(String text) {
        return Text.literal("     ").append(Text.literal(text).formatted(Formatting.GRAY));
    }

    /**
     * "... and 12 more block types", closes a truncated list.
     *
     * @param noun the plural form, which is pluralised back down when only one item remains so
     *             the line does not read "1 more block types"
     */
    public static void more(ServerCommandSource source, int remaining, String noun) {
        more(source, remaining, noun, null);
    }

    /**
     * "... and 12 more block types", closes a truncated list.
     *
     * @param noun the plural form, pluralised back down when only one item remains
     * @param hint how to see the remainder, or null to say nothing
     */
    public static void more(ServerCommandSource source, int remaining, String noun, String hint) {
        String word = remaining == 1 && noun.endsWith("s")
                ? noun.substring(0, noun.length() - 1)
                : noun;
        source.sendFeedback(() -> Text.literal("  ... and " + number(remaining) + " more " + word)
                .formatted(Formatting.DARK_GRAY), false);
        if (hint != null) {
            source.sendFeedback(() -> Text.literal("  " + hint)
                    .formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
        }
    }

    // ---- value formatting ---------------------------------------------------------------------

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
     * Spelled out rather than abbreviated: "m" reads as metres, and players think in blocks.
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

        /** A chunk radius, phrased as the reach it is. */
    /** How an area is named in chat, covering the loaded case that has no number. */
    public static String area(dev.locateplus.util.Radius radius) {
        if (radius.isLoadedArea()) {
            return "the loaded area";
        }
        return radius.typedUnit() == dev.locateplus.util.Radius.Unit.CHUNKS
                ? chunkReach(radius.chunks())
                : count(radius.blocks(), "block");
    }

    public static String chunkReach(int chunks) {
        // Zero reaches nowhere, so naming the distance would read as nothing at all.
        return chunks == 0 ? "this chunk only" : count(chunks, "chunk") + " out";
    }

    /** Reminder of the export directory, shown after a report that could have been exported. */
    public static String exportPath() {
        return "config/" + LPConstants.MOD_ID_PATH + "/exports/";
    }
}
