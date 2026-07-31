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
package dev.locateplus.fabric;

import dev.locateplus.command.LPCommands;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.core.TickTasks;
import dev.locateplus.platform.Services;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Fabric entrypoint.
 *
 * <p>Everything loader-specific lives here and in {@link FabricPlatform}: bootstrap the platform,
 * register commands, pump the schedulers each tick, and clean up on shutdown. Porting to Forge
 * means writing the same four hooks against Forge's event bus.</p>
 *
 * <p>Implements {@link DedicatedServerModInitializer} as well as {@link ModInitializer} so the mod
 * is explicitly server-side; it also loads fine in singleplayer, where the integrated server runs
 * the same code.</p>
 */
public final class LocatePlusFabric implements ModInitializer, DedicatedServerModInitializer {

    @Override
    public void onInitialize() {
        if (Services.isBootstrapped()) {
            return;
        }
        Services.bootstrap(new FabricPlatform());

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        LPCommands.registerAll(dispatcher, registryAccess));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LPScheduler.onServerTick();
            TickTasks.tick();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LPScheduler.shutdown();
            TickTasks.clear();
        });

        LPLog.info(LPConstants.MOD_NAME + " ready. Type /lp in game for the command list "
                + "(permission level " + LPConstants.PERMISSION_LEVEL + ", server-side)");
    }

    @Override
    public void onInitializeServer() {
        onInitialize();
    }
}
