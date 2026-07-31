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
import dev.locateplus.core.ScanJob;
import dev.locateplus.model.ScanResult;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Walks chunk sections counting blocks, in time slices spread across ticks.
 *
 * <p>Three things keep even a 32-chunk-radius scan cheap:</p>
 * <ul>
 *   <li>The deadline is checked per <em>section</em> (4096 blocks), not per chunk. A single chunk
 *       can hold 24 sections of solid stone; checking only between chunks would let one iteration
 *       run far past the tick budget and cause exactly the lag spike the guide forbids.</li>
 *   <li>{@link ChunkSection#isEmpty()} skips the large fraction of any world that is pure air
 *       without touching a block position.</li>
 *   <li>The section palette is asked first whether any contained state can match; if not, all 4096
 *       positions are skipped at once.</li>
 * </ul>
 */
public final class BlockScanJob implements ScanJob {

    private final ServerWorld world;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final BlockMatcher matcher;
    private final ScanResult result;
    private final boolean collectPositions;
    private final int blockRadius;
    private final long radiusSq;
    private final Vec3d origin;
    private final Consumer<ScanResult> onComplete;
    private final Consumer<Throwable> onError;
    private final boolean stopWhenFound;

    private final List<ChunkPos> chunks;
    private int chunkIndex;

    /** Resume state: the chunk being scanned and the next section index within it. */
    private WorldChunk currentChunk;
    private ChunkPos currentPos;
    private int sectionCursor;

    /** Cache of block -> id; Registries.BLOCK.getId is a map lookup we would otherwise repeat millions of times. */
    private final Map<net.minecraft.block.Block, Identifier> idCache = new HashMap<>(64);

    private final long startNanos = System.nanoTime();
    private boolean finished;
    private boolean foundAny;

    public BlockScanJob(ServerWorld world, ScanRegion region, BlockMatcher matcher,
                        ScanResult result, boolean collectPositions, int blockRadius,
                        Vec3d origin, boolean stopWhenFound,
                        Consumer<ScanResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.matcher = matcher;
        this.result = result;
        this.collectPositions = collectPositions;
        this.blockRadius = blockRadius;
        this.radiusSq = blockRadius > 0 ? (long) blockRadius * blockRadius : Long.MAX_VALUE;
        this.origin = origin;
        this.stopWhenFound = stopWhenFound;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
    }

    @Override
    public String describe() {
        return "block scan (" + matcher.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            while (true) {
                // Pick up the next chunk when the previous one is exhausted.
                if (currentChunk == null) {
                    if (chunkIndex >= chunks.size()) {
                        complete();
                        return true;
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                    currentPos = chunks.get(chunkIndex++);
                    currentChunk = access.acquire(currentPos);
                    sectionCursor = 0;
                    if (currentChunk == null) {
                        continue;
                    }
                    result.addChunkScanned();
                }

                ChunkSection[] sections = currentChunk.getSectionArray();
                while (sectionCursor < sections.length) {
                    if (System.nanoTime() >= deadlineNanos) {
                        return false; // resume mid-chunk next tick
                    }
                    scanSection(sections[sectionCursor], sectionCursor);
                    sectionCursor++;
                }

                currentChunk = null;

                // /locate stops once it has a hit: chunks are ordered nearest-first, and any
                // remaining chunk starts farther away than the one that produced the match.
                if (stopWhenFound && foundAny) {
                    complete();
                    return true;
                }
            }
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    private void scanSection(ChunkSection section, int sectionIndex) {
        if (section == null || section.isEmpty()) {
            return;
        }
        if (!sectionMayContainMatch(section)) {
            return;
        }

        int sectionBottom = currentChunk.getBottomY() + (sectionIndex << 4);
        int startX = currentPos.getStartX();
        int startZ = currentPos.getStartZ();
        long scannedHere = 0;

        for (int y = 0; y < 16; y++) {
            int worldY = sectionBottom + y;
            for (int x = 0; x < 16; x++) {
                int worldX = startX + x;
                for (int z = 0; z < 16; z++) {
                    scannedHere++;

                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || !matcher.matches(state)) {
                        continue;
                    }

                    int worldZ = startZ + z;
                    // Horizontal radius: the whole Y column at a given X/Z is in range, so ores
                    // directly below you are found no matter how high you are standing.
                    double dx = origin.x - (worldX + 0.5);
                    double dz = origin.z - (worldZ + 0.5);
                    double flatSq = dx * dx + dz * dz;
                    if (blockRadius > 0 && flatSq > radiusSq) {
                        continue;
                    }
                    // True distance is still what "nearest" means when ranking results.
                    double distSq = origin.squaredDistanceTo(
                            worldX + 0.5, worldY + 0.5, worldZ + 0.5);

                    Identifier id = idCache.computeIfAbsent(state.getBlock(), Registries.BLOCK::getId);
                    result.tally(id, collectPositions)
                            .record(BlockPos.asLong(worldX, worldY, worldZ), distSq);
                    foundAny = true;
                }
            }
        }
        result.addPositionsScanned(scannedHere);
    }

    /**
     * Ask the section's palette whether any state it contains could match.
     * {@code hasAny} walks the palette rather than the 4096 block positions.
     */
    private boolean sectionMayContainMatch(ChunkSection section) {
        try {
            return section.getBlockStateContainer().hasAny(matcher::matches);
        } catch (Throwable t) {
            // If a modded palette misbehaves, fall back to scanning the section fully.
            return true;
        }
    }

    private void complete() {
        if (finished) {
            return;
        }
        finished = true;
        result.addChunksSkipped(access.skippedUnloaded());
        result.finish((System.nanoTime() - startNanos) / 1_000_000L);
        access.close();
        try {
            onComplete.accept(result);
        } catch (Throwable t) {
            LPLog.error("Block scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Block scan failed", t);
        try {
            onError.accept(t);
        } catch (Throwable ignored) {
            // reporting must not throw
        }
    }

    @Override
    public void onCancelled(Throwable cause) {
        if (!finished) {
            finished = true;
            access.close();
        }
    }
}
