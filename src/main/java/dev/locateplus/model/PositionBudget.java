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

import dev.locateplus.core.LPConstants;

/**
 * Shared cap on how many individual coordinates one export may retain.
 *
 * <p>The limit has to be global rather than per block type. A 32-chunk-radius export can touch
 * hundreds of block types; a per-type cap of two million would still allow hundreds of millions of
 * retained longs and take the server down with it. One budget shared by every tally in a scan keeps
 * peak memory bounded no matter how the matches are distributed.</p>
 */
public final class PositionBudget {

    private final long limit;
    private long used;
    private boolean exhausted;

    public PositionBudget() {
        this(LPConstants.MAX_EXPORT_POSITIONS);
    }

    public PositionBudget(long limit) {
        this.limit = limit;
    }

    /**
     * Reserve room for one more coordinate.
     *
     * @return {@code false} once the budget is spent, after which callers stop storing positions
     *         but keep counting
     */
    public boolean claim() {
        if (used >= limit) {
            exhausted = true;
            return false;
        }
        used++;
        return true;
    }

    public boolean exhausted() {
        return exhausted;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }
}
