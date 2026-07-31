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
import dev.locateplus.core.LPConstants;
import dev.locateplus.report.Chat;
import dev.locateplus.teleport.SafeSpot;
import dev.locateplus.teleport.TeleportService;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /safetp [targets] <destination>}.
 *
 * <p>Accepts the same coordinate styles as vanilla {@code /tp}, absolute, {@code ~} relative and
 * {@code ^} local, by reusing {@link Vec3ArgumentType}, so anything that works in {@code /tp}
 * works here. The destination is then run through the safe-location search before anyone moves.</p>
 *
 * <p>Note the argument order: Brigadier resolves the one-argument form first, so
 * {@code /safetp 120 64 -35} is unambiguous and {@code /safetp @a 120 64 -35} only matches when a
 * selector is actually present.</p>
 */
public final class SafeTpCommand {

    private SafeTpCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("safetp")
                .requires(source -> source.hasPermissionLevel(LPConstants.PERMISSION_LEVEL))

                // /safetp <destination>
                .then(argument("destination", Vec3ArgumentType.vec3())
                        .executes(ctx -> teleportSelf(ctx)))

                // /safetp <targets> <destination>
                .then(argument("targets", EntityArgumentType.entities())
                        .then(argument("destination", Vec3ArgumentType.vec3())
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
        // getOptionalEntities: an empty selector is reported cleanly rather than throwing
        // "No entity was found", the same failure mode this mod fixes for /locate entity.
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

    private static int run(ServerCommandSource source, List<? extends Entity> targets, Vec3d destination) {
        ServerWorld world = source.getWorld();
        BlockPos requested = BlockPos.ofFloored(destination);

        int moved = 0;
        int failed = 0;

        for (Entity entity : targets) {
            SafeSpot spot = TeleportService.teleportSafely(entity, world, requested);
            if (spot == null) {
                failed++;
                continue;
            }
            moved++;

            // Tell the player who moved where they ended up.
            if (entity instanceof ServerPlayerEntity player) {
                String message = "Teleported to " + spot.describeLanding() + ".";
                player.sendMessage(Chat.prefixed(Text.literal(message)
                        .formatted(Formatting.GREEN)), false);
            }

            // And tell the command source, when it is someone else.
            if (source.getEntity() != entity) {
                String name = entity.getName().getString();
                Chat.success(source, name + " -> " + spot.describeLanding());
            }
        }

        if (failed > 0) {
            Chat.error(source, "No safe location found near " + Chat.coords(requested)
                    + " for " + failed + " target" + (failed == 1 ? "" : "s") + ".");
        }
        return moved;
    }
}
