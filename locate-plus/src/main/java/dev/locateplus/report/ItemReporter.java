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
package dev.locateplus.report;

import dev.locateplus.core.LPConfig;
import dev.locateplus.model.ItemHit;
import dev.locateplus.model.ItemResult;
import dev.locateplus.teleport.TeleportService;
import dev.locateplus.util.Radius;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Turns an {@link ItemResult} into the chat report a player reads. */
public final class ItemReporter {

    private ItemReporter() {
    }

    public static void report(ServerCommandSource source, ItemResult result, Radius radius,
                              boolean exported) {
        if (result.isEmpty()) {
            Msg.warn(source, "No " + result.label() + " within " + describe(radius) + ".");
            suggestForceload(source, result);
            suggestScopes(source);
            return;
        }

        List<ItemResult.Pile> piles = result.piles();

        // One line for the whole answer. A stack count sat here as well, which read oddly at
        // small numbers, "11 apples in 9 stacks", and told nobody anything they wanted: a stack
        // means 64 to a player, so counting part-full ones as stacks invites the wrong sum. The
        // places figure is the useful second number, since it says how far the search is spread.
        Msg.heading(source, "Found " + result.label());
        Msg.field(source, "Total", Msg.count(result.totalItems(), result.itemNoun())
                + " across " + Msg.count(piles.size(), "place")
                + ", within " + describe(radius));
        breakdown(source, result);
        scanScope(source, result);

        int shown = Math.min(LPConfig.get().chatTopN(), piles.size());
        Msg.blank(source);

        for (int i = 0; i < shown; i++) {
            ItemResult.Pile pile = piles.get(i);
            int rank = i + 1;

            source.sendFeedback(() -> pileLine(rank, pile)
                    .append(Text.literal(" "))
                    .append(TeleportService.teleportButton(pile.pos())), false);
        }

        if (piles.size() > shown) {
            Msg.more(source, piles.size() - shown, "locations");
        }

        if (result.truncated()) {
            Msg.blank(source);
            Msg.warn(source, "Very large result; some locations were left out.");
        }
        Msg.blank(source);
        if (exported) {
            Msg.note(source, "Full list written to " + Msg.exportPath());
        } else {
            Msg.note(source, "Add 'export' to write every location to a file.");
        }
        Msg.note(source, "Took " + Msg.duration(result.durationMillis()) + ".");
    }

    /**
     * One result line: how many, where it is, and how far.
     *
     * The count leads because the list is ordered by it, and the distance closes the line so a
     * column of results can be read down to find the nearest worthwhile pile. The holder is
     * coloured by kind, since "a player has your diamonds" is a different finding from "a chest
     * has your diamonds".
     */
    private static MutableText pileLine(int rank, ItemResult.Pile pile) {
        MutableText line = Text.literal("  ")
                .append(Text.literal(rank + ". ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(Msg.number(pile.count()) + "x").formatted(Formatting.AQUA))
                .append(Text.literal(preposition(pile.source())).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(pile.holder()).formatted(colourFor(pile.source())));

        // How many slots the pile occupies. Worth saying only when it is more than one, where it
        // is the difference between one full chest slot and a chest with the item dotted about.
        if (pile.stacks() > 1) {
            line.append(Text.literal(" over " + pile.stacks() + " slots")
                    .formatted(Formatting.DARK_GRAY));
        }
        if (pile.customName() != null) {
            line.append(Text.literal(" \"" + pile.customName() + "\"")
                    .formatted(Formatting.YELLOW));
        }
        if (pile.anyEnchanted()) {
            line.append(Text.literal(" enchanted").formatted(Formatting.LIGHT_PURPLE));
        }
        line.append(Text.literal("  " + Math.round(pile.distance()) + "m ")
                .formatted(Formatting.DARK_GRAY));
        return line;
    }

    /**
     * The preposition that reads correctly for each kind of holder.
     *
     * Items are <em>in</em> a chest but <em>on</em> the ground and <em>on</em> a mob, so a single
     * hardcoded "in" produces "96 in on the ground".
     */
    private static String preposition(ItemHit.Source source) {
        return switch (source) {
            case DROPPED, ENTITY -> " on ";
            case CONTAINER, NESTED, PLAYER -> " in ";
        };
    }

    private static Formatting colourFor(ItemHit.Source source) {
        return switch (source) {
            case PLAYER -> Formatting.GOLD;
            case DROPPED -> Formatting.GREEN;
            case ENTITY -> Formatting.YELLOW;
            case NESTED -> Formatting.AQUA;
            case CONTAINER -> Formatting.WHITE;
        };
    }

    /** Where the items were, when they were in more than one kind of place. */
    private static void breakdown(ServerCommandSource source, ItemResult result) {
        StringBuilder parts = new StringBuilder();
        int kinds = 0;
        for (ItemHit.Source kind : ItemHit.Source.values()) {
            long count = result.countFrom(kind);
            if (count == 0) {
                continue;
            }
            if (kinds > 0) {
                parts.append(", ");
            }
            parts.append(Msg.number(count)).append(' ').append(kind.phrase());
            kinds++;
        }
        if (kinds > 1) {
            Msg.field(source, "Split", parts.toString());
        }
    }

    private static void scanScope(ServerCommandSource source, ItemResult result) {
        String scanned = Msg.count(result.chunksScanned(), "chunk");
        int requested = result.requestedChunks();
        if (requested > 0 && result.chunksScanned() != requested) {
            scanned += " of " + Msg.number(requested) + " requested";
        }
        Msg.field(source, "Scanned", scanned);
        if (result.chunksSkipped() > 0) {
            Msg.field(source, "Skipped", Msg.count(result.chunksSkipped(), "unloaded chunk"),
                    Formatting.YELLOW);
        }
    }

    private static void suggestForceload(ServerCommandSource source, ItemResult result) {
        if (!result.forceload() && result.chunksSkipped() > 0) {
            Msg.note(source, Msg.count(result.chunksSkipped(), "chunk")
                    + " in range were not loaded. Add 'forceload' to include them.");
        }
    }

    /** An empty result can also mean the config switched off the place the item is actually in. */
    private static void suggestScopes(ServerCommandSource source) {
        LPConfig config = LPConfig.get();
        StringBuilder off = new StringBuilder();
        if (!config.searchContainers()) {
            off.append("containers");
        }
        if (!config.searchDroppedItems()) {
            append(off, "dropped items");
        }
        if (!config.searchEntityInventories()) {
            append(off, "mobs");
        }
        if (!config.searchPlayerInventories()) {
            append(off, "players");
        }
        if (off.length() > 0) {
            Msg.note(source, "Not searched, switched off in the config: " + off);
        }
    }

    private static void append(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(text);
    }

    private static String describe(Radius radius) {
        return Msg.area(radius);
    }
}
