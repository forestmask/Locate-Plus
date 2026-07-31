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
 * <h2>Why this class exists</h2>
 *
 * <p>The obvious implementation, declare the argument as
 * {@code EntityArgumentType.entities()} and call {@code EntityArgumentType.getEntities(...)} --
 * is what makes {@code /locate entity} fail, in three separate ways:</p>
 *
 * <ol>
 *   <li><b>Empty results are thrown, not returned.</b> {@code getEntities()} raises
 *       {@code argument.entity.notfound.entity}, the red <i>"No entity was found"</i>, whenever
 *       a selector matches nothing. For a <i>search</i> command "nothing nearby" is a perfectly
 *       normal answer, not an error. {@code getOptionalEntities()} is the variant that returns an
 *       empty list, and the selector path below uses it.</li>
 *
 *   <li><b>A bare entity id is read as a player name.</b> Typing {@code /locate entity zombie}
 *       makes {@code EntitySelectorReader} look for a <i>player</i> called "zombie", find nobody,
 *       and throw the same error. Worse, {@code /locate entity minecraft:zombie} does not even
 *       parse: unquoted player names cannot contain {@code :}, so the reader stops at the colon
 *       and Brigadier rejects the leftover text. Both spellings are the ones people actually type,
 *       so they are handled explicitly here.</li>
 *
 *   <li><b>The entity registry is defaulted.</b> {@code Registries.ENTITY_TYPE} falls back to
 *       {@code minecraft:pig} for unknown ids, so a typo silently searches for pigs instead of
 *       reporting a mistake. Every lookup below goes through {@link net.minecraft.registry.Registry#getOrEmpty}
 *       and an explicit {@code containsId} check.</li>
 * </ol>
 *
 * <p>Accepted forms: {@code @e[...]} and every other vanilla selector, {@code minecraft:zombie},
 * {@code zombie}, {@code #minecraft:skeletons}, a player name, a raw UUID, and {@code *} for
 * "anything".</p>
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
     * @param raw exactly what the player typed for the target argument
     */
    public static TargetSpec resolve(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim();

        // Brigadier hands back quoted strings verbatim; unwrap so "@e[type=pig]" behaves like @e[type=pig].
        if (text.length() >= 2
                && ((text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
                || (text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\''))) {
            text = text.substring(1, text.length() - 1).trim();
        }

        if (text.isEmpty()) {
            throw EMPTY_TARGET.create();
        }

        // "*" / "all", everything. Handy shorthand; @e means the same thing.
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

    /**
     * A vanilla selector, evaluated late.
     *
     * <p>The selector is re-parsed and run at bind time so that force-loaded chunks are already in
     * memory. Results are reduced to a UUID set and handed back as a predicate, which keeps every
     * vanilla filter: {@code nbt}, {@code scores}, {@code team}, {@code limit}, {@code sort},
     * {@code distance}, working exactly as players expect, while letting the caller apply its own
     * radius and report "0 matches" without an exception.</p>
     */
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

            // getEntities() on the selector itself returns an empty list rather than throwing --
            // unlike EntityArgumentType.getEntities(), which is the source of "No entity was found".
            List<? extends Entity> matched = selector.getEntities(source);

            Set<UUID> ids = new HashSet<>(Math.max(16, matched.size() * 2));
            for (Entity e : matched) {
                ids.add(e.getUuid());
            }
            return EntityQuery.ofPredicate(text, e -> ids.contains(e.getUuid()), false);
        }
    }
}
