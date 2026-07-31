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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.locateplus.util.Radius;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Builds the shared {@code <number> chunks|blocks [forceload]} tail.
 *
 * <p>Every scanning command uses this, so they cannot drift apart in what they accept.</p>
 *
 * <h2>Only the plural spellings exist</h2>
 *
 * <p>Singular {@code block} and {@code chunk} were removed deliberately. Beyond being redundant,
 * they collided with the mode literals of {@code /analyzechunks blocks|entities|both}: with
 * {@code blocks} meaning both "scan blocks" and "the unit is blocks", Brigadier had two valid
 * parses of {@code /analyzechunks blocks 4 blocks} and picked the wrong one.</p>
 *
 * <h2>No upper limit</h2>
 *
 * <p>Radius is deliberately unbounded. Clamping silently is confusing and refusing outright is
 * patronising, so a large scan warns about the cost and then runs. What a machine can handle is
 * the operator's judgement, not the mod's.</p>
 */
public final class RadiusArg {

    private static final DynamicCommandExceptionType TOO_SMALL = new DynamicCommandExceptionType(
            value -> Text.literal("Radius " + value + " is too small; use 1 or more."));

    /** What a command does once its radius and forceload flag are known. */
    @FunctionalInterface
    public interface Action {
        int run(CommandContext<ServerCommandSource> ctx, Radius radius, boolean forceload)
                throws CommandSyntaxException;
    }

    private RadiusArg() {
    }

    /**
     * Attach the radius tail to {@code parent}.
     *
     * @param supportsForceload whether a trailing {@code forceload} literal should be offered
     */
    public static <T extends ArgumentBuilder<ServerCommandSource, T>> T attach(
            T parent, boolean supportsForceload, Action action) {

        RequiredArgumentBuilder<ServerCommandSource, Integer> number =
                argument("radius", IntegerArgumentType.integer(0, Radius.maxBlocks()));

        addUnit(number, "chunks", false, supportsForceload, action);
        addUnit(number, "blocks", true, supportsForceload, action);

        return parent.then(number);
    }

    private static void addUnit(RequiredArgumentBuilder<ServerCommandSource, Integer> number,
                                String word, boolean isBlocks, boolean supportsForceload,
                                Action action) {
        var unit = literal(word)
                .executes(ctx -> action.run(ctx, read(ctx, isBlocks), false));

        if (supportsForceload) {
            unit.then(literal("forceload")
                    .executes(ctx -> action.run(ctx, read(ctx, isBlocks), true)));
        }
        number.then(unit);
    }

    /**
     * Read the typed number for the chosen unit.
     *
     * <p>There is no upper limit beyond the sanity ceiling on the argument itself. Scanning a
     * hundred chunks is the operator's call; the mod warns about the cost and gets on with it.
     * The only rejection left is a block radius of zero, which cannot mean anything.</p>
     */
    public static Radius read(CommandContext<ServerCommandSource> ctx, boolean isBlocks)
            throws CommandSyntaxException {
        int value = IntegerArgumentType.getInteger(ctx, "radius");

        if (isBlocks) {
            if (value < 1) {
                throw TOO_SMALL.create(value);
            }
            return Radius.ofBlocks(value);
        }
        // A scan always covers at least the chunk the player is standing in, so treat 0 as 1
        // rather than announcing "0 chunks" and then scanning one.
        return Radius.ofChunks(Math.max(1, value));
    }
}
