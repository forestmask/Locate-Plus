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
package dev.locateplus.teleport;

import dev.locateplus.core.LPConstants;
import dev.locateplus.report.Chat;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Collections;

/**
 * Performs safe teleports and reports the outcome, for both {@code /safetp} and the chat buttons.
 */
public final class TeleportService {

    private TeleportService() {
    }

    /**
     * Move {@code entity} to the nearest safe spot to {@code requested}.
     *
     * <p>Loads the destination chunk first: teleporting into an unloaded chunk is exactly the
     * situation where a naive safety check reads air everywhere and drops the player into the void.</p>
     *
     * @return the spot used, or {@code null} if nowhere safe was found
     */
    public static SafeSpot teleportSafely(Entity entity, ServerWorld world, BlockPos requested) {
        // Force the target chunk to at least FULL so block states are real, then look.
        world.getChunk(requested.getX() >> 4, requested.getZ() >> 4, ChunkStatus.FULL, true);

        SafeSpot spot = SafeLocator.find(world, requested);
        if (spot == null) {
            return null;
        }

        Vec3d pos = spot.position();
        if (entity instanceof ServerPlayerEntity player) {
            player.teleport(world, pos.x, pos.y, pos.z,
                    Collections.emptySet(), player.getYaw(), player.getPitch());
            player.fallDistance = 0.0f;
        } else {
            if (entity.getWorld() != world) {
                entity.moveToWorld(world);
            }
            entity.teleport(pos.x, pos.y, pos.z);
            entity.fallDistance = 0.0f;
        }
        return spot;
    }


    /**
     * A clickable {@code [Teleport]} button.
     *
     * <p>Runs {@code /safetp} rather than vanilla {@code /tp} so the click gets the same
     * column-search safety and the same "placed 12 blocks above" feedback as the typed command.
     * A player without permission level {@value LPConstants#PERMISSION_LEVEL} simply sees the click
     * rejected, which is the vanilla behaviour for any command button.</p>
     */
    public static Text teleportButton(BlockPos pos) {
        String command = String.format("/safetp %d %d %d", pos.getX(), pos.getY(), pos.getZ());
        return Text.literal("[Teleport]")
                .styled(style -> style
                        .withColor(Formatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new net.minecraft.text.ClickEvent(
                                net.minecraft.text.ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new net.minecraft.text.HoverEvent(
                                net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Safely teleport to " + Chat.coords(pos)
                                        + "\nYou will be told if you land above, below, or "
                                        + "away from this spot."))));
    }

}
