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

import dev.locateplus.core.LPConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one {@code /locate item} scan produced.
 *
 * Populated on the server thread, then handed to background threads for sorting, formatting and
 * file writing. Holds no live world references, so that hand-off is safe.
 */
public final class ItemResult {

    private final String label;
    private final String dimensionId;
    private final BlockPos originBlock;
    private final Vec3d originExact;
    private final int chunkRadius;
    private final int blockRadius;
    private final String boundsDescription;
    private final boolean forceload;
    private final long startedAtEpochMillis;

    private final List<ItemHit> hits = new ArrayList<>();
    private final Map<ItemHit.Source, Long> countsBySource = new EnumMap<>(ItemHit.Source.class);

    private long totalItems;
    private int chunksScanned;
    private int requestedChunks = -1;
    private int chunksSkipped;
    private long durationMillis;
    private boolean truncated;

    public ItemResult(String label, String dimensionId, BlockPos originBlock, Vec3d originExact,
                      int chunkRadius, int blockRadius, String boundsDescription, boolean forceload) {
        this.label = label;
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

    /**
     * Record one find.
     *
     * The running total keeps counting after the hit list is full, so a truncated report still
     * states the true number of items even though it cannot list every location.
     */
    public void add(ItemHit hit) {
        totalItems += hit.count();
        countsBySource.merge(hit.source(), (long) hit.count(), Long::sum);

        if (hits.size() >= LPConfig.get().maxItemHits()) {
            truncated = true;
            return;
        }
        hits.add(hit);
    }

    public void addChunkScanned() {
        chunksScanned++;
    }

    public void addChunksSkipped(int count) {
        chunksSkipped = count;
    }

    public void setRequestedChunks(int requested) {
        this.requestedChunks = requested;
    }

    public void finish(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    // ---- queries ------------------------------------------------------------------------------

    /** What was searched for, as the player typed it. */
    public String label() {
        return label;
    }

    /**
     * The label as a countable noun, so a total reads "7 apples" rather than "7".
     *
     * Falls back to "item" for a tag and for a list of several ids, since neither
     * "#minecraft:planks" nor "diamond or minecraft:emerald" is something you can pluralise.
     */
    public String itemNoun() {
        if (label.startsWith("#") || label.contains(" or ")) {
            return "item";
        }
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
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

    public int requestedChunks() {
        return requestedChunks;
    }

    public int chunksSkipped() {
        return chunksSkipped;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    /** How many individual stacks were found. */
    public int stackCount() {
        return hits.size();
    }

    /** Total item count across every stack, which is the number people actually want. */
    public long totalItems() {
        return totalItems;
    }

    public long countFrom(ItemHit.Source source) {
        return countsBySource.getOrDefault(source, 0L);
    }

    /** Every hit, nearest first. Safe to call from a background thread once scanning ends. */
    public List<ItemHit> byDistance() {
        List<ItemHit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble(ItemHit::distance));
        return sorted;
    }

    /** Hits grouped by the place holding them, largest pile first. */
    public List<Pile> piles() {
        Map<String, Pile> grouped = new java.util.LinkedHashMap<>();
        for (ItemHit hit : hits) {
            String key = hit.pos().asLong() + "|" + hit.source() + "|" + hit.holder();
            grouped.computeIfAbsent(key, k -> new Pile(hit)).accept(hit);
        }
        List<Pile> list = new ArrayList<>(grouped.values());
        list.sort(Comparator.comparingLong(Pile::count).reversed()
                .thenComparingDouble(Pile::distance));
        return list;
    }

    /** One place holding one or more stacks of the searched item. */
    public static final class Pile {
        private final BlockPos pos;
        private final ItemHit.Source source;
        private final String holder;
        private double distance;
        private long count;
        private int stacks;
        private boolean anyEnchanted;
        private String customName;

        Pile(ItemHit first) {
            this.pos = first.pos();
            this.source = first.source();
            this.holder = first.holder();
            this.distance = first.distance();
        }

        void accept(ItemHit hit) {
            count += hit.count();
            stacks++;
            distance = Math.min(distance, hit.distance());
            anyEnchanted |= hit.enchanted();
            if (customName == null) {
                customName = hit.customName();
            }
        }

        public BlockPos pos() {
            return pos;
        }

        public ItemHit.Source source() {
            return source;
        }

        public String holder() {
            return holder;
        }

        public double distance() {
            return distance;
        }

        public long count() {
            return count;
        }

        public int stacks() {
            return stacks;
        }

        public boolean anyEnchanted() {
            return anyEnchanted;
        }

        public String customName() {
            return customName;
        }
    }
}
