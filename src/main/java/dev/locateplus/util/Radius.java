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

import dev.locateplus.core.LPConstants;

/**
 * A radius plus the unit it was given in.
 *
 * <p>The unit is a separate literal in the command tree rather than a suffix on the number:</p>
 *
 * <pre>
 *   /locate block minecraft:iron_ore 64 blocks forceload
 *   /analyzechunks blocks 4 chunks export
 * </pre>
 *
 * <p>Making it a Brigadier literal rather than parsing {@code 64b} means tab-completion offers the
 * unit, a wrong unit is a parse error instead of a silent misreading, and the number stays a plain
 * {@code integer()} argument with real range checking.</p>
 *
 * <p>Both units are always carried, so every command can echo the conversion back and the player
 * never has to guess which one was applied. Block-to-chunk conversion rounds <em>up</em>: asking
 * for 20 blocks scans 2 chunks, because 1 chunk would silently miss part of the request.</p>
 */
public final class Radius {

    public enum Unit { CHUNKS, BLOCKS }

    private final int chunks;
    private final int blocks;
    private final Unit typedUnit;

    private Radius(int chunks, int blocks, Unit typedUnit) {
        this.chunks = chunks;
        this.blocks = blocks;
        this.typedUnit = typedUnit;
    }

    public int chunks() {
        return chunks;
    }

    public int blocks() {
        return blocks;
    }

    /** The unit the player actually typed, before any conversion. */
    public Unit typedUnit() {
        return typedUnit;
    }

    public static Radius ofChunks(int chunks) {
        return new Radius(chunks, chunks * 16, Unit.CHUNKS);
    }

    public static Radius ofBlocks(int blocks) {
        return new Radius(chunksFor(blocks), blocks, Unit.BLOCKS);
    }

    /** Round up so nothing inside the requested block radius is skipped. */
    private static int chunksFor(int blocks) {
        return Math.max(1, (blocks + 15) / 16);
    }

    /** {@code "4 chunks (64 blocks)"}, chunk-first, for /analyzechunks. */
    public String describe() {
        return chunks + (chunks == 1 ? " chunk (" : " chunks (")
                + blocks + (blocks == 1 ? " block)" : " blocks)");
    }

    /** {@code "64 blocks (4 chunks)"}, block-first, for /locate and the entity commands. */
    public String describeBlocksFirst() {
        return blocks + (blocks == 1 ? " block (" : " blocks (")
                + chunks + (chunks == 1 ? " chunk)" : " chunks)");
    }

    /** Whichever ordering matches the unit the player typed. */
    public String describeNatural() {
        return typedUnit == Unit.CHUNKS ? describe() : describeBlocksFirst();
    }

    // ---- limits ---------------------------------------------------------------------------------

    public static int maxChunks() {
        return LPConstants.MAX_CHUNK_RADIUS;
    }

    public static int maxBlocks() {
        return LPConstants.MAX_BLOCK_RADIUS;
    }
}
