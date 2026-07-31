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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongVecTest {

    @Test
    @DisplayName("grows past its initial capacity without losing or reordering values")
    void growsCorrectly() {
        LongVec vec = new LongVec(4);
        for (int i = 0; i < 10_000; i++) {
            vec.add(i * 3L);
        }
        assertEquals(10_000, vec.size());
        for (int i = 0; i < 10_000; i++) {
            assertEquals(i * 3L, vec.get(i));
        }
    }

    @Test
    @DisplayName("stores negative coordinates, which packed BlockPos values routinely are")
    void handlesNegatives() {
        LongVec vec = new LongVec();
        vec.add(Long.MIN_VALUE);
        vec.add(-1L);
        assertEquals(Long.MIN_VALUE, vec.get(0));
        assertEquals(-1L, vec.get(1));
    }

    @Test
    @DisplayName("empty state")
    void emptyState() {
        LongVec vec = new LongVec();
        assertTrue(vec.isEmpty());
        vec.add(1L);
        assertFalse(vec.isEmpty());
    }

    @Test
    @DisplayName("trim keeps contents intact")
    void trimPreservesData() {
        LongVec vec = new LongVec(128);
        vec.add(7L);
        vec.add(9L);
        vec.trim();
        assertEquals(2, vec.size());
        assertEquals(7L, vec.get(0));
        assertEquals(9L, vec.get(1));
        assertEquals(2, vec.rawData().length);
    }

    @Test
    @DisplayName("out of range access fails loudly")
    void boundsChecked() {
        LongVec vec = new LongVec();
        vec.add(1L);
        assertThrows(IndexOutOfBoundsException.class, () -> vec.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> vec.get(-1));
    }
}
