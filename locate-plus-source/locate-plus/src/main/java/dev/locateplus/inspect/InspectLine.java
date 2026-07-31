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
package dev.locateplus.inspect;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * A collected block of {@code /inspect} output.
 *
 * <p>Inspectors append lines here rather than sending them straight to the player, so a section
 * that turns out to be empty can be dropped entirely instead of printing a bare heading. Keeps the
 * report short when there is nothing interesting to say.</p>
 */
public final class InspectLine {

    private final List<Text> lines = new ArrayList<>();

    /** A section heading, e.g. "Redstone". */
    public InspectLine section(String title) {
        lines.add(Text.literal("")
                .append(Text.literal(title).formatted(Formatting.GOLD)));
        return this;
    }

    /** {@code  label: value} with two-space indent. */
    public InspectLine field(String label, String value) {
        lines.add(Text.literal("  ")
                .append(Text.literal(label + ": ").formatted(Formatting.GRAY))
                .append(Text.literal(value).formatted(Formatting.WHITE)));
        return this;
    }

    /** A field whose value carries its own colour, used for warnings and highlights. */
    public InspectLine field(String label, String value, Formatting colour) {
        lines.add(Text.literal("  ")
                .append(Text.literal(label + ": ").formatted(Formatting.GRAY))
                .append(Text.literal(value).formatted(colour)));
        return this;
    }

    /** A deeper-indented continuation line, used for list items. */
    public InspectLine item(String value) {
        lines.add(Text.literal("    ")
                .append(Text.literal(value).formatted(Formatting.WHITE)));
        return this;
    }

    public InspectLine item(String value, Formatting colour) {
        lines.add(Text.literal("    ")
                .append(Text.literal(value).formatted(colour)));
        return this;
    }

    /** Pre-built text, for lines that need mixed styling. */
    public InspectLine raw(MutableText text) {
        lines.add(text);
        return this;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public List<Text> lines() {
        return lines;
    }
}
