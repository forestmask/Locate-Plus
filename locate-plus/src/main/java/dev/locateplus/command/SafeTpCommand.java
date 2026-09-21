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
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.locateplus.core.LPConfig;
import dev.locateplus.report.Chat;
import dev.locateplus.teleport.SafeSpot;
import dev.locateplus.teleport.TeleportService;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /safetp [targets] <destination>}.
 *
 * Note the argument order: Brigadier resolves the one-argument form first, so {@code /safetp 120 64
 * -35} is unambiguous and {@code /safetp @a 120 64 -35} only matches when a selector is actually
 * present.
 */
public final class SafeTpCommand {

    /**
     * Offer {@code ~ ~ ~} and nothing else for a coordinate.
     *
     * Left to itself the client suggests two things at once: the relative form, and the numeric
     * coordinates of whatever block is under the crosshair. Two candidates that look nothing alike
     * in the same slot make the argument hard to read, and the numeric one is rarely what is
     * wanted, so only the relative form is offered.
     */
    private static final SuggestionProvider<ServerCommandSource> RELATIVE_ONLY =
            (ctx, builder) -> CommandSource.suggestPositions(builder.getRemaining(),
                    Collections.singleton(CommandSource.RelativePosition.ZERO_WORLD),
                    builder, text -> true);

    private SafeTpCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("safetp")
                .requires(source -> source.hasPermissionLevel(LPConfig.get().permissionLevel()))

                // /safetp <destination>
                .then(argument("destination", Vec3ArgumentType.vec3())
                        .suggests(RELATIVE_ONLY)
                        .executes(ctx -> teleportSelf(ctx)))

                // /safetp <targets> <destination>
                .then(argument("targets", EntityArgumentType.entities())
                        .then(argument("destination", Vec3ArgumentType.vec3())
                                .suggests(RELATIVE_ONLY)
                                .executes(ctx -> teleportTargets(ctx)))));
    }

    private static int teleportSelf(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        Entity self = source.getEntity();
        if (self == null) {
            Chat.error(source, "This form of /safetp needs an entity to move. "
                    + "From the console, use /safetp <targets> <destination>.");
            return 0;
        }
        return run(source, Collections.singletonList(self), destination(ctx));
    }

    private static int teleportTargets(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        Collection<? extends Entity> targets =
                EntityArgumentType.getOptionalEntities(ctx, "targets");
        if (targets.isEmpty()) {
            Chat.warn(source, "No entities matched that selector. Nothing was teleported.");
            return 0;
        }
        return run(source, List.copyOf(targets), destination(ctx));
    }

    private static Vec3d destination(CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        // Handles absolute, ~ relative and ^ local coordinates exactly as vanilla /tp does.
        return Vec3ArgumentType.getVec3(ctx, "destination");
    }

    private static int run(ServerCommandSource source, List<? extends Entity> targets,
                           Vec3d destination) {
        ServerWorld world = source.getWorld();
        BlockPos requested = BlockPos.ofFloored(destination);

        // Same test vanilla /tp applies. Outside it the world cannot generate terrain, so the
        // search would read nothing and the chunks it asked for would fail on the server thread.
        if (!World.isValid(requested)) {
            Chat.error(source, "That position is outside the world. "
                    + "X and Z go up to 30,000,000 and Y from -20,000,000 to 20,000,000.");
            return 0;
        }

        int moved = 0;
        int failed = 0;
        int unchecked = 0;

        for (Entity entity : targets) {
            SafeSpot spot = TeleportService.teleportSafely(entity, world, destination);
            if (spot == null) {
                failed++;
                continue;
            }
            moved++;
            if (!spot.isChecked()) {
                unchecked++;
            }

            // Tell the player who moved where they ended up.
            if (entity instanceof ServerPlayerEntity player) {
                String message = spot.isChecked()
                        ? "Teleported to " + spot.describeLanding()
                        : "Teleported to " + Chat.coords(spot.blockPos())
                                + ". Nowhere safe to stand was found, so this is the exact spot "
                                + "you asked for and it was not checked.";
                player.sendMessage(Chat.prefixed(Text.literal(message)
                        .formatted(spot.isChecked() ? Formatting.GREEN : Formatting.YELLOW)),
                        false);
            }

            // And tell the command source, when it is someone else.
            if (source.getEntity() != entity) {
                String name = entity.getName().getString();
                if (spot.isChecked()) {
                    Chat.success(source, name + " -> " + spot.describeLanding());
                } else {
                    Chat.warn(source, name + " -> " + Chat.coords(spot.blockPos())
                            + ", unchecked.");
                }
            }
        }

        if (unchecked > 0 && source.getEntity() == null) {
            Chat.warn(source, "No safe location found near " + Chat.coords(requested)
                    + ", so " + unchecked + " target" + (unchecked == 1 ? " was" : "s were")
                    + " sent there unchecked.");
        }
        if (failed > 0) {
            Chat.error(source, "No safe location found near " + Chat.coords(requested)
                    + " for " + failed + " target" + (failed == 1 ? "" : "s")
                    + ". Set safetp_vanilla_fallback to true to teleport there anyway.");
        }
        return moved;
    }
}
