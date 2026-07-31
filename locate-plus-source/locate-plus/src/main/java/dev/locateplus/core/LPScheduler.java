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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link ScanJob}s in tick-sized slices on the server thread, and owns the background pool
 * used for formatting and export writing.
 *
 * <p>The loader entrypoint calls {@link #onServerTick()} once per server tick and
 * {@link #shutdown()} on server stop. That is the only wiring a new loader has to provide.</p>
 */
public final class LPScheduler {

    private static final Deque<ScanJob> JOBS = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private static volatile ExecutorService background;

    private LPScheduler() {
    }

    public static void submit(ScanJob job) {
        synchronized (LOCK) {
            JOBS.add(job);
        }
    }

    /**
     * Advance every queued job, sharing {@link LPConstants#TICK_BUDGET_NANOS} between them.
     * Must be called from the server thread.
     */
    public static void onServerTick() {
        List<ScanJob> snapshot;
        synchronized (LOCK) {
            if (JOBS.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(JOBS);
        }

        long budgetPerJob = Math.max(250_000L, LPConstants.TICK_BUDGET_NANOS / snapshot.size());

        for (ScanJob job : snapshot) {
            long deadline = System.nanoTime() + budgetPerJob;
            boolean done;
            try {
                done = job.step(deadline);
            } catch (Throwable t) {
                done = true;
                LPLog.error("Scan job '" + job.describe() + "' failed", t);
                try {
                    job.onCancelled(t);
                } catch (Throwable ignored) {
                    // never let cleanup mask the original failure
                }
            }
            if (done) {
                synchronized (LOCK) {
                    JOBS.remove(job);
                }
            }
        }
    }

    /** Off-thread work: sorting, string building, writing export files. Never touches world data. */
    public static ExecutorService background() {
        ExecutorService pool = background;
        if (pool == null || pool.isShutdown()) {
            synchronized (LOCK) {
                if (background == null || background.isShutdown()) {
                    background = Executors.newFixedThreadPool(
                            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2)),
                            new NamedThreads());
                }
                pool = background;
            }
        }
        return pool;
    }

    public static void shutdown() {
        List<ScanJob> pending;
        synchronized (LOCK) {
            pending = new ArrayList<>(JOBS);
            JOBS.clear();
        }
        for (ScanJob job : pending) {
            try {
                job.onCancelled(new IllegalStateException("server stopping"));
            } catch (Throwable ignored) {
                // shutting down anyway
            }
        }
        ExecutorService pool = background;
        background = null;
        if (pool != null) {
            pool.shutdown();
        }
    }

    private static final class NamedThreads implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "locate-plus-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    }
}
