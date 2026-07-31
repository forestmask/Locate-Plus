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
package dev.locateplus.inspect;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.Collection;
import java.util.Locale;

/**
 * Block-level {@code /inspect} sections: redstone, light and mob spawning, and growth stages.
 *
 * <p>Everything here reads state that is already in memory. Nothing is computed by simulation, so
 * a single inspect is effectively free even on a busy server.</p>
 */
public final class BlockInspector {

    private BlockInspector() {
    }

    // ---- redstone ------------------------------------------------------------------------------

    /**
     * Signal strength, powered status, and which way a component points.
     *
     * <p>Reports both received power (what this block is being given) and emitted power (what it
     * hands to its neighbours), because a repeater or comparator commonly differs on the two and
     * that difference is usually the thing being debugged.</p>
     */
    public static void redstone(InspectLine out, ServerWorld world, BlockPos pos, BlockState state) {
        InspectLine section = new InspectLine();

        int received = world.getReceivedRedstonePower(pos);
        boolean receiving = world.isReceivingRedstonePower(pos);

        if (received > 0 || receiving) {
            section.field("Receiving power", received + " / 15",
                    received > 0 ? Formatting.RED : Formatting.GRAY);
        }

        // Blocks that carry their own signal level: redstone wire, repeaters, comparators, etc.
        if (state.contains(Properties.POWER)) {
            int power = state.get(Properties.POWER);
            section.field("Signal strength", power + " / 15",
                    power > 0 ? Formatting.RED : Formatting.DARK_GRAY);
        }
        if (state.contains(Properties.POWERED)) {
            boolean powered = state.get(Properties.POWERED);
            section.field("Powered", String.valueOf(powered),
                    powered ? Formatting.RED : Formatting.DARK_GRAY);
        }
        if (state.contains(Properties.LIT)) {
            boolean lit = state.get(Properties.LIT);
            section.field("Lit / active", String.valueOf(lit),
                    lit ? Formatting.YELLOW : Formatting.DARK_GRAY);
        }

        // Emitted power, sampled on each face. Strong power passes through blocks, weak does not.
        if (state.emitsRedstonePower()) {
            StringBuilder weak = new StringBuilder();
            StringBuilder strong = new StringBuilder();
            for (Direction dir : Direction.values()) {
                int w = state.getWeakRedstonePower(world, pos, dir);
                int s = state.getStrongRedstonePower(world, pos, dir);
                if (w > 0) {
                    append(weak, dir.getName() + " " + w);
                }
                if (s > 0) {
                    append(strong, dir.getName() + " " + s);
                }
            }
            if (weak.length() > 0) {
                section.field("Emits (weak)", weak.toString(), Formatting.RED);
            }
            if (strong.length() > 0) {
                section.field("Emits (strong)", strong.toString(), Formatting.RED);
            }
        }

        // Which way the component points, and where its output lands.
        Direction facing = null;
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            facing = state.get(Properties.HORIZONTAL_FACING);
        } else if (state.contains(Properties.FACING)) {
            facing = state.get(Properties.FACING);
        }
        if (facing != null) {
            BlockPos target = pos.offset(facing);
            String targetId = net.minecraft.registry.Registries.BLOCK
                    .getId(world.getBlockState(target).getBlock()).toString();
            section.field("Facing", facing.getName()
                    + "  -> " + target.getX() + " " + target.getY() + " " + target.getZ()
                    + " (" + targetId + ")");
        }

        // Repeater delay and comparator mode are the two settings people forget to check.
        if (state.contains(Properties.DELAY)) {
            section.field("Repeater delay", state.get(Properties.DELAY) + " tick(s)");
        }
        if (state.contains(Properties.COMPARATOR_MODE)) {
            section.field("Comparator mode",
                    state.get(Properties.COMPARATOR_MODE).asString());
        }
        if (state.contains(Properties.LOCKED) && state.get(Properties.LOCKED)) {
            section.field("Locked", "true", Formatting.YELLOW);
        }

        if (!section.isEmpty()) {
            out.section("Redstone");
            out.lines().addAll(section.lines());
        }
    }

    private static void append(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(value);
    }

    // ---- light and spawning --------------------------------------------------------------------

    /**
     * Light levels and whether a hostile mob could spawn here.
     *
     * <p>The spawn check mirrors the vanilla rule set that players actually care about for mob
     * proofing: a solid top face below, two free blocks of room, and block light at or below the
     * hostile threshold. It deliberately does not attempt a full {@code SpawnHelper} simulation --
     * biome-specific and mob-specific rules would make the answer less useful, not more.</p>
     */
    public static void lightAndSpawning(InspectLine out, ServerWorld world, BlockPos pos, BlockState state) {
        out.section("Light & spawning");

        int blockLight = world.getLightLevel(LightType.BLOCK, pos);
        int skyLight = world.getLightLevel(LightType.SKY, pos);
        int emitted = state.getLuminance();

        out.field("Block light", blockLight + " / 15");
        out.field("Sky light", skyLight + " / 15");
        if (emitted > 0) {
            out.field("Emits light", emitted + " / 15", Formatting.YELLOW);
        }

        BlockPos below = pos.down();
        BlockState floor = world.getBlockState(below);

        boolean floorSolid = floor.isSideSolidFullSquare(world, below, Direction.UP);
        boolean roomHere = state.getCollisionShape(world, pos).isEmpty();
        boolean roomAbove = world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
        boolean floorBlocked = floor.isIn(BlockTags.LEAVES)
                || floor.isOf(Blocks.BEDROCK)
                || floor.isOf(Blocks.BARRIER);
        boolean darkEnough = blockLight <= 0;

        boolean canSpawn = floorSolid && roomHere && roomAbove && !floorBlocked && darkEnough;

        out.field("Hostile mobs can spawn", canSpawn ? "YES" : "no",
                canSpawn ? Formatting.RED : Formatting.GREEN);

        if (!canSpawn) {
            String reason;
            if (!floorSolid) {
                reason = "no solid block below";
            } else if (floorBlocked) {
                reason = "floor material blocks spawning";
            } else if (!roomHere || !roomAbove) {
                reason = "not enough headroom (needs 2 free blocks)";
            } else {
                reason = "block light is " + blockLight + " (needs 0)";
            }
            out.field("  reason", reason, Formatting.DARK_GRAY);
        }

        // Passive animals use a different rule: grass, daylight, and no light requirement.
        if (floor.isOf(Blocks.GRASS_BLOCK) && roomHere && skyLight >= 9) {
            out.field("Passive animals can spawn", "YES (grass, sky light "
                    + skyLight + ")", Formatting.YELLOW);
        }
    }

    // ---- growth --------------------------------------------------------------------------------

    /**
     * Growth stage for crops, stems, saplings and anything else with an {@code age} property.
     *
     * <p>Discovered generically from the block state rather than from a hardcoded block list, so
     * modded crops report correctly too.</p>
     */
    public static void growth(InspectLine out, BlockState state) {
        IntProperty age = null;
        for (Property<?> property : state.getEntries().keySet()) {
            if (property instanceof IntProperty intProperty && "age".equals(intProperty.getName())) {
                age = intProperty;
                break;
            }
        }
        if (age == null) {
            // Sapling growth uses "stage"; bamboo and a few others do too.
            for (Property<?> property : state.getEntries().keySet()) {
                if (property instanceof IntProperty intProperty && "stage".equals(intProperty.getName())) {
                    out.section("Growth");
                    Collection<Integer> values = intProperty.getValues();
                    int max = values.stream().mapToInt(Integer::intValue).max().orElse(0);
                    int current = state.get(intProperty);
                    out.field("Stage", current + " / " + max);
                    out.field("Status", current >= max ? "ready to grow" : "growing",
                            current >= max ? Formatting.GREEN : Formatting.YELLOW);
                    return;
                }
            }
            return;
        }

        out.section("Growth");
        Collection<Integer> values = age.getValues();
        int max = values.stream().mapToInt(Integer::intValue).max().orElse(0);
        int current = state.get(age);
        boolean mature = current >= max;

        out.field("Age", current + " / " + max);
        out.field("Progress", String.format(Locale.US, "%.0f%%",
                max == 0 ? 100.0 : (current * 100.0 / max)));
        out.field("Status", mature ? "FULLY GROWN" : "still growing",
                mature ? Formatting.GREEN : Formatting.YELLOW);
    }
}
