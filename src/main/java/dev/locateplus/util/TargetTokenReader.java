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
package dev.locateplus.util;

import com.mojang.brigadier.StringReader;

/**
 * Consumes exactly one {@code /locate entity} target token from a command line.
 *
 * <p>Extracted from the argument type so it can be unit tested without booting Minecraft, and
 * because the rules are fiddly enough to be worth isolating:</p>
 *
 * <ul>
 *   <li>A selector's brackets may contain spaces: {@code @e[type=zombie, distance=..5]} is one
 *       token, so splitting on whitespace would truncate it and produce a parse error.</li>
 *   <li>Brackets nest: {@code @e[nbt={Inventory:[{id:"minecraft:stone"}]}]}. Depth is tracked
 *       rather than scanning for the first {@code ]}.</li>
 *   <li>Quoted sections may themselves contain brackets, e.g. {@code @e[name="a]b"}. Characters
 *       inside quotes must not affect depth.</li>
 * </ul>
 *
 * <p>Deliberately dependency-free apart from Brigadier, which is present on every loader and every
 * Minecraft version this mod targets.</p>
 */
public final class TargetTokenReader {

    private TargetTokenReader() {
    }

    /**
     * Read one token, advancing the reader's cursor past it.
     *
     * @return the raw token text, which may be empty if the reader is already at the end
     */
    public static String read(StringReader reader) {
        int start = reader.getCursor();

        if (reader.canRead() && reader.peek() == '@') {
            reader.skip(); // '@'
            if (reader.canRead() && reader.peek() != ' ' && reader.peek() != '[') {
                reader.skip(); // selector letter: a, e, p, r, s
            }
            if (reader.canRead() && reader.peek() == '[') {
                consumeBrackets(reader);
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        // Quoted target, e.g. "Some Player".
        if (reader.canRead() && (reader.peek() == '"' || reader.peek() == '\'')) {
            char quote = reader.read();
            boolean escaped = false;
            while (reader.canRead()) {
                char c = reader.read();
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    break;
                }
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    /** Consume a balanced {@code [...]} group, ignoring brackets that appear inside quotes. */
    private static void consumeBrackets(StringReader reader) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        boolean escaped = false;

        while (reader.canRead()) {
            char c = reader.read();

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }
}
