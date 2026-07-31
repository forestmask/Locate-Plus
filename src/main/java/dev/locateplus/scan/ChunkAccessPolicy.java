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

import dev.locateplus.core.LPLog;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides how a scan is allowed to obtain chunks, and cleans up afterwards.
 *
 * <p>Without {@code forceload} a scan only ever touches chunks already in memory, it must never
 * silently generate terrain. With {@code forceload} chunks are pulled in via the vanilla ticket
 * system and every ticket added is released when the scan finishes.</p>
 */
public final class ChunkAccessPolicy implements AutoCloseable {

    private final ServerWorld world;
    private final boolean forceload;
    private final Set<Long> addedTickets = new LinkedHashSet<>();

    private int skippedUnloaded;

    public ChunkAccessPolicy(ServerWorld world, boolean forceload) {
        this.world = world;
        this.forceload = forceload;
    }

    /**
     * @return the chunk to scan, or {@code null} if it is unavailable under this policy
     */
    public WorldChunk acquire(ChunkPos pos) {
        if (!forceload) {
            if (!world.isChunkLoaded(pos.x, pos.z)) {
                skippedUnloaded++;
                return null;
            }
            // getWorldChunk(...) returns null rather than loading, which is exactly what we want.
            return world.getChunkManager().getWorldChunk(pos.x, pos.z);
        }

        boolean alreadyLoaded = world.isChunkLoaded(pos.x, pos.z);
        if (!alreadyLoaded) {
            // A forced ticket keeps the chunk resident for the duration of the scan.
            world.setChunkForced(pos.x, pos.z, true);
            addedTickets.add(pos.toLong());
        }

        Chunk chunk = world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
        if (chunk instanceof WorldChunk worldChunk) {
            return worldChunk;
        }
        skippedUnloaded++;
        return null;
    }

    /** Chunks that could not be scanned, reported in chat and in export headers. */
    public int skippedUnloaded() {
        return skippedUnloaded;
    }

    public int forcedChunkCount() {
        return addedTickets.size();
    }

    /** Release every ticket this scan added. Safe to call twice. */
    @Override
    public void close() {
        if (addedTickets.isEmpty()) {
            return;
        }
        for (long packed : addedTickets) {
            ChunkPos pos = new ChunkPos(packed);
            try {
                world.setChunkForced(pos.x, pos.z, false);
            } catch (Throwable t) {
                LPLog.error("Failed to release forced chunk " + pos, t);
            }
        }
        addedTickets.clear();
    }
}
