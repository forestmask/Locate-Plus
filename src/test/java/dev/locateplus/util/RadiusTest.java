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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unit is now a command literal ({@code 64 blocks}) rather than a suffix ({@code 64b}), so
 * there is no parsing left to test, only the conversion and the wording.
 */
class RadiusTest {

    @Test
    @DisplayName("chunks carry their block equivalent")
    void chunksToBlocks() {
        Radius r = Radius.ofChunks(4);
        assertEquals(4, r.chunks());
        assertEquals(64, r.blocks());
        assertEquals(Radius.Unit.CHUNKS, r.typedUnit());
    }

    @Test
    @DisplayName("blocks carry their chunk equivalent")
    void blocksToChunks() {
        Radius r = Radius.ofBlocks(64);
        assertEquals(64, r.blocks());
        assertEquals(4, r.chunks());
        assertEquals(Radius.Unit.BLOCKS, r.typedUnit());
    }

    @Test
    @DisplayName("blocks round UP to chunks so nothing requested is skipped")
    void roundsUp() {
        assertEquals(1, Radius.ofBlocks(1).chunks());
        assertEquals(1, Radius.ofBlocks(16).chunks());
        assertEquals(2, Radius.ofBlocks(17).chunks());
        assertEquals(2, Radius.ofBlocks(20).chunks());
        assertEquals(2, Radius.ofBlocks(32).chunks());
        assertEquals(3, Radius.ofBlocks(33).chunks());
    }

    @Test
    @DisplayName("radius 0 chunks is the single origin chunk")
    void zeroChunks() {
        Radius r = Radius.ofChunks(0);
        assertEquals(0, r.chunks());
        assertEquals(0, r.blocks());
    }

    @Test
    @DisplayName("both units always appear, in the order matching what was typed")
    void describesBothUnits() {
        assertEquals("4 chunks (64 blocks)", Radius.ofChunks(4).describe());
        assertEquals("64 blocks (4 chunks)", Radius.ofBlocks(64).describeBlocksFirst());
        assertEquals("4 chunks (64 blocks)", Radius.ofChunks(4).describeNatural());
        assertEquals("64 blocks (4 chunks)", Radius.ofBlocks(64).describeNatural());
    }

    @Test
    @DisplayName("large radii are allowed: the mod warns about cost, it does not refuse")
    void largeRadiiAllowed() {
        Radius huge = Radius.ofChunks(1000);
        assertEquals(1000, huge.chunks());
        assertEquals(16000, huge.blocks());

        Radius farther = Radius.ofBlocks(50_000);
        assertEquals(50_000, farther.blocks());
        assertEquals(3125, farther.chunks());
    }

    @Test
    @DisplayName("singular wording when a value is 1")
    void singularWording() {
        assertEquals("1 chunk (16 blocks)", Radius.ofChunks(1).describe());
        assertEquals("1 block (1 chunk)", Radius.ofBlocks(1).describeBlocksFirst());
    }
}
