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

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * A resolved, radius-independent description of "which entities the player meant".
 *
 * <p>Deliberately <em>not</em> a vanilla {@code EntitySelector}. A vanilla selector bakes in its
 * own distance/limit/sorting rules and, critically, refuses to return an empty list. Locate
 * Enhanced needs the opposite: a pure predicate it can apply to whatever candidate set the scanner
 * produces, with "nothing matched" treated as an ordinary, reportable result.</p>
 */
public final class EntityQuery {

    private final String displayName;
    private final Predicate<Entity> predicate;
    private final boolean playersOnly;

    private EntityQuery(String displayName, Predicate<Entity> predicate, boolean playersOnly) {
        this.displayName = displayName;
        this.predicate = predicate;
        this.playersOnly = playersOnly;
    }

    public String displayName() {
        return displayName;
    }

    /** True when only players can possibly match ({@code @a}, {@code @p}, {@code @r}, a name). */
    public boolean playersOnly() {
        return playersOnly;
    }

    public boolean matches(Entity entity) {
        try {
            return predicate.test(entity);
        } catch (Throwable t) {
            // A modded entity with a broken getName()/getType() must never abort a whole scan.
            return false;
        }
    }

    // ---- factories ----------------------------------------------------------------------------

    public static EntityQuery all() {
        return new EntityQuery("any entity", e -> true, false);
    }

    public static EntityQuery ofType(EntityType<?> type) {
        Identifier id = Registries.ENTITY_TYPE.getId(type);
        boolean players = type == EntityType.PLAYER;
        return new EntityQuery(id.toString(), e -> e.getType() == type, players);
    }

    public static EntityQuery ofTag(TagKey<EntityType<?>> tag) {
        return new EntityQuery("#" + tag.id(), e -> e.getType().isIn(tag), false);
    }

    /** Matches a player by exact name, case-insensitively. */
    public static EntityQuery ofPlayerName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        return new EntityQuery(name,
                e -> e instanceof net.minecraft.server.network.ServerPlayerEntity
                        && e.getName().getString().toLowerCase(Locale.ROOT).equals(wanted),
                true);
    }

    /** Matches one specific entity instance, used for UUID lookups and {@code @s}. */
    public static EntityQuery ofExact(Entity target, String label) {
        java.util.UUID uuid = target.getUuid();
        return new EntityQuery(label, e -> e.getUuid().equals(uuid),
                target instanceof net.minecraft.server.network.ServerPlayerEntity);
    }

    public static EntityQuery ofPredicate(String label, Predicate<Entity> predicate, boolean playersOnly) {
        return new EntityQuery(label, predicate, playersOnly);
    }

    /** Combines this query with an extra condition, keeping the original label. */
    public EntityQuery and(Predicate<Entity> extra) {
        Predicate<Entity> base = this.predicate;
        return new EntityQuery(displayName, e -> base.test(e) && extra.test(e), playersOnly);
    }

    /**
     * Excludes the command source's own entity.
     * Used so {@code /locate entity @e[type=player]} does not simply report the caller at 0 blocks.
     */
    public EntityQuery excludingSelf(ServerCommandSource source) {
        Entity self = source.getEntity();
        if (self == null) {
            return this;
        }
        java.util.UUID selfId = self.getUuid();
        return and(e -> !e.getUuid().equals(selfId));
    }
}
