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
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.locateplus.core.LPConstants;
import dev.locateplus.inspect.BlockInspector;
import dev.locateplus.inspect.ContainerInspector;
import dev.locateplus.inspect.EntityInspector;
import dev.locateplus.inspect.InspectLine;
import dev.locateplus.report.Chat;
import dev.locateplus.report.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * {@code /inspect <x> <y> <z>}, a full report on one block position.
 *
 * <p>Covers the block itself (state, redstone, light, spawning, growth), its block entity
 * (container contents, machine progress), and every entity standing there (equipment, effects,
 * villager data, pet ownership).</p>
 *
 * <p>When several entities of the same type share the position, they are grouped: the report shows
 * how many there are and expands only the single most informative one. Standing in a mob farm
 * would otherwise produce hundreds of near-identical lines.</p>
 *
 * <p>Never force-loads. Inspecting a coordinate in an unloaded chunk says so rather than quietly
 * generating terrain.</p>
 */
public final class InspectCommand {

    private InspectCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("inspect")
                .requires(source -> source.hasPermissionLevel(LPConstants.PERMISSION_LEVEL))
                .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> inspect(ctx, false))
                        .then(literal("forceload")
                                .executes(ctx -> inspect(ctx, true)))));
    }

    private static int inspect(CommandContext<ServerCommandSource> ctx, boolean forceload) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();

        BlockPos pos;
        try {
            pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
        } catch (Exception e) {
            Chat.error(source, "Invalid position.");
            return 0;
        }

        boolean wasLoaded = world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        if (!wasLoaded) {
            if (!forceload) {
                Chat.warn(source, "Chunk at " + Chat.coords(pos) + " is not loaded. "
                        + "Add 'forceload' to load it for this inspection.");
                return 0;
            }
            // Pull the chunk to FULL so block states, block entities and entities are all real.
            // A single chunk is cheap, and it is released again as soon as the report is built.
            Chat.warn(source, "Force-loading the chunk at " + Chat.coords(pos)
                    + ". This may generate terrain.");
            try {
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                        ChunkStatus.FULL, true);
            } catch (Throwable t) {
                Chat.error(source, "Could not load that chunk: " + t.getMessage());
                return 0;
            }
        }

        InspectLine out = new InspectLine();
        BlockState state = world.getBlockState(pos);

        // ---- block ----
        out.section("Block");
        out.field("Id", Registries.BLOCK.getId(state.getBlock()).toString());
        out.field("Dimension", world.getRegistryKey().getValue().toString());

        Map<Property<?>, Comparable<?>> properties = state.getEntries();
        if (!properties.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            properties.forEach((property, value) -> {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(property.getName()).append('=').append(value);
            });
            out.field("State", sb.toString());
        }
        if (!state.getFluidState().isEmpty()) {
            out.field("Fluid", Registries.FLUID.getId(state.getFluidState().getFluid()).toString());
        }
        float hardness = state.getHardness(world, pos);
        if (hardness >= 0) {
            out.field("Hardness", String.format("%.1f", hardness));
        } else {
            out.field("Hardness", "unbreakable", Formatting.YELLOW);
        }

        // ---- redstone, light, growth ----
        BlockInspector.redstone(out, world, pos, state);
        BlockInspector.lightAndSpawning(out, world, pos, state);
        BlockInspector.growth(out, state);

        // ---- block entity ----
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            Identifier beId = Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType());
            out.section("Block entity");
            out.field("Type", beId == null ? "unknown" : beId.toString());

            if (ContainerInspector.isContainer(blockEntity)) {
                ContainerInspector.contents(out, blockEntity);
            }
            ContainerInspector.machine(out, blockEntity);
        }

        // ---- entities ----
        appendEntities(out, world, pos);

        // ---- send ----
        Msg.heading(source, "Inspect " + Msg.coords(pos));
        for (Text line : out.lines()) {
            source.sendFeedback(() -> line, false);
        }
        if (!wasLoaded) {
            source.sendFeedback(() -> Text.literal(
                            "Chunk was force-loaded for this inspection; it may unload again.")
                    .formatted(Formatting.DARK_GRAY), false);
        }
        return 1;
    }

    /**
     * Group entities at this position by type.
     *
     * <p>For each type: report the count, then expand the single most informative individual --
     * see {@link #informationScore}. A hopper-fed mob farm with 200 zombies produces three lines,
     * not six hundred.</p>
     */
    private static void appendEntities(InspectLine out, ServerWorld world, BlockPos pos) {
        Box box = new Box(pos);
        List<Entity> found = new ArrayList<>();
        try {
            found.addAll(world.getOtherEntities(null, box, e -> true));
        } catch (Throwable t) {
            return;
        }

        // Players are tracked separately from chunk entity storage, so getOtherEntities never
        // returns them. Without this sweep, standing on the block you inspect shows a count but
        // no details for yourself.
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (box.intersects(player.getBoundingBox()) && !found.contains(player)) {
                found.add(player);
            }
        }

        out.section("Entities");
        if (found.isEmpty()) {
            out.field("Count", "0", Formatting.DARK_GRAY);
            return;
        }

        Map<Identifier, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity entity : found) {
            Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
            byType.computeIfAbsent(id, key -> new ArrayList<>()).add(entity);
        }

        out.field("Total", found.size() + " entit" + (found.size() == 1 ? "y" : "ies")
                + " in " + byType.size() + " type" + (byType.size() == 1 ? "" : "s"));

        for (Map.Entry<Identifier, List<Entity>> entry : byType.entrySet()) {
            List<Entity> group = entry.getValue();
            Entity best = group.stream()
                    .max(Comparator.comparingInt(InspectCommand::informationScore))
                    .orElse(group.get(0));

            out.section(entry.getKey() + (group.size() > 1 ? "  x" + group.size() : ""));
            if (group.size() > 1) {
                out.field("Count", String.valueOf(group.size()), Formatting.AQUA);
                out.field("Showing", "the most detailed of the " + group.size(),
                        Formatting.DARK_GRAY);
            }
            describeOne(out, best, world);
        }
    }

    /**
     * How interesting an entity is, used to pick which of a group to expand.
     *
     * <p>Weighted so that a named, geared, effect-laden mob always beats a plain one. Ties fall
     * back to whichever came first in the list, which is stable enough for a diagnostic command.</p>
     */
    private static int informationScore(Entity entity) {
        int score = 0;
        try {
            if (entity.hasCustomName()) {
                score += 100;
            }
            if (!entity.getCommandTags().isEmpty()) {
                score += 40;
            }
            if (entity.isInvulnerable()) {
                score += 10;
            }
            if (entity instanceof LivingEntity living) {
                score += living.getStatusEffects().size() * 25;
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!living.getEquippedStack(slot).isEmpty()) {
                        score += 20;
                    }
                }
                // A damaged mob is usually the one being investigated.
                if (living.getHealth() < living.getMaxHealth()) {
                    score += 15;
                }
            }
            if (entity instanceof VillagerEntity villager) {
                score += 50 + villager.getVillagerData().getLevel() * 5;
            }
            if (entity instanceof TameableEntity tameable
                    && tameable.isTamed()) {
                score += 60;
            }
            if (entity instanceof ItemEntity item) {
                score += item.getStack().getCount();
            }
        } catch (Throwable ignored) {
            // a misbehaving modded entity simply scores low
        }
        return score;
    }

    /** Core identity fields for one entity, followed by the detailed sections. */
    private static void describeOne(InspectLine out, Entity entity, ServerWorld world) {
        out.field("UUID", entity.getUuidAsString());
        out.field("Name", entity.getName().getString()
                + (entity.getCustomName() != null
                ? "  (\"" + entity.getCustomName().getString() + "\")" : ""));
        out.field("Position", String.format("%.3f %.3f %.3f",
                entity.getX(), entity.getY(), entity.getZ()));

        if (entity instanceof LivingEntity living) {
            out.field("Health", String.format("%.1f / %.1f",
                    living.getHealth(), living.getMaxHealth()),
                    living.getHealth() < living.getMaxHealth() / 2 ? Formatting.RED : Formatting.WHITE);
        }
        if (entity instanceof ItemEntity item) {
            var stack = item.getStack();
            out.field("Item", stack.getCount() + "x "
                    + Registries.ITEM.getId(stack.getItem()));
            if (item.getItemAge() > 0) {
                out.field("Age", item.getItemAge() + " ticks");
            }
        }
        if (entity.isGlowing()) {
            out.field("Glowing", "true", Formatting.AQUA);
        }

        EntityInspector.describe(out, entity, world);
    }
}
