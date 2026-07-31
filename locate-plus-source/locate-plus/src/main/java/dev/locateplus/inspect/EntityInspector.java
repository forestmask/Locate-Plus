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

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.village.VillagerData;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Entity {@code /inspect} sections: equipment, status effects, villager trades, and pet ownership.
 *
 * <p>Each section is skipped entirely when it has nothing to report, so inspecting a plain zombie
 * stays short while inspecting a geared, named, effect-laden one shows everything.</p>
 */
public final class EntityInspector {

    private EntityInspector() {
    }

    /** Everything worth knowing about one entity, appended to {@code out}. */
    public static void describe(InspectLine out, Entity entity, ServerWorld world) {
        equipment(out, entity);
        effects(out, entity);
        villager(out, entity);
        tameable(out, entity, world);
        misc(out, entity);
    }

    // ---- equipment -----------------------------------------------------------------------------

    private static void equipment(InspectLine out, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        InspectLine section = new InspectLine();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack;
            try {
                stack = living.getEquippedStack(slot);
            } catch (Throwable t) {
                continue;
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            StringBuilder line = new StringBuilder();
            line.append(slot.getName()).append(": ");
            if (stack.getCount() > 1) {
                line.append(stack.getCount()).append("x ");
            }
            line.append(Registries.ITEM.getId(stack.getItem()));

            if (stack.hasCustomName()) {
                line.append(" \"").append(stack.getName().getString()).append('"');
            }
            if (stack.isDamageable() && stack.getDamage() > 0) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                line.append("  [").append(remaining).append('/')
                        .append(stack.getMaxDamage()).append(" durability]");
            }

            boolean enchanted = stack.hasEnchantments();
            section.item(line.toString(), enchanted ? Formatting.LIGHT_PURPLE : Formatting.WHITE);

            if (enchanted) {
                EnchantmentHelper.get(stack).forEach((enchantment, level) -> {
                    var id = Registries.ENCHANTMENT.getId(enchantment);
                    section.item("    " + (id == null ? enchantment.toString() : id.getPath())
                            + " " + level, Formatting.DARK_PURPLE);
                });
            }
        }

        if (!section.isEmpty()) {
            out.section("Equipment");
            out.lines().addAll(section.lines());
        }
    }

    // ---- status effects ------------------------------------------------------------------------

    private static void effects(InspectLine out, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Collection<StatusEffectInstance> active;
        try {
            active = living.getStatusEffects();
        } catch (Throwable t) {
            return;
        }
        if (active.isEmpty()) {
            return;
        }

        out.section("Status effects");
        for (StatusEffectInstance instance : active) {
            var id = Registries.STATUS_EFFECT.getId(instance.getEffectType());
            String name = id == null ? instance.getEffectType().toString() : id.toString();
            int level = instance.getAmplifier() + 1;

            String duration = instance.isInfinite()
                    ? "infinite"
                    : formatTicks(instance.getDuration());

            StringBuilder line = new StringBuilder();
            line.append(name);
            if (level > 1) {
                line.append(" ").append(level);
            }
            line.append("  (").append(duration).append(')');
            if (instance.isAmbient()) {
                line.append(" [ambient]");
            }

            out.item(line.toString(), instance.getEffectType().isBeneficial()
                    ? Formatting.GREEN : Formatting.RED);
        }
    }

    private static String formatTicks(int ticks) {
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0) {
            return String.format(Locale.US, "%d:%02d", minutes, seconds);
        }
        return seconds + "s";
    }

    // ---- villagers -----------------------------------------------------------------------------

    private static void villager(InspectLine out, Entity entity) {
        if (!(entity instanceof VillagerEntity villager)) {
            return;
        }
        out.section("Villager");

        VillagerData data = villager.getVillagerData();
        var professionId = Registries.VILLAGER_PROFESSION.getId(data.getProfession());
        var typeId = Registries.VILLAGER_TYPE.getId(data.getType());

        out.field("Profession", professionId == null ? "unknown" : professionId.toString());
        out.field("Biome type", typeId == null ? "unknown" : typeId.getPath());
        out.field("Level", data.getLevel() + " (" + careerTier(data.getLevel()) + ")");
        out.field("Experience", String.valueOf(villager.getExperience()));

        try {
            int offers = villager.getOffers().size();
            out.field("Trades offered", String.valueOf(offers));

            int used = 0;
            for (var offer : villager.getOffers()) {
                if (offer.isDisabled()) {
                    used++;
                }
            }
            if (used > 0) {
                out.field("Locked trades", used + " of " + offers, Formatting.YELLOW);
            }
        } catch (Throwable ignored) {
            // offers may not be generated until first interaction
        }
    }

    /** Vanilla career tier names for villager levels 1-5. */
    private static String careerTier(int level) {
        return switch (level) {
            case 1 -> "Novice";
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> "level " + level;
        };
    }

    // ---- pets ----------------------------------------------------------------------------------

    private static void tameable(InspectLine out, Entity entity, ServerWorld world) {
        InspectLine section = new InspectLine();
        UUID owner = null;

        if (entity instanceof TameableEntity tameable) {
            section.field("Tamed", String.valueOf(tameable.isTamed()),
                    tameable.isTamed() ? Formatting.GREEN : Formatting.GRAY);
            section.field("Sitting", String.valueOf(tameable.isSitting()));
            owner = tameable.getOwnerUuid();
        } else if (entity instanceof AbstractHorseEntity horse) {
            section.field("Tamed", String.valueOf(horse.isTame()),
                    horse.isTame() ? Formatting.GREEN : Formatting.GRAY);
            section.field("Saddled", String.valueOf(horse.isSaddled()));
            owner = horse.getOwnerUuid();
        }

        if (owner != null) {
            PlayerEntity ownerPlayer = world.getPlayerByUuid(owner);
            section.field("Owner", owner + (ownerPlayer == null
                    ? "  (offline)" : "  (" + ownerPlayer.getName().getString() + ")"));
        }

        // Breeding state applies to any animal, tame or not.
        if (entity instanceof AnimalEntity animal) {
            if (animal.isBaby()) {
                section.field("Age", "baby", Formatting.YELLOW);
            }
            int loveTicks = animal.getLoveTicks();
            if (loveTicks > 0) {
                section.field("In love", formatTicks(loveTicks), Formatting.LIGHT_PURPLE);
            }
            int breedingAge = animal.getBreedingAge();
            if (breedingAge > 0) {
                section.field("Breeding cooldown", formatTicks(breedingAge));
            }
        }

        if (!section.isEmpty()) {
            out.section("Animal / pet");
            out.lines().addAll(section.lines());
        }
    }

    // ---- misc ----------------------------------------------------------------------------------

    private static void misc(InspectLine out, Entity entity) {
        InspectLine section = new InspectLine();

        Set<String> tags = entity.getCommandTags();
        if (!tags.isEmpty()) {
            section.field("Command tags", String.join(", ", tags), Formatting.AQUA);
        }
        if (entity.isSilent()) {
            section.field("Silent", "true");
        }
        if (entity.isInvulnerable()) {
            section.field("Invulnerable", "true", Formatting.YELLOW);
        }
        if (entity.hasCustomName() && entity.isCustomNameVisible()) {
            section.field("Nameplate", "visible");
        }
        if (entity instanceof MobEntity mob) {
            if (mob.isPersistent()) {
                section.field("Persistent", "true (will not despawn)", Formatting.GREEN);
            }
            if (mob.isAiDisabled()) {
                section.field("AI", "disabled", Formatting.YELLOW);
            }
            LivingEntity target = mob.getTarget();
            if (target != null) {
                section.field("Attacking", target.getName().getString(), Formatting.RED);
            }
            if (mob.isLeashed()) {
                section.field("Leashed", "true");
            }
        }
        if (entity instanceof LivingEntity living) {
            if (living.isSleeping()) {
                section.field("Sleeping", "true");
            }
            if (living.getArmor() > 0) {
                section.field("Armor points", String.valueOf(living.getArmor()));
            }
            if (living.isOnFire()) {
                section.field("On fire", "true", Formatting.RED);
            }
        }

        if (!section.isEmpty()) {
            out.section("Other");
            out.lines().addAll(section.lines());
        }
    }
}
