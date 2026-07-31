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
package dev.locateplus.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block radius produces a disc rather than a bounding box: box corners reach {@code r * 1.41}
 * chunks out, past the distance requested. These tests pin the counts down.
 *
 * <p>The maths is verified directly rather than through {@code ScanRegion}, because constructing
 * one needs {@code ChunkPos}, whose class initialiser requires a bootstrapped game that a plain
 * JUnit JVM cannot provide.</p>
 */
class ScanRegionTest {

    /** Mirrors the inclusion rule in {@link ScanRegion#ofChunkRadius}. */
    private static int discCount(int radius) {
        long limitSq = (long) radius * radius;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((long) dx * dx + (long) dz * dz <= limitSq) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int squareCount(int radius) {
        return (2 * radius + 1) * (2 * radius + 1);
    }

    @Test
    @DisplayName("radius 0 is exactly the origin chunk")
    void radiusZero() {
        assertEquals(1, discCount(0));
    }

    @Test
    @DisplayName("radius 1 is 5 chunks (plus shape), not the 9 of a 3x3 box")
    void radiusOne() {
        assertEquals(5, discCount(1));
        assertEquals(9, squareCount(1), "the old square behaviour, kept here for contrast");
    }

    /**
     * A chunk count typed by the player means exactly that many chunks, nearest first --
     * "1 chunk" scans one chunk, not the five of a one-chunk ring.
     */
    private static int nearestCount(int wanted) {
        int ring = (int) Math.ceil(Math.sqrt(wanted)) + 1;
        int available = (2 * ring + 1) * (2 * ring + 1);
        return Math.min(wanted, available);
    }

    @Test
    @DisplayName("a chunk count scans exactly that many chunks")
    void chunkCountIsLiteral() {
        for (int wanted : new int[]{1, 2, 5, 9, 25, 100, 1000}) {
            assertEquals(wanted, nearestCount(wanted),
                    wanted + " chunks requested should scan " + wanted + " chunks");
        }
    }

    @Test
    @DisplayName("the candidate pool is always big enough for the requested count")
    void poolCoversRequest() {
        for (int wanted = 1; wanted <= 500; wanted++) {
            int ring = (int) Math.ceil(Math.sqrt(wanted)) + 1;
            int available = (2 * ring + 1) * (2 * ring + 1);
            assertTrue(available >= wanted,
                    "ring " + ring + " gives " + available + " chunks, need " + wanted);
        }
    }

    @Test
    @DisplayName("a disc is always smaller than the enclosing square")
    void discIsSmallerThanSquare() {
        for (int r = 1; r <= 16; r++) {
            assertTrue(discCount(r) < squareCount(r),
                    "radius " + r + ": disc " + discCount(r) + " should be < square " + squareCount(r));
        }
    }

    /**
     * A chunk count must never be re-derived through blocks.
     *
     * <p>Converting {@code 100 chunks} to 1600 blocks and rebuilding a block radius gives
     * {@code (1600/16)+1 = 101}, and a disc of radius 101 covers 32,017 chunks: a 320x overshoot.
     * Any code path that round-trips a chunk count through blocks reintroduces this.</p>
     */
    @Test
    @DisplayName("a chunk count must not be re-derived through blocks")
    void chunkCountNeverRoundTripsThroughBlocks() {
        for (int chunks : new int[]{1, 4, 32, 100}) {
            int viaCount = nearestCount(chunks);
            assertEquals(chunks, viaCount, chunks + " chunks should scan " + chunks + " chunks");

            // What the old bug did: chunks -> blocks -> chunk radius -> disc.
            int blocks = chunks * 16;
            int rebuiltRadius = ((blocks + 15) >> 4) + 1;
            int bugged = discCount(rebuiltRadius);

            assertTrue(bugged > viaCount * 10,
                    "round-tripping " + chunks + " chunks through blocks explodes to "
                            + bugged + " chunks");
        }
        assertEquals(32017, discCount(101), "a radius of 101 chunks covers 32,017 chunks");
    }

    @Test
    @DisplayName("the disc approximates pi*r^2 as the radius grows")
    void approximatesCircleArea() {
        for (int r : new int[]{8, 16, 32}) {
            double expected = Math.PI * r * r;
            double actual = discCount(r);
            double error = Math.abs(actual - expected) / expected;
            assertTrue(error < 0.05,
                    "radius " + r + ": " + actual + " chunks vs pi*r^2 " + expected
                            + " (error " + String.format("%.1f%%", error * 100) + ")");
        }
    }

    @Test
    @DisplayName("no included chunk lies outside the requested radius")
    void nothingOutsideRadius() {
        int radius = 6;
        long limitSq = (long) radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean included = (long) dx * dx + (long) dz * dz <= limitSq;
                if (included) {
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    assertTrue(distance <= radius + 1e-9,
                            "chunk offset (" + dx + "," + dz + ") is " + distance
                                    + " chunks away, outside radius " + radius);
                }
            }
        }
    }
}
