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
package dev.locateplus.teleport;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A safe landing position plus how far it drifted from what was requested.
 *
 * <p>The offset fields drive the guide's "you were placed 12 blocks above the target" feedback.</p>
 */
public final class SafeSpot {

    private final Vec3d position;
    private final BlockPos requested;
    private final int verticalOffset;
    private final int horizontalOffset;

    public SafeSpot(Vec3d position, BlockPos requested) {
        this.position = position;
        this.requested = requested;
        this.verticalOffset = BlockPos.ofFloored(position).getY() - requested.getY();
        BlockPos landed = BlockPos.ofFloored(position);
        int dx = landed.getX() - requested.getX();
        int dz = landed.getZ() - requested.getZ();
        this.horizontalOffset = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /** Exact position to teleport to: block centre on X/Z, feet on the floor. */
    public Vec3d position() {
        return position;
    }

    public BlockPos blockPos() {
        return BlockPos.ofFloored(position);
    }

    public BlockPos requested() {
        return requested;
    }

    /** Positive when the safe spot is above what was asked for. */
    public int verticalOffset() {
        return verticalOffset;
    }

    /** Always >= 0. Non-zero means the column had no safe spot and we searched sideways. */
    public int horizontalOffset() {
        return horizontalOffset;
    }

    public boolean isExact() {
        return verticalOffset == 0 && horizontalOffset == 0;
    }

    /**
     * One-line landing summary, always stating the relationship to the requested spot.
     *
     * <p>Used for every teleport confirmation, including the chat buttons in
     * {@code /analyzechunks} and {@code /locate} output. The relationship is always stated, even
     * when the landing is exact, so a player clicking a teleport button always knows whether they
     * ended up above or below the block they clicked.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "12 -60 12 (exactly at the target)"}</li>
     *   <li>{@code "12 -56 12 (4 blocks above the target)"}</li>
     *   <li>{@code "9 -60 14 (3 blocks away horizontally and 2 blocks below the target)"}</li>
     * </ul>
     */
    public String describeLanding() {
        String where = blockPos().getX() + " " + blockPos().getY() + " " + blockPos().getZ();
        if (isExact()) {
            return where + " (exactly at the target)";
        }
        return where + " (" + describeOffset() + ")";
    }

    /**
     * Human readable drift description, e.g. {@code "12 blocks above the target"} or
     * {@code "3 blocks away horizontally and 5 blocks below the target"}.
     */
    public String describeOffset() {
        if (isExact()) {
            return "exactly at the requested position";
        }

        StringBuilder sb = new StringBuilder();
        if (horizontalOffset > 0) {
            sb.append(horizontalOffset).append(horizontalOffset == 1 ? " block" : " blocks")
                    .append(" away horizontally");
        }
        if (verticalOffset != 0) {
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            int abs = Math.abs(verticalOffset);
            sb.append(abs).append(abs == 1 ? " block " : " blocks ")
                    .append(verticalOffset > 0 ? "above" : "below");
        }
        sb.append(" the target");
        return sb.toString();
    }
}
