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

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The set of chunks a scan will visit, ordered nearest-first.
 *
 * <h2>Shape</h2>
 *
 * <p>A block radius produces a <em>disc</em>, not a square. The corners of a bounding box sit up
 * to {@code r * 1.41} chunks out, well past the distance requested, so a chunk is included only
 * when its centre falls inside the radius.</p>
 *
 * <p>Nearest-first ordering means {@code /locate} usually finds its answer in the first handful of
 * chunks and can stop early, and partial results stay meaningful if a scan is cut short.</p>
 */
public final class ScanRegion {

    private final ChunkPos origin;
    private final int chunkRadius;
    private final List<ChunkPos> chunks;
    private final boolean forceload;

    private ScanRegion(ChunkPos origin, int chunkRadius, List<ChunkPos> chunks, boolean forceload) {
        this.origin = origin;
        this.chunkRadius = chunkRadius;
        this.chunks = chunks;
        this.forceload = forceload;
    }

    /**
     * Region of {@code chunkRadius} chunks, counted as "how many chunks to scan", starting with
     * the one you are standing in and spreading outwards.
     *
     * <pre>
     *   1 chunk  -> just the chunk you are in
     *   2 chunks -> that chunk plus its nearest neighbour
     *   9 chunks -> the full 3x3 around you
     * </pre>
     *
     * <p>This is a change from treating the number as a geometric radius, where "1" meant a
     * one-chunk ring and quietly scanned five chunks. Asking for one chunk and being told five
     * were scanned is confusing, and the ring interpretation is only useful if you already think
     * in chunk grids. Counting chunks is what the number looks like it means.</p>
     */
    public static ScanRegion ofChunkCount(BlockPos centre, int chunkCount, boolean forceload) {
        ChunkPos origin = new ChunkPos(centre);
        int wanted = Math.max(1, chunkCount);

        // Grow a square ring at a time until enough candidates exist, then keep the closest.
        int ring = (int) Math.ceil(Math.sqrt(wanted)) + 1;
        List<ChunkPos> candidates = new ArrayList<>((2 * ring + 1) * (2 * ring + 1));
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                candidates.add(new ChunkPos(origin.x + dx, origin.z + dz));
            }
        }
        candidates.sort((a, b) -> Long.compare(distanceSq(origin, a), distanceSq(origin, b)));

        List<ChunkPos> list = new ArrayList<>(wanted);
        for (int i = 0; i < wanted && i < candidates.size(); i++) {
            list.add(candidates.get(i));
        }
        return new ScanRegion(origin, chunkCount, list, forceload);
    }

    /**
     * Region covering every chunk within {@code chunkRadius} chunks of the origin, as a disc.
     *
     * <p>Used for block-radius scans, where the radius is a real distance rather than a count.</p>
     */
    public static ScanRegion ofChunkRadius(BlockPos centre, int chunkRadius, boolean forceload) {
        ChunkPos origin = new ChunkPos(centre);
        List<ChunkPos> list = new ArrayList<>();

        long limitSq = (long) chunkRadius * chunkRadius;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                if ((long) dx * dx + (long) dz * dz > limitSq) {
                    continue; // outside the disc, trims the box corners
                }
                list.add(new ChunkPos(origin.x + dx, origin.z + dz));
            }
        }
        list.sort((a, b) -> Long.compare(distanceSq(origin, a), distanceSq(origin, b)));
        return new ScanRegion(origin, chunkRadius, list, forceload);
    }

    /**
     * Build the region a {@link dev.locateplus.util.Radius} describes, honouring the unit the
     * player actually typed.
     *
     * <p>This exists because converting between units and back loses the player's intent. Asking
     * for {@code 100 chunks}, turning that into 1600 blocks, then rebuilding a <em>block</em>
     * radius produced {@code (1600/16)+1 = 101} chunks of radius, a disc of <b>32,017</b>
     * chunks. Every scanning command now routes through here so a chunk count stays a chunk count
     * and a block radius stays a block radius.</p>
     */
    public static ScanRegion forRadius(BlockPos centre, dev.locateplus.util.Radius radius,
                                       boolean forceload) {
        return radius.typedUnit() == dev.locateplus.util.Radius.Unit.CHUNKS
                ? ofChunkCount(centre, radius.chunks(), forceload)
                : ofBlockRadius(centre, radius.blocks(), forceload);
    }

    /**
     * Region covering a radius given in blocks.
     *
     * <p>Rounded up to whole chunks, then expanded by one so a block-radius sphere near a chunk
     * edge is fully covered. Per-block distance filtering still applies during the scan, so the
     * extra ring costs a little scanning but never reports anything out of range.</p>
     */
    public static ScanRegion ofBlockRadius(BlockPos centre, int blockRadius, boolean forceload) {
        int chunkRadius = ((blockRadius + 15) >> 4) + 1;
        return ofChunkRadius(centre, chunkRadius, forceload);
    }

    private static long distanceSq(ChunkPos a, ChunkPos b) {
        long dx = (long) a.x - b.x;
        long dz = (long) a.z - b.z;
        return dx * dx + dz * dz;
    }

    public ChunkPos origin() {
        return origin;
    }

    public int chunkRadius() {
        return chunkRadius;
    }

    /** Nearest-first chunk list. */
    public List<ChunkPos> chunks() {
        return chunks;
    }

    public int totalChunks() {
        return chunks.size();
    }

    public boolean forceload() {
        return forceload;
    }

    /** Chunk bounds actually covered, reported in export headers. */
    public String boundsDescription() {
        if (chunks.isEmpty()) {
            return "no chunks";
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkPos pos : chunks) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxZ = Math.max(maxZ, pos.z);
        }
        return String.format("[%d, %d] .. [%d, %d] (%d chunks)", minX, minZ, maxX, maxZ, chunks.size());
    }

    /**
     * Furthest horizontal distance, in blocks, that any chunk in this region reaches.
     *
     * <p>Lets an entity search bound itself by the chunks actually selected rather than by a
     * block radius, which is meaningless when the player asked for a chunk count.</p>
     */
    public int blockExtent() {
        int maxChunks = 0;
        for (ChunkPos pos : chunks) {
            maxChunks = Math.max(maxChunks, Math.max(
                    Math.abs(pos.x - origin.x), Math.abs(pos.z - origin.z)));
        }
        return (maxChunks + 1) * 16;
    }

    /** How many of these chunks are already in memory. Used for the pre-scan warning. */
    public int countLoaded(ServerWorld world) {
        int loaded = 0;
        for (ChunkPos pos : chunks) {
            if (world.isChunkLoaded(pos.x, pos.z)) {
                loaded++;
            }
        }
        return loaded;
    }
}
