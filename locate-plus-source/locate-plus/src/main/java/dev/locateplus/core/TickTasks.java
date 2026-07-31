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

import java.util.ArrayList;
import java.util.List;

/**
 * Delayed and repeating work on the server thread.
 *
 * <p>Used for the things the guide asks to persist after a command returns: particle markers that
 * keep re-emitting for a minute, and clearing the glow flag on entities that cannot hold a status
 * effect. Kept separate from {@link LPScheduler} because these tasks are tiny and must run every
 * tick regardless of the scan time budget.</p>
 */
public final class TickTasks {

    private static final List<Task> TASKS = new ArrayList<>();
    private static final Object LOCK = new Object();

    private TickTasks() {
    }

    /** Run {@code action} once, {@code delayTicks} from now. */
    public static void schedule(int delayTicks, Runnable action) {
        synchronized (LOCK) {
            TASKS.add(new Task(action, Math.max(0, delayTicks), 0, 1));
        }
    }

    /**
     * Run {@code action} every {@code intervalTicks} until it has run {@code repeats} times.
     * The first run happens one interval from now.
     */
    public static void scheduleRepeating(int intervalTicks, int repeats, Runnable action) {
        int interval = Math.max(1, intervalTicks);
        synchronized (LOCK) {
            TASKS.add(new Task(action, interval, interval, Math.max(1, repeats)));
        }
    }

    /** Must be called from the server thread, once per tick. */
    public static void tick() {
        List<Task> snapshot;
        synchronized (LOCK) {
            if (TASKS.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(TASKS);
        }

        List<Task> finished = null;
        for (Task task : snapshot) {
            if (--task.ticksRemaining > 0) {
                continue;
            }
            try {
                task.action.run();
            } catch (Throwable t) {
                LPLog.error("Scheduled task failed", t);
                task.runsRemaining = 0;
            }
            if (--task.runsRemaining <= 0) {
                if (finished == null) {
                    finished = new ArrayList<>(4);
                }
                finished.add(task);
            } else {
                task.ticksRemaining = task.interval;
            }
        }

        if (finished != null) {
            synchronized (LOCK) {
                TASKS.removeAll(finished);
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            TASKS.clear();
        }
    }

    private static final class Task {
        final Runnable action;
        final int interval;
        int ticksRemaining;
        int runsRemaining;

        Task(Runnable action, int delay, int interval, int runs) {
            this.action = action;
            this.ticksRemaining = Math.max(1, delay);
            this.interval = Math.max(1, interval);
            this.runsRemaining = runs;
        }
    }

}
