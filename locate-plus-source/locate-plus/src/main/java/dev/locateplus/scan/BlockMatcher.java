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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Resolves the {@code <block_id|#tag>} argument into a state predicate.
 *
 * <p>Like the entity resolver, this avoids the defaulted registry trap: {@code Registries.BLOCK}
 * returns {@code minecraft:air} for unknown ids, so a typo would otherwise scan for air and report
 * a nonsense result instead of an error.</p>
 */
public final class BlockMatcher {

    private static final DynamicCommandExceptionType UNKNOWN_BLOCK = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown block '" + id + "'. Use an id like minecraft:diamond_ore "
                    + "or a tag like #minecraft:logs."));

    private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown block tag '#" + id + "'."));

    private final String label;
    private final Predicate<BlockState> predicate;

    private BlockMatcher(String label, Predicate<BlockState> predicate) {
        this.label = label;
        this.predicate = predicate;
    }

    public String label() {
        return label;
    }

    public boolean matches(BlockState state) {
        try {
            return predicate.test(state);
        } catch (Throwable t) {
            return false;
        }
    }

    public static BlockMatcher parse(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw UNKNOWN_BLOCK.create("");
        }

        if (text.charAt(0) == '#') {
            String idText = text.substring(1);
            Identifier id = Identifier.tryParse(idText);
            if (id == null) {
                throw UNKNOWN_TAG.create(idText);
            }
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
            if (Registries.BLOCK.getEntryList(tag).isEmpty()) {
                throw UNKNOWN_TAG.create(idText);
            }
            return new BlockMatcher("#" + id, state -> state.isIn(tag));
        }

        Identifier id = Identifier.tryParse(text);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            throw UNKNOWN_BLOCK.create(text);
        }
        Optional<Block> block = Registries.BLOCK.getOrEmpty(id);
        if (block.isEmpty()) {
            throw UNKNOWN_BLOCK.create(text);
        }
        Block target = block.get();
        return new BlockMatcher(id.toString(), state -> state.isOf(target));
    }

    /** Matches exactly one block, built from a vanilla registry lookup. */
    public static BlockMatcher ofBlock(String label, Block block) {
        return new BlockMatcher(label, state -> state.isOf(block));
    }

    /** Matches any block in a tag, built from a vanilla registry lookup. */
    public static BlockMatcher ofTag(TagKey<Block> tag) {
        return new BlockMatcher("#" + tag.id(), state -> state.isIn(tag));
    }

    /** Matches every non-air block. Used by {@code /analyzechunks blocks}. */
    public static BlockMatcher allNonAir() {
        return new BlockMatcher("all blocks", state -> !state.isAir());
    }
}
