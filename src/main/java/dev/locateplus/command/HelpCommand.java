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
import dev.locateplus.platform.Services;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /lp} and {@code /lp help}, an in-game index of everything this mod adds.
 *
 * <p>Seven commands with optional radius units and force-load flags is more than anyone will
 * remember from a README, so the reference lives in game. Every entry is clickable: clicking
 * suggests the command in the chat box with the cursor ready, rather than running it, so nothing
 * destructive fires by accident.</p>
 */
public final class HelpCommand {

    private HelpCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("lp")
                .requires(source -> source.hasPermissionLevel(LPConstants.PERMISSION_LEVEL))
                .executes(HelpCommand::help)
                .then(literal("help").executes(HelpCommand::help)));
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();

        source.sendFeedback(() -> Text.literal("")
                .append(Text.literal(LPConstants.MOD_NAME)
                        .formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal("  v" + Services.platform().modVersion())
                        .formatted(Formatting.DARK_GRAY)), false);
        source.sendFeedback(() -> Text.literal("Server-side scanning, inspection and safe teleport.")
                .formatted(Formatting.GRAY), false);
        blank(source);

        entry(source, "/locate block <id|#tag> [<n> chunks|blocks] [forceload]",
                "Nearest matching block, with a teleport button and a particle marker.",
                "/locate block minecraft:diamond_ore 128 blocks");

        entry(source, "/locate entity <id|#tag> [<n> chunks|blocks] [forceload]",
                "Nearest matching entity. Applies Glowing to the closest one.",
                "/locate entity minecraft:zombie 64 blocks");

        entry(source, "/locate biome|structure <id|#tag>",
                "Vanilla searches, upgraded with a safe-teleport button.",
                "/locate biome minecraft:jungle");

        entry(source, "/inspect <x> <y> <z> [forceload]",
                "Everything about one position: redstone, light, growth, containers, entities.",
                "/inspect ~ ~-1 ~");

        entry(source, "/safetp [targets] <destination>",
                "Teleport to a spot you can actually stand on, and be told where you landed.",
                "/safetp ~ ~ ~10");

        entry(source, "/glow <target> <n> chunks|blocks",
                "Outline entities through terrain for a minute. Ids, #tags and @ selectors.",
                "/glow @e[type=minecraft:zombie] 64 blocks");

        entry(source, "/analyzechunks blocks|entities|both <n> chunks|blocks [export] [forceload]",
                "Survey everything nearby. Add 'export' to write the full data to a file.",
                "/analyzechunks both 4 chunks export");

        entry(source, "/purgeentities <target> <n> chunks|blocks [export]",
                "Remove matching entities and log exactly what went. Never touches players.",
                "/purgeentities minecraft:item 64 blocks export");

        blank(source);
        source.sendFeedback(() -> Text.literal("Notes").formatted(Formatting.GOLD), false);
        note(source, "The radius unit is required: write \"64 blocks\" or \"4 chunks\" (plural).");
        note(source, "Both units are always shown back to you, so nothing is ambiguous.");
        note(source, "Without 'forceload', scans only read chunks already in memory.");
        note(source, "Exports land in config/locate-plus/exports/");
        note(source, "Everything needs permission level " + LPConstants.PERMISSION_LEVEL
                + " (OP, or cheats enabled).");
        blank(source);
        source.sendFeedback(() -> Text.literal("Click any command above to put it in your chat box.")
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
        return 1;
    }

    /** One command: clickable usage line plus a one-line description. */
    private static void entry(ServerCommandSource source, String usage, String description,
                              String example) {
        MutableText line = Text.literal("")
                .append(Text.literal("> ").formatted(Formatting.DARK_AQUA))
                .append(Text.literal(usage).styled(style -> style
                        .withColor(Formatting.WHITE)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND, example))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Click to try:\n")
                                        .formatted(Formatting.GRAY)
                                        .append(Text.literal(example)
                                                .formatted(Formatting.YELLOW))))));
        source.sendFeedback(() -> line, false);
        source.sendFeedback(() -> Text.literal("   " + description)
                .formatted(Formatting.GRAY), false);
    }

    private static void note(ServerCommandSource source, String text) {
        source.sendFeedback(() -> Text.literal("  - " + text)
                .formatted(Formatting.GRAY), false);
    }

    private static void blank(ServerCommandSource source) {
        source.sendFeedback(Text::empty, false);
    }
}
