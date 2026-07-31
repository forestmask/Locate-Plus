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
package dev.locateplus.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one scan produced.
 *
 * <p>Populated on the server thread, then handed to background threads for sorting, formatting and
 * file writing. It holds no references to live world objects, so that hand-off is safe.</p>
 */
public final class ScanResult {

    public enum Kind { BLOCKS, ENTITIES }

    private final Kind kind;
    private final String dimensionId;
    private final BlockPos originBlock;
    private final Vec3d originExact;
    private final int chunkRadius;
    private final int blockRadius;
    private final String boundsDescription;
    private final boolean forceload;
    private final long startedAtEpochMillis;

    private final Map<Identifier, BlockTally> blockTallies = new HashMap<>();
    private final PositionBudget positionBudget = new PositionBudget();
    private final List<EntityRecord> entities = new ArrayList<>();

    private int chunksScanned;
    private int requestedChunks = -1;
    private int chunksSkipped;
    private long positionsScanned;
    private long durationMillis;
    private boolean truncated;

    public ScanResult(Kind kind, String dimensionId, BlockPos originBlock, Vec3d originExact,
                      int chunkRadius, int blockRadius, String boundsDescription, boolean forceload) {
        this.kind = kind;
        this.dimensionId = dimensionId;
        this.originBlock = originBlock;
        this.originExact = originExact;
        this.chunkRadius = chunkRadius;
        this.blockRadius = blockRadius;
        this.boundsDescription = boundsDescription;
        this.forceload = forceload;
        this.startedAtEpochMillis = System.currentTimeMillis();
    }

    // ---- collection ---------------------------------------------------------------------------

    public BlockTally tally(Identifier id, boolean collectPositions) {
        return blockTallies.computeIfAbsent(id,
                key -> new BlockTally(key, collectPositions, positionBudget));
    }

    public void addEntity(EntityRecord record) {
        entities.add(record);
    }

    /**
     * How many chunks the player actually asked for, when that is a meaningful number.
     *
     * <p>Only set for a chunk-count scan. A block-radius scan covers whatever chunks the radius
     * touches, so comparing the two would report nonsense like "49 chunks of 4 requested".</p>
     */
    public void setRequestedChunks(int requested) {
        this.requestedChunks = requested;
    }

    public int requestedChunks() {
        return requestedChunks;
    }

    public void addChunkScanned() {
        chunksScanned++;
    }

    public void addChunksSkipped(int count) {
        chunksSkipped = count;
    }

    public void addPositionsScanned(long count) {
        positionsScanned += count;
    }

    public void markTruncated() {
        truncated = true;
    }

    public void finish(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    // ---- queries ------------------------------------------------------------------------------

    public Kind kind() {
        return kind;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public BlockPos originBlock() {
        return originBlock;
    }

    public Vec3d originExact() {
        return originExact;
    }

    public int chunkRadius() {
        return chunkRadius;
    }

    public int blockRadius() {
        return blockRadius;
    }

    public String boundsDescription() {
        return boundsDescription;
    }

    public boolean forceload() {
        return forceload;
    }

    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public int chunksScanned() {
        return chunksScanned;
    }

    public int chunksSkipped() {
        return chunksSkipped;
    }

    public long positionsScanned() {
        return positionsScanned;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public boolean truncated() {
        return truncated || positionBudget.exhausted()
                || blockTallies.values().stream().anyMatch(BlockTally::truncated);
    }

    public List<EntityRecord> entities() {
        return entities;
    }

    public boolean isEmpty() {
        return blockTallies.isEmpty() && entities.isEmpty();
    }

    /** Total matches across all types. */
    public long totalMatches() {
        if (kind == Kind.ENTITIES) {
            return entities.size();
        }
        long total = 0;
        for (BlockTally tally : blockTallies.values()) {
            total += tally.count();
        }
        return total;
    }

    /** Block tallies, most common first. Safe to call from a background thread once scanning ends. */
    public List<BlockTally> blocksByCount() {
        List<BlockTally> list = new ArrayList<>(blockTallies.values());
        list.sort(Comparator.comparingLong(BlockTally::count).reversed()
                .thenComparing(t -> t.id().toString()));
        return list;
    }

    /** Entity counts by type, most common first. */
    public List<TypeCount> entitiesByCount() {
        Map<Identifier, TypeCount> grouped = new HashMap<>();
        for (EntityRecord record : entities) {
            grouped.computeIfAbsent(record.typeId(), TypeCount::new).accept(record);
        }
        List<TypeCount> list = new ArrayList<>(grouped.values());
        list.sort(Comparator.comparingLong(TypeCount::count).reversed()
                .thenComparing(t -> t.id().toString()));
        return list;
    }

    /** Nearest entity overall, or {@code null}. */
    public EntityRecord nearestEntity() {
        EntityRecord best = null;
        for (EntityRecord record : entities) {
            if (best == null || record.distance() < best.distance()) {
                best = record;
            }
        }
        return best;
    }

    /** Aggregated per-type entity counts with the nearest example of each. */
    public static final class TypeCount {
        private final Identifier id;
        private long count;
        private EntityRecord nearest;

        TypeCount(Identifier id) {
            this.id = id;
        }

        void accept(EntityRecord record) {
            count++;
            if (nearest == null || record.distance() < nearest.distance()) {
                nearest = record;
            }
        }

        public Identifier id() {
            return id;
        }

        public long count() {
            return count;
        }

        public EntityRecord nearest() {
            return nearest;
        }
    }
}
