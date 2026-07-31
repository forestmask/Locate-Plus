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
 * A unit of work that runs on the server thread in tick-sized slices.
 *
 * <p>World data must be read on the server thread (chunk and entity storage are not thread safe),
 * so scanning is sliced rather than threaded: each tick the scheduler hands running jobs a shared
 * time budget and they stop as soon as it is spent. Counting, sorting, formatting and file writing
 * then happen off-thread, exactly the split the guide asks for.</p>
 */
public interface ScanJob {

    /**
     * Perform work until {@code deadlineNanos} (on the {@link System#nanoTime()} scale).
     *
     * @return {@code true} when the job is finished and should be removed
     */
    boolean step(long deadlineNanos);

    /** Human readable description, used in error reporting. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** Called if the job throws, or if the server shuts down before it completes. */
    default void onCancelled(Throwable cause) {
    }
}
