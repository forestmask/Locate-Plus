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

import dev.locateplus.core.LPConstants;
import dev.locateplus.core.TickTasks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** Visual markers: long-lasting particles on located blocks, Glowing on the nearest entity. */
public final class Highlights {

    private Highlights() {
    }

    /**
     * Mark a block position with particles that persist for about a minute.
     *
     * <p>Particles are re-emitted on a repeating task because a single spawn call lasts under a
     * second, far too brief to walk towards, which is what the guide's "long-lasting particle
     * markers" are for.</p>
     */
    public static void markBlock(ServerWorld world, BlockPos pos) {
        int repeats = Math.max(1, LPConstants.PARTICLE_DURATION_TICKS / LPConstants.PARTICLE_INTERVAL_TICKS);
        TickTasks.scheduleRepeating(LPConstants.PARTICLE_INTERVAL_TICKS, repeats, () -> {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return;
            }
            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 0.5;
            double cz = pos.getZ() + 0.5;

            // A bright core plus a sparse outline, visible through terrain at a distance.
            world.spawnParticles(ParticleTypes.END_ROD, cx, cy, cz, 6, 0.25, 0.25, 0.25, 0.0);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, cx, cy + 1.0, cz, 4, 0.3, 0.3, 0.3, 0.0);
        });
    }

    /**
     * Apply the vanilla Glowing effect to an entity.
     *
     * <p>Living entities get a status effect. Everything else, item entities, boats, armour
     * stands, cannot hold effects, so the entity's glow flag is set directly and cleared later
     * by a scheduled task.</p>
     */
    public static void glow(ServerWorld world, Entity entity) {
        if (entity instanceof LivingEntity living) {
            living.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.GLOWING, LPConstants.GLOW_DURATION_TICKS, 0, false, false, true));
            return;
        }

        entity.setGlowing(true);
        UUID id = entity.getUuid();
        TickTasks.schedule(LPConstants.GLOW_DURATION_TICKS, () -> {
            Entity live = world.getEntity(id);
            if (live != null && !live.isRemoved()) {
                live.setGlowing(false);
            }
        });
    }
}
