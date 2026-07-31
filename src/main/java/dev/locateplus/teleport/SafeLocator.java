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

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;

/**
 * Finds somewhere a player can stand without dying.
 *
 * <p>Search order matches the guide:</p>
 * <ol>
 *   <li>Scan the full vertical Y column at the requested X/Z, picking the candidate whose Y is
 *       closest to the requested Y.</li>
 *   <li>If that column has nothing, spiral outward ring by ring.</li>
 * </ol>
 *
 * <h2>What counts as "safe"</h2>
 *
 * <p>A floor needs to present <em>some</em> top surface to stand on, not a full cube: slabs,
 * stairs, dirt paths, snow layers and carpets all qualify. Requiring a full-height block would
 * reject most cave floors and send the player to the surface fallback instead of the spot they
 * asked for.</p>
 *
 * <p>The landing Y comes from the floor's actual collision shape, so a player lands on top of a
 * slab rather than inside it. Shallow water at foot level is fine (you wade, you do not drown);
 * water over the head is not.</p>
 */
public final class SafeLocator {

    /** How far sideways to look when the requested column is unusable. */
    private static final int MAX_HORIZONTAL_SEARCH = 24;

    /**
     * Minimum top-surface height for a block to count as a floor.
     * A bottom slab is 0.5, a snow layer is 0.125; both are fine to stand on.
     */
    private static final double MIN_FLOOR_HEIGHT = 0.1;

    private SafeLocator() {
    }

    /**
     * @return a safe spot, or {@code null} when nothing suitable exists nearby
     */
    public static SafeSpot find(ServerWorld world, BlockPos requested) {
        SafeSpot inColumn = searchColumn(world, requested.getX(), requested.getZ(), requested, true);
        if (inColumn != null) {
            return inColumn;
        }

        // Spiral outward. Ring order means the first hit is (near enough) the closest.
        for (int radius = 1; radius <= MAX_HORIZONTAL_SEARCH; radius++) {
            SafeSpot best = null;
            double bestScore = Double.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue; // perimeter only
                    }
                    SafeSpot candidate = searchColumn(world,
                            requested.getX() + dx, requested.getZ() + dz, requested, true);
                    if (candidate == null) {
                        continue;
                    }
                    // Prefer small vertical drift over small horizontal drift: dropping 40 blocks
                    // into a cave is a worse outcome than stepping 2 blocks sideways.
                    double score = candidate.horizontalOffset()
                            + Math.abs(candidate.verticalOffset()) * 1.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }

        // Absolute last resort: the surface directly above the request. Only reached when the
        // entire neighbourhood is unusable, and never in preference to a nearby safe spot.
        return surfaceFallback(world, requested);
    }

    /**
     * Best safe Y in one X/Z column, nearest to {@code requested.getY()}.
     * Skips columns in unloaded chunks so a search never triggers hidden generation.
     */
    private static SafeSpot searchColumn(ServerWorld world, int x, int z,
                                         BlockPos requested, boolean columnOnly) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        int bottom = world.getBottomY();
        int top = world.getTopY();
        int wanted = Math.max(bottom + 1, Math.min(requested.getY(), top - 1));

        int maxDistance = Math.max(wanted - bottom, top - wanted);
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        // Walk outward from the requested Y so the first hit is automatically the nearest.
        for (int d = 0; d <= maxDistance; d++) {
            int up = wanted + d;
            if (up < top) {
                Double landing = standingHeight(world, cursor.set(x, up, z));
                if (landing != null) {
                    return new SafeSpot(new Vec3d(x + 0.5, landing, z + 0.5), requested);
                }
            }
            if (d == 0) {
                continue;
            }
            int down = wanted - d;
            if (down > bottom) {
                Double landing = standingHeight(world, cursor.set(x, down, z));
                if (landing != null) {
                    return new SafeSpot(new Vec3d(x + 0.5, landing, z + 0.5), requested);
                }
            }
        }
        return null;
    }

    /** Surface of the requested column, used only when everything else failed. */
    private static SafeSpot surfaceFallback(ServerWorld world, BlockPos requested) {
        int x = requested.getX();
        int z = requested.getZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (surface <= world.getBottomY() || surface >= world.getTopY()) {
            return null;
        }
        Double landing = standingHeight(world, new BlockPos(x, surface, z));
        if (landing == null) {
            return null;
        }
        return new SafeSpot(new Vec3d(x + 0.5, landing, z + 0.5), requested);
    }

    /**
     * Exact Y a player's feet would rest at when standing in {@code feet}, or {@code null} if the
     * position is not safe.
     *
     * <p>Returning the height rather than a boolean is what lets a player land cleanly on top of a
     * slab or stair instead of being placed inside it.</p>
     */
    public static Double standingHeight(ServerWorld world, BlockPos feet) {
        if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return null;
        }
        BlockPos head = feet.up();
        BlockPos ground = feet.down();
        if (ground.getY() < world.getBottomY() || head.getY() >= world.getTopY()) {
            return null;
        }

        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        BlockState groundState = world.getBlockState(ground);

        // Body space must be clear of anything you would suffocate inside.
        if (!isPassable(world, feet, feetState) || !isPassable(world, head, headState)) {
            return null;
        }
        if (isHarmful(feetState) || isHarmful(headState) || isHarmful(groundState)) {
            return null;
        }

        // Wading through shallow water is fine; being fully submerged is not.
        if (!headState.getFluidState().isEmpty()) {
            return null;
        }
        if (feetState.getFluidState().isIn(FluidTags.LAVA)) {
            return null;
        }

        // The floor must offer a surface to stand on, but need not be a full cube.
        if (groundState.isAir()) {
            return null;
        }
        VoxelShape groundShape = groundState.getCollisionShape(world, ground);
        if (groundShape.isEmpty()) {
            return null;
        }
        double surface = groundShape.getMax(Direction.Axis.Y);
        if (surface < MIN_FLOOR_HEIGHT) {
            return null;
        }

        // Feet rest on top of whatever the floor block actually presents.
        return ground.getY() + surface;
    }

    /** Kept for callers that only need a yes/no answer. */
    public static boolean isSafeStand(ServerWorld world, BlockPos feet) {
        return standingHeight(world, feet) != null;
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        // Anything with no collision box, grass, torches, signs, water, is fine to occupy.
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isHarmful(BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return true;
        }
        return state.isIn(BlockTags.FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.END_PORTAL)
                || state.isOf(Blocks.NETHER_PORTAL)
                || state.isOf(Blocks.LAVA_CAULDRON);
    }
}
