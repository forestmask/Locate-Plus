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
import net.minecraft.server.command.ServerCommandSource;

/**
 * Single registration entry point for every command.
 *
 * <p>Loader-agnostic on purpose: Fabric calls this from {@code CommandRegistrationCallback}, and a
 * future Forge build would call the identical method from {@code RegisterCommandsEvent}.</p>
 */
public final class LPCommands {

    private LPCommands() {
    }

    public static void registerAll(CommandDispatcher<ServerCommandSource> dispatcher,
                                   net.minecraft.command.CommandRegistryAccess registryAccess) {
        HelpCommand.register(dispatcher);
        InspectCommand.register(dispatcher);
        SafeTpCommand.register(dispatcher);
        LocateCommand.register(dispatcher);
        AnalyzeChunksCommand.register(dispatcher);
        GlowCommand.register(dispatcher);
        PurgeEntitiesCommand.register(dispatcher);

        // Registered last so its /locate biome and /locate structure replace vanilla's.
        VanillaLocateCommand.register(dispatcher, registryAccess);
    }
}
