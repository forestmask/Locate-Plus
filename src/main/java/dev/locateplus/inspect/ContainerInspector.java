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

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Container and machine {@code /inspect} sections.
 *
 * <p>Summarises what is inside a chest, hopper, shulker box or furnace without dumping raw NBT.
 * Works for modded blocks too: anything implementing {@link Inventory} is summarised generically,
 * and machine progress falls back to reading well-known NBT keys when the block entity is not a
 * vanilla furnace.</p>
 */
public final class ContainerInspector {

    /** Items whose presence is worth calling out explicitly in a summary. */
    private static final List<String> VALUABLE = List.of(
            "minecraft:diamond", "minecraft:diamond_block", "minecraft:netherite_ingot",
            "minecraft:netherite_block", "minecraft:netherite_scrap", "minecraft:ancient_debris",
            "minecraft:emerald", "minecraft:emerald_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:enchanted_golden_apple", "minecraft:elytra",
            "minecraft:nether_star", "minecraft:beacon", "minecraft:totem_of_undying",
            "minecraft:dragon_egg", "minecraft:enchanted_book", "minecraft:shulker_box");

    private ContainerInspector() {
    }

    /** True when this block entity has an inventory worth summarising. */
    public static boolean isContainer(BlockEntity be) {
        return be instanceof Inventory;
    }

    /**
     * Item counts, distinct types, fill level and any valuables.
     *
     * <p>Stacks are grouped by item id so a chest of 27 stacks of cobblestone reads as one line,
     * which is the whole point of a summary.</p>
     */
    public static void contents(InspectLine out, BlockEntity be) {
        if (!(be instanceof Inventory inv)) {
            return;
        }

        int slots = inv.size();
        int usedSlots = 0;
        long totalItems = 0;
        Map<Identifier, Long> counts = new LinkedHashMap<>();
        List<String> named = new ArrayList<>();
        List<String> enchanted = new ArrayList<>();

        for (int i = 0; i < slots; i++) {
            ItemStack stack;
            try {
                stack = inv.getStack(i);
            } catch (Throwable t) {
                continue; // a modded inventory with an odd slot count must not abort the report
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            usedSlots++;
            totalItems += stack.getCount();
            Identifier id = Registries.ITEM.getId(stack.getItem());
            counts.merge(id, (long) stack.getCount(), Long::sum);

            if (stack.hasCustomName()) {
                named.add(stack.getName().getString() + " (" + id + ")");
            }
            if (stack.hasEnchantments()) {
                enchanted.add(id.getPath());
            }
        }

        out.section("Container");
        if (be instanceof net.minecraft.block.entity.LockableContainerBlockEntity lockable) {
            try {
                if (lockable.getCustomName() != null) {
                    out.field("Name", lockable.getCustomName().getString());
                }
            } catch (Throwable ignored) {
                // custom name is optional
            }
        }

        out.field("Slots used", usedSlots + " / " + slots
                + String.format(Locale.US, "  (%.0f%% full)",
                slots == 0 ? 0.0 : usedSlots * 100.0 / slots));
        out.field("Total items", String.valueOf(totalItems));
        out.field("Distinct types", String.valueOf(counts.size()));

        if (counts.isEmpty()) {
            out.field("Contents", "empty", Formatting.DARK_GRAY);
            return;
        }

        // Biggest stacks first, that is what people scan for.
        List<Map.Entry<Identifier, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Identifier, Long>comparingByValue().reversed());

        int shown = Math.min(10, sorted.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<Identifier, Long> entry = sorted.get(i);
            boolean valuable = VALUABLE.contains(entry.getKey().toString());
            out.item(entry.getValue() + "x " + entry.getKey(),
                    valuable ? Formatting.AQUA : Formatting.WHITE);
        }
        if (sorted.size() > shown) {
            out.item("... and " + (sorted.size() - shown) + " more types", Formatting.DARK_GRAY);
        }

        List<String> valuablesFound = sorted.stream()
                .map(e -> e.getKey().toString())
                .filter(VALUABLE::contains)
                .toList();
        if (!valuablesFound.isEmpty()) {
            out.field("Valuables", String.join(", ", valuablesFound), Formatting.AQUA);
        }
        if (!enchanted.isEmpty()) {
            out.field("Enchanted items", String.valueOf(enchanted.size()), Formatting.LIGHT_PURPLE);
        }
        if (!named.isEmpty()) {
            out.field("Renamed items", String.join(", ",
                    named.subList(0, Math.min(3, named.size()))));
        }
    }

    /**
     * Cook progress, fuel and burn time.
     *
     * <p>Vanilla furnaces expose these through their NBT ({@code BurnTime}, {@code CookTime},
     * {@code CookTimeTotal}); reading the NBT rather than the fields means the same code also
     * reports progress for modded machines that follow the same convention.</p>
     */
    public static void machine(InspectLine out, BlockEntity be) {
        NbtCompound nbt;
        try {
            nbt = be.createNbt();
        } catch (Throwable t) {
            return;
        }

        boolean isFurnace = be instanceof AbstractFurnaceBlockEntity;
        boolean hasProgress = nbt.contains("CookTime") || nbt.contains("BurnTime")
                || nbt.contains("progress") || nbt.contains("Progress");
        if (!isFurnace && !hasProgress && !(be instanceof BrewingStandBlockEntity)) {
            return;
        }

        InspectLine section = new InspectLine();

        int burnTime = nbt.getInt("BurnTime");
        int cookTime = nbt.getInt("CookTime");
        int cookTotal = nbt.getInt("CookTimeTotal");

        if (cookTotal > 0) {
            section.field("Cook progress", cookTime + " / " + cookTotal
                    + String.format(Locale.US, "  (%.0f%%)", cookTime * 100.0 / cookTotal));
        }
        if (burnTime > 0) {
            section.field("Fuel remaining", burnTime + " ticks"
                    + String.format(Locale.US, "  (%.1f s)", burnTime / 20.0), Formatting.GOLD);
            section.field("Status", "BURNING", Formatting.GOLD);
        } else if (isFurnace) {
            section.field("Status", "not burning", Formatting.DARK_GRAY);
        }

        // Brewing stands use their own keys.
        if (nbt.contains("BrewTime")) {
            int brew = nbt.getInt("BrewTime");
            section.field("Brew time", brew + " ticks"
                    + String.format(Locale.US, "  (%.1f s left)", brew / 20.0));
            if (nbt.contains("Fuel")) {
                section.field("Blaze powder", String.valueOf(nbt.getInt("Fuel")));
            }
        }

        // Generic modded progress keys.
        for (String key : new String[]{"progress", "Progress", "energy", "Energy"}) {
            if (nbt.contains(key)) {
                section.field(key, String.valueOf(nbt.getInt(key)));
            }
        }

        if (!section.isEmpty()) {
            out.section("Machine");
            out.lines().addAll(section.lines());
        }
    }
}
