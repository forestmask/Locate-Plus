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
package dev.locateplus.model;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Immutable snapshot of one entity.
 *
 * <p>Snapshotting matters: entities move, despawn and die while an export is being written on a
 * background thread. Copying the fields we need on the server thread means the report can be built
 * off-thread without ever touching a live {@code Entity}.</p>
 */
public final class EntityRecord {

    private final Identifier typeId;
    private final UUID uuid;
    private final String displayName;
    private final String customName;
    private final Vec3d pos;
    private final BlockPos blockPos;
    private final double distance;
    private final boolean glowing;
    private final Float health;
    private final Float maxHealth;
    private final String itemId;
    private final Integer itemCount;

    private EntityRecord(Builder b) {
        this.typeId = b.typeId;
        this.uuid = b.uuid;
        this.displayName = b.displayName;
        this.customName = b.customName;
        this.pos = b.pos;
        this.blockPos = b.blockPos;
        this.distance = b.distance;
        this.glowing = b.glowing;
        this.health = b.health;
        this.maxHealth = b.maxHealth;
        this.itemId = b.itemId;
        this.itemCount = b.itemCount;
    }

    /** Capture everything the guide asks exports and {@code /inspect} to show. */
    public static EntityRecord of(Entity entity, Vec3d origin) {
        Builder b = new Builder();
        b.typeId = Registries.ENTITY_TYPE.getId(entity.getType());
        b.uuid = entity.getUuid();
        b.displayName = entity.getName().getString();
        b.customName = entity.getCustomName() == null ? null : entity.getCustomName().getString();
        b.pos = entity.getPos();
        b.blockPos = entity.getBlockPos();
        b.distance = origin == null ? 0.0 : Math.sqrt(origin.squaredDistanceTo(entity.getPos()));
        b.glowing = entity.isGlowing();

        if (entity instanceof LivingEntity living) {
            b.health = living.getHealth();
            b.maxHealth = living.getMaxHealth();
        }
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getStack();
            b.itemId = Registries.ITEM.getId(stack.getItem()).toString();
            b.itemCount = stack.getCount();
        }
        return new EntityRecord(b);
    }

    public Identifier typeId() {
        return typeId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String displayName() {
        return displayName;
    }

    public String customName() {
        return customName;
    }

    public Vec3d pos() {
        return pos;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    public double distance() {
        return distance;
    }

    public boolean glowing() {
        return glowing;
    }

    public Float health() {
        return health;
    }

    public Float maxHealth() {
        return maxHealth;
    }

    public String itemId() {
        return itemId;
    }

    public Integer itemCount() {
        return itemCount;
    }

    private static final class Builder {
        Identifier typeId;
        UUID uuid;
        String displayName;
        String customName;
        Vec3d pos;
        BlockPos blockPos;
        double distance;
        boolean glowing;
        Float health;
        Float maxHealth;
        String itemId;
        Integer itemCount;
    }
}
