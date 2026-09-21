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
import dev.locateplus.core.ConfigFile;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.core.TickTasks;
import dev.locateplus.platform.Services;
import dev.locateplus.scan.BlockClass;
import dev.locateplus.scan.Highlights;
import dev.locateplus.visual.BlockBeacon;
import dev.locateplus.visual.RegionOutline;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Fabric entrypoint.
 *
 * Implements {@link DedicatedServerModInitializer} as well as {@link ModInitializer} so the mod is
 * explicitly server-side; it also loads fine in singleplayer, where the integrated server runs the
 * same code.
 */
public final class LocatePlusFabric implements ModInitializer, DedicatedServerModInitializer {

    /** How long after startup chunk loads are checked for markers left by a previous run. */
    private static final long STALE_MARKER_SWEEP_MILLIS = 5 * 60 * 1000L;

    /** When to stop checking. Set on server start. */
    private static volatile long markerSweepDeadline;

    /** How often untracked markers are looked for, in ticks. Ten seconds. */
    private static final int ORPHAN_SWEEP_TICKS = 200;

    private static int tickCounter;

    @Override
    public void onInitialize() {
        if (Services.isBootstrapped()) {
            return;
        }
        Services.bootstrap(new FabricPlatform());

        // Read the config before anything else: which commands exist and what permission level they
        // need are both decided while the command tree is being built.
        LPLog.info(ConfigFile.load());

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        LPCommands.registerAll(dispatcher, registryAccess));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LPScheduler.onServerTick();
            TickTasks.tick();
            Highlights.tick(server);

            // A marker is only supposed to exist while something is tracking it. Checking now and
            // then costs one entity query per world and catches anything that came adrift, so a
            // stray outline cannot outlive the run that made it.
            if (++tickCounter % ORPHAN_SWEEP_TICKS == 0) {
                int stray = Highlights.sweepOrphans(server);
                if (stray > 0) {
                    LPLog.info("Removed " + stray + " block marker(s) that nothing was tracking");
                }
            }
        });

        // Markers are real entities, so a server that stops without running its shutdown hook
        // leaves them in the world.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                markerSweepDeadline = System.currentTimeMillis() + STALE_MARKER_SWEEP_MILLIS);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (System.currentTimeMillis() > markerSweepDeadline) {
                return;
            }
            if (entity.getCommandTags().contains(BlockBeacon.TAG)
                    && !BlockBeacon.isOurs(entity.getUuid())) {
                entity.discard();
                LPLog.info("Removed a block marker left by a previous run");
            }
        });

        // Block tags are rebuilt when a datapack reloads, so every cached answer about which blocks
        // count as ore or as ground has to be thrown away with them.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resources, success) -> BlockClass.invalidate());

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LPScheduler.shutdown();
            TickTasks.clear();
            RegionOutline.clear();
            BlockBeacon.sweep(server);
            Highlights.forgetBeacons();
        });

        LPLog.info(LPConstants.MOD_NAME + " ready. Type /lp in game for the command list "
                + "(permission level " + LPConfig.get().permissionLevel() + ", server-side)");
    }

    @Override
    public void onInitializeServer() {
        onInitialize();
    }
}
