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
package dev.locateplus.report;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Thin compatibility layer over {@link Msg}.
 *
 * <p>{@code Msg} is the real formatting layer and is what new code should use. This class remains
 * because several commands already call it, and forwarding is cheaper and safer than rewriting
 * every call site at once. Each method is a one-line delegate, there is no formatting logic
 * here, so the two cannot drift apart.</p>
 */
public final class Chat {

    private Chat() {
    }

    public static MutableText prefixed(Text body) {
        return Msg.prefixed(body);
    }

    public static void info(ServerCommandSource source, String message) {
        Msg.info(source, message);
    }

    public static void success(ServerCommandSource source, String message) {
        Msg.success(source, message);
    }

    public static void warn(ServerCommandSource source, String message) {
        Msg.warn(source, message);
    }

    public static void error(ServerCommandSource source, String message) {
        Msg.error(source, message);
    }

    public static String coords(BlockPos pos) {
        return Msg.coords(pos);
    }

    public static String coords(Vec3d pos) {
        return Msg.coords(pos);
    }

    public static String number(long value) {
        return Msg.number(value);
    }

    public static String percent(double fraction) {
        return Msg.percent(fraction);
    }
}
