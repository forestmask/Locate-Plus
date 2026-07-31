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
package dev.locateplus.scan;

import dev.locateplus.core.LPLog;
import dev.locateplus.core.ScanJob;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Collects entities matching a {@link TargetSpec}.
 *
 * <h2>Why this does not use a plain {@code world.getEntitiesByClass(box, ...)} call</h2>
 *
 * <p>Three details decide whether {@code /locate entity} actually finds things:</p>
 *
 * <ol>
 *   <li><b>Force-loading happens before the selector is evaluated.</b> Chunks are acquired first,
 *       and only then is {@link TargetSpec#bind} called. Binding earlier, which is what happens
 *       if the selector is resolved at argument-parse time, evaluates it against chunks that are
 *       not in memory yet, so it matches nothing and vanilla reports "No entity was found".</li>
 *
 *   <li><b>The player list is authoritative for players.</b> Player entities are tracked
 *       separately from the chunk entity storage, so a player-only query walks
 *       {@code world.getPlayers()} directly and cannot be affected by chunk residency at all.</li>
 *
 *   <li><b>Zero matches is a result, not an error.</b> The job always completes and hands back a
 *       {@link ScanResult}; the command layer then prints "0 matches" in the normal format.</li>
 * </ol>
 */
public final class EntityScanJob implements ScanJob {

    private final ServerWorld world;
    private final ServerCommandSource source;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final TargetSpec spec;
    private final ScanResult result;
    private final int blockRadius;
    private final Vec3d origin;
    private final Consumer<ScanResult> onComplete;
    private final Consumer<Throwable> onError;

    private final List<ChunkPos> chunks;
    private int chunkIndex;
    private final Set<UUID> seen = new HashSet<>();
    private final long startNanos = System.nanoTime();

    private EntityQuery query;
    private boolean bound;
    private boolean finished;

    public EntityScanJob(ServerWorld world, ServerCommandSource source, ScanRegion region,
                         TargetSpec spec, ScanResult result, int blockRadius, Vec3d origin,
                         Consumer<ScanResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.source = source;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.spec = spec;
        this.result = result;
        this.blockRadius = blockRadius;
        this.origin = origin;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
    }

    @Override
    public String describe() {
        return "entity scan (" + spec.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            // Phase 1: make the chunks resident (only does work when forceload was requested).
            while (chunkIndex < chunks.size()) {
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                ChunkPos pos = chunks.get(chunkIndex++);
                if (access.acquire(pos) != null) {
                    result.addChunkScanned();
                }
            }

            // Phase 2: bind the selector now that the world is in the state the player expects.
            if (!bound) {
                query = spec.bind(source);
                bound = true;
            }

            collect();
            complete();
            return true;
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    private void collect() {
        if (spec.playersOnly()) {
            collectPlayers();
        } else {
            collectFromWorld();
            // Players live outside chunk entity storage; sweep them in too unless the query is
            // structurally incapable of matching one.
            collectPlayers();
        }
    }

    private void collectPlayers() {
        for (ServerPlayerEntity player : world.getPlayers()) {
            consider(player);
        }
    }

    private void collectFromWorld() {
        Box box = searchBox();
        // getOtherEntities(null, box) returns every entity type, including item entities and
        // entities whose class does not extend LivingEntity.
        List<Entity> found = world.getOtherEntities(null, box, e -> true);
        for (Entity entity : found) {
            consider(entity);
        }
    }

    private Box searchBox() {
        double r = blockRadius > 0
                ? blockRadius
                : (region.chunkRadius() + 1) * 16.0;
        return new Box(
                origin.x - r, world.getBottomY(), origin.z - r,
                origin.x + r, world.getTopY(), origin.z + r);
    }

    private void consider(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (!seen.add(entity.getUuid())) {
            return; // already counted (players are swept twice by design)
        }
        if (query != null && !query.matches(entity)) {
            return;
        }
        if (blockRadius > 0) {
            // Horizontal distance only. A radius is what you would draw on a map; using true 3D
            // distance meant flying above a mob pushed it out of range.
            double dx = origin.x - entity.getX();
            double dz = origin.z - entity.getZ();
            if (dx * dx + dz * dz > (double) blockRadius * blockRadius) {
                return;
            }
        }
        result.addEntity(EntityRecord.of(entity, origin));
    }


    private void complete() {
        if (finished) {
            return;
        }
        finished = true;
        result.addChunksSkipped(access.skippedUnloaded());
        result.finish((System.nanoTime() - startNanos) / 1_000_000L);
        access.close();
        try {
            onComplete.accept(result);
        } catch (Throwable t) {
            LPLog.error("Entity scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Entity scan failed", t);
        try {
            onError.accept(t);
        } catch (Throwable ignored) {
            // reporting must not throw
        }
    }

    @Override
    public void onCancelled(Throwable cause) {
        if (!finished) {
            finished = true;
            access.close();
        }
    }
}
