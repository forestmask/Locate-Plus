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
package dev.locateplus.scan;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.function.Predicate;

/** Resolves the {@code <item_id|#tag>} argument of {@code /locate item} into a stack predicate. */
public final class ItemMatcher {

    private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown item '" + id + "'. Use an id like minecraft:diamond "
                    + "or a tag like #minecraft:swords."));

    private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown item tag '#" + id + "'."));

    private final String label;
    private final Predicate<ItemStack> predicate;

    private ItemMatcher(String label, Predicate<ItemStack> predicate) {
        this.label = label;
        this.predicate = predicate;
    }

    public String label() {
        return label;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return predicate.test(stack);
        } catch (Throwable t) {
            // A modded item with an unusual implementation must not abort a whole scan.
            return false;
        }
    }

    /** Matches exactly one item, built from a vanilla registry lookup. */
    public static ItemMatcher ofItem(String label, Item item) {
        return new ItemMatcher(label, stack -> stack.isOf(item));
    }

    /** Matches any item in a tag, built from a vanilla registry lookup. */
    public static ItemMatcher ofTag(TagKey<Item> tag) {
        return new ItemMatcher("#" + tag.id(), stack -> stack.isIn(tag));
    }


    /**
     * Parse a raw {@code id} or {@code #tag} string.
     *
     * Kept for callers that hold text rather than a resolved registry predicate, and so the
     * validation rules live in exactly one place.
     */
    public static ItemMatcher parse(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw UNKNOWN_ITEM.create("");
        }

        if (text.charAt(0) == '#') {
            String idText = text.substring(1);
            Identifier id = Identifier.tryParse(idText);
            if (id == null) {
                throw UNKNOWN_TAG.create(idText);
            }
            TagKey<Item> tag = TagKey.of(RegistryKeys.ITEM, id);
            if (Registries.ITEM.getEntryList(tag).isEmpty()) {
                throw UNKNOWN_TAG.create(idText);
            }
            return ofTag(tag);
        }

        Identifier id = Identifier.tryParse(text);
        if (id == null || !Registries.ITEM.containsId(id)) {
            throw UNKNOWN_ITEM.create(text);
        }
        Item item = Registries.ITEM.get(id);
        return ofItem(id.toString(), item);
    }
}
