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
package dev.locateplus.core;

/**
 * Tunables and limits in one place.
 *
 * <p>These are intentionally constants rather than a config file: the guide specifies a hardcoded
 * permission level and fixed behaviour. Anything a server owner is likely to want to change is
 * grouped here so it is a one-line edit.</p>
 */
public final class LPConstants {

    private LPConstants() {
    }

    public static final String MOD_ID = "locateplus";

    /** Folder name under {@code config/}. Hyphenated to match the jar and the docs. */
    public static final String MOD_ID_PATH = "locate-plus";
    public static final String MOD_NAME = "Locate Plus";

    /** Guide: "Hardcoded permission level: 2". */
    public static final int PERMISSION_LEVEL = 2;

    // ---- Radius defaults / limits -------------------------------------------------------------

    /** Default radius, in blocks, for {@code /locate block} and {@code /locate entity}. */
    public static final int DEFAULT_BLOCK_RADIUS = 64;

    /**
     * Upper bound accepted by the radius argument.
     *
     * <p>This is a sanity ceiling on the <em>number</em>, not a policy limit, it exists only so
     * Brigadier has a range and a typo like 99999999 cannot overflow the chunk maths. Anything a
     * machine can actually finish is allowed; how hard to push is the operator's call.</p>
     */
    public static final int MAX_BLOCK_RADIUS = 100_000;

    /** Default radius, in chunks, for {@code /analyzechunks}. */
    public static final int DEFAULT_CHUNK_RADIUS = 4;

    /** Sanity ceiling only, see {@link #MAX_BLOCK_RADIUS}. 6250 chunks is 100k blocks. */
    public static final int MAX_CHUNK_RADIUS = 6_250;

    // ---- Performance --------------------------------------------------------------------------

    /**
     * Nanoseconds of server-thread time all scan jobs combined may consume per tick.
     * A tick is 50 ms; 8 ms keeps headroom so scans never visibly lag the server.
     */
    public static final long TICK_BUDGET_NANOS = 8_000_000L;

    /**
     * Chunk count above which a force-load scan is called out as very large in chat.
     *
     * <p>Purely advisory. The scan still runs, the warning states the real numbers so the
     * decision is an informed one rather than a blocked one.</p>
     */
    public static final int FORCELOAD_WARN_THRESHOLD = 4_225; // 65x65

    /** Chunks whose generation we will wait on before giving up, per scan. */
    public static final int FORCELOAD_TIMEOUT_TICKS = 20 * 120;

    /**
     * Maximum individual coordinates retained for an export. Beyond this the export is marked
     * truncated rather than risking an out-of-memory kill on a big forceload scan.
     */
    public static final int MAX_EXPORT_POSITIONS = 2_000_000;

    // ---- Presentation -------------------------------------------------------------------------

    /** Guide: "Shows the top 10-20 block types in chat." */
    public static final int CHAT_TOP_N = 15;

    /** How long located blocks keep their particle marker. */
    public static final int PARTICLE_DURATION_TICKS = 20 * 60;
    public static final int PARTICLE_INTERVAL_TICKS = 10;

    /** How long the nearest entity glows. */
    public static final int GLOW_DURATION_TICKS = 20 * 60;
}
