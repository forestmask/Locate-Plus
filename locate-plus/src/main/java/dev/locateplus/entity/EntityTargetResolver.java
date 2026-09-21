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
package dev.locateplus.entity;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the raw text after {@code /locate entity} into a {@link TargetSpec}.
 *
 * The obvious implementation, declare the argument as {@code EntityArgumentType.entities()} and
 * call {@code EntityArgumentType.getEntities(...)}, which is what makes {@code /locate entity}
 * fail.
 */
public final class EntityTargetResolver {

    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown entity type '" + id + "'. Use an id like minecraft:zombie, "
                    + "a tag like #minecraft:skeletons, or a selector like @e[type=minecraft:zombie]."));

    private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
            id -> Text.literal("Unknown entity tag '#" + id + "'. Entity tags come from data packs; "
                    + "check the tag exists in this world."));

    private static final SimpleCommandExceptionType EMPTY_TARGET = new SimpleCommandExceptionType(
            Text.literal("No target given. Try an id (minecraft:zombie), a tag (#minecraft:skeletons), "
                    + "a selector (@e[type=minecraft:zombie]), or * for everything."));

    private EntityTargetResolver() {
    }

    /**
     *
     * @param raw exactly what the player typed for the target argument
     */
    public static TargetSpec resolve(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim();

        // Brigadier hands back quoted strings verbatim; unwrap so "@e[type=pig]" behaves like
        // @e[type=pig].
        if (text.length() >= 2
                && ((text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
                || (text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\''))) {
            text = text.substring(1, text.length() - 1).trim();
        }

        if (text.isEmpty()) {
            throw EMPTY_TARGET.create();
        }

        // "*" / "all", everything.
        if (text.equals("*") || text.equalsIgnoreCase("all")) {
            return new SimpleSpec("any entity", EntityQuery.all());
        }

        // Vanilla selector.
        if (text.charAt(0) == '@') {
            return new SelectorSpec(text);
        }

        // Entity type tag.
        if (text.charAt(0) == '#') {
            String idText = text.substring(1);
            Identifier id = Identifier.tryParse(idText);
            if (id == null) {
                throw UNKNOWN_TAG.create(idText);
            }
            TagKey<EntityType<?>> tag = TagKey.of(RegistryKeys.ENTITY_TYPE, id);
            if (Registries.ENTITY_TYPE.getEntryList(tag).isEmpty()) {
                throw UNKNOWN_TAG.create(idText);
            }
            return new SimpleSpec("#" + id, EntityQuery.ofTag(tag));
        }

        // Entity type id, the case vanilla mis-reads as a player name.
        Identifier id = Identifier.tryParse(text.toLowerCase(Locale.ROOT));
        if (id != null && Registries.ENTITY_TYPE.containsId(id)) {
            Optional<EntityType<?>> type = Registries.ENTITY_TYPE.getOrEmpty(id);
            if (type.isPresent()) {
                return new SimpleSpec(id.toString(), EntityQuery.ofType(type.get()));
            }
        }

        // Raw UUID.
        try {
            UUID uuid = UUID.fromString(text);
            return new SimpleSpec(text,
                    EntityQuery.ofPredicate("uuid " + text, e -> e.getUuid().equals(uuid), false));
        } catch (IllegalArgumentException ignored) {
            // not a uuid; fall through
        }

        // Player name.
        if (isPlausiblePlayerName(text)) {
            return new SimpleSpec(text, EntityQuery.ofPlayerName(text)) {
                @Override
                public boolean playersOnly() {
                    return true;
                }
            };
        }

        // Anything left really is a mistake, say so instead of quietly finding pigs.
        throw UNKNOWN_TYPE.create(text);
    }

    private static boolean isPlausiblePlayerName(String text) {
        if (text.isEmpty() || text.length() > 16) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ---- spec implementations -----------------------------------------------------------------

    /** A pure predicate: entity type, tag, uuid, player name, or "everything". */
    private static class SimpleSpec implements TargetSpec {
        private final String label;
        private final EntityQuery query;

        SimpleSpec(String label, EntityQuery query) {
            this.label = label;
            this.query = query;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public EntityQuery bind(ServerCommandSource source) {
            return query;
        }
    }

    /** A vanilla selector, evaluated late. */
    private static final class SelectorSpec implements TargetSpec {
        private final String text;

        SelectorSpec(String text) {
            this.text = text;
        }

        @Override
        public String label() {
            return text;
        }

        @Override
        public EntityQuery bind(ServerCommandSource source) throws CommandSyntaxException {
            StringReader reader = new StringReader(text);
            EntitySelector selector = new EntitySelectorReader(reader, true).read();

            List<? extends Entity> matched = selector.getEntities(source);

            Set<UUID> ids = new HashSet<>(Math.max(16, matched.size() * 2));
            for (Entity e : matched) {
                ids.add(e.getUuid());
            }
            return EntityQuery.ofPredicate(text, e -> ids.contains(e.getUuid()), false);
        }
    }
}
