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

import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.ScanJob;
import dev.locateplus.model.ItemHit;
import dev.locateplus.model.ItemResult;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;
import java.util.function.Consumer;

/**
 * Finds every copy of an item within range, wherever it is being kept.
 *
 * Each scope can be switched off in the config, and each is skipped entirely rather than filtered
 * afterwards, so turning one off makes the scan cheaper as well as quieter.
 */
public final class ItemScanJob implements ScanJob {

    private final ServerWorld world;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final ItemMatcher matcher;
    private final ItemResult result;
    private final int blockRadius;
    private final Vec3d origin;
    private final Consumer<ItemResult> onComplete;
    private final Consumer<Throwable> onError;

    private final List<ChunkPos> chunks;
    private int chunkIndex;
    private boolean entitiesDone;

    private final long startNanos = System.nanoTime();

    /** Read once per scan. A nested container can recurse several levels for every stack. */
    private final int nestingDepth = LPConfig.get().itemNestingDepth();
    private boolean finished;

    public ItemScanJob(ServerWorld world, ScanRegion region, ItemMatcher matcher,
                       ItemResult result, int blockRadius, Vec3d origin,
                       Consumer<ItemResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.matcher = matcher;
        this.result = result;
        this.blockRadius = blockRadius;
        this.origin = origin;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
    }

    @Override
    public String describe() {
        return "item scan (" + matcher.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            LPConfig config = LPConfig.get();

            // Phase 1: container blocks, chunk by chunk.
            while (chunkIndex < chunks.size()) {
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                ChunkPos pos = chunks.get(chunkIndex++);
                WorldChunk chunk = access.acquire(pos);
                if (chunk == null) {
                    continue;
                }
                result.addChunkScanned();
                if (config.searchContainers()) {
                    scanContainers(chunk, config);
                }
            }

            // Phase 2: everything that moves.
            if (!entitiesDone) {
                entitiesDone = true;
                scanEntities(config);
            }

            complete();
            return true;
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    // ---- containers ---------------------------------------------------------------------------

    private void scanContainers(WorldChunk chunk, LPConfig config) {
        // Read once. A chunk can hold hundreds of block entities and these never change mid-scan.
        final int bandMinY = config.scanMinY(world);
        final int bandMaxY = config.scanMaxY(world);
        final boolean wantNested = config.searchInsideShulkerBoxes();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof Inventory inv)) {
                continue;
            }
            BlockPos pos = be.getPos();
            if (outOfRange(pos.getX() + 0.5, pos.getZ() + 0.5)) {
                continue;
            }
            // Containers come from the chunk's block entity map rather than from a search box,
            // so the Y band has to be applied to them directly.
            if (pos.getY() < bandMinY || pos.getY() > bandMaxY) {
                continue;
            }
            String holder = containerName(be);
            double distance = distanceTo(pos);
            int size = inv.size();

            for (int slot = 0; slot < size; slot++) {
                ItemStack stack;
                try {
                    stack = inv.getStack(slot);
                } catch (Throwable t) {
                    continue; // a modded inventory with an odd slot count must not abort the scan
                }
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (matcher.matches(stack)) {
                    record(stack, pos, distance, ItemHit.Source.CONTAINER, holder);
                }
                if (wantNested) {
                    scanNested(stack, pos, distance, holder, 1);
                }
            }
        }
    }

    /** Read the contents of a container item, a shulker box being the obvious case. */
    private void scanNested(ItemStack container, BlockPos pos, double distance,
                            String outerHolder, int depth) {
        if (depth > nestingDepth) {
            return;
        }
        if (!(container.getItem() instanceof BlockItem)) {
            return;
        }
        NbtCompound nbt = container.getNbt();
        if (nbt == null || !nbt.contains("BlockEntityTag", NbtElement.COMPOUND_TYPE)) {
            return;
        }
        NbtCompound blockTag = nbt.getCompound("BlockEntityTag");
        if (!blockTag.contains("Items", NbtElement.LIST_TYPE)) {
            return;
        }

        String containerName = Registries.ITEM.getId(container.getItem()).getPath();
        String holder = containerName + " in " + outerHolder;
        NbtList items = blockTag.getList("Items", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < items.size(); i++) {
            ItemStack inner;
            try {
                inner = ItemStack.fromNbt(items.getCompound(i));
            } catch (Throwable t) {
                continue;
            }
            if (inner == null || inner.isEmpty()) {
                continue;
            }
            if (matcher.matches(inner)) {
                record(inner, pos, distance, ItemHit.Source.NESTED, holder);
            }
            scanNested(inner, pos, distance, holder, depth + 1);
        }
    }

    // ---- entities -----------------------------------------------------------------------------

    private void scanEntities(LPConfig config) {
        boolean wantDropped = config.searchDroppedItems();
        boolean wantEntities = config.searchEntityInventories();
        boolean wantPlayers = config.searchPlayerInventories();

        if (wantDropped || wantEntities) {
            Box box = searchBox();
            List<Entity> found = world.getOtherEntities(null, box, e -> true);
            for (Entity entity : found) {
                if (outOfRange(entity.getX(), entity.getZ())) {
                    continue;
                }
                if (BlockBeacon.isMarker(entity)) {
                    continue; // this mod's own block marker holds no items
                }
                if (entity instanceof ItemEntity item) {
                    if (wantDropped) {
                        considerDropped(item);
                    }
                } else if (entity instanceof ServerPlayerEntity) {
                    // Players are handled below, where the whole inventory is read at once.
                    // Equipment is part of that inventory, so counting it here as well would
                    // report a held stack twice and inflate the total.
                    continue;
                } else if (wantEntities) {
                    considerEntityInventory(entity, config);
                }
            }
        }

        if (wantPlayers) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (outOfRange(player.getX(), player.getZ())) {
                    continue;
                }
                considerPlayer(player, config);
            }
        }
    }

    private void considerDropped(ItemEntity item) {
        ItemStack stack = item.getStack();
        if (!matcher.matches(stack)) {
            return;
        }
        BlockPos pos = item.getBlockPos();
        record(stack, pos, distanceTo(item.getPos()), ItemHit.Source.DROPPED, "the ground");
    }

    /** Equipment on any entity, plus anything an entity type carries in its own inventory. */
    private void considerEntityInventory(Entity entity, LPConfig config) {
        BlockPos pos = entity.getBlockPos();
        double distance = distanceTo(entity.getPos());
        String holder = entityName(entity);

        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack;
                try {
                    stack = living.getEquippedStack(slot);
                } catch (Throwable t) {
                    continue;
                }
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (matcher.matches(stack)) {
                    record(stack, pos, distance, ItemHit.Source.ENTITY,
                            holder + " (" + slot.getName() + ")");
                }
                if (config.searchInsideShulkerBoxes()) {
                    scanNested(stack, pos, distance, holder, 1);
                }
            }
        }

        if (entity instanceof ItemFrameEntity frame) {
            ItemStack stack = frame.getHeldItemStack();
            if (matcher.matches(stack)) {
                record(stack, pos, distance, ItemHit.Source.ENTITY, "item frame");
            }
        }

        if (entity instanceof Inventory inv && !(entity instanceof ServerPlayerEntity)) {
            scanInventory(inv, pos, distance, holder, ItemHit.Source.ENTITY, config);
        }
    }

    private void considerPlayer(ServerPlayerEntity player, LPConfig config) {
        BlockPos pos = player.getBlockPos();
        double distance = distanceTo(player.getPos());
        String name = player.getGameProfile().getName();

        PlayerInventory inv = player.getInventory();
        scanInventory(inv, pos, distance, name, ItemHit.Source.PLAYER, config);

        try {
            scanInventory(player.getEnderChestInventory(), pos, distance,
                    name + " (ender chest)", ItemHit.Source.PLAYER, config);
        } catch (Throwable ignored) {
            // the ender chest is optional detail, never worth failing a scan over
        }
    }

    private void scanInventory(Inventory inv, BlockPos pos, double distance, String holder,
                               ItemHit.Source source, LPConfig config) {
        int size = inv.size();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack;
            try {
                stack = inv.getStack(slot);
            } catch (Throwable t) {
                continue;
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (matcher.matches(stack)) {
                record(stack, pos, distance, source, holder);
            }
            if (config.searchInsideShulkerBoxes()) {
                scanNested(stack, pos, distance, holder, 1);
            }
        }
    }

    // ---- shared -------------------------------------------------------------------------------

    private void record(ItemStack stack, BlockPos pos, double distance,
                        ItemHit.Source source, String holder) {
        String customName = stack.hasCustomName() ? stack.getName().getString() : null;
        boolean enchanted;
        try {
            enchanted = stack.hasEnchantments();
        } catch (Throwable t) {
            enchanted = false;
        }
        result.add(ItemHit.of(Registries.ITEM.getId(stack.getItem()), stack.getCount(),
                pos.toImmutable(), distance, source, holder, customName, enchanted));
    }

    /**
     * Whether a position lies outside what this scan covers.
     *
     * This matters most for players, who come from {@code world.getPlayers()} rather than from the
     * chunk list or the search box, and so have nothing else constraining them.
     */
    private boolean outOfRange(double x, double z) {
        if (blockRadius > 0) {
            double dx = origin.x - x;
            double dz = origin.z - z;
            return dx * dx + dz * dz > (double) blockRadius * blockRadius;
        }
        return !region.containsPosition(x, z);
    }

    private double distanceTo(BlockPos pos) {
        return Math.sqrt(origin.squaredDistanceTo(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private double distanceTo(Vec3d pos) {
        return Math.sqrt(origin.squaredDistanceTo(pos));
    }

    /** A coarse box used only to gather candidate entities cheaply. */
    private Box searchBox() {
        double r = blockRadius > 0 ? blockRadius : region.blockExtent() + 16;
        return new Box(
                origin.x - r, dev.locateplus.core.LPConfig.get().scanMinY(world), origin.z - r,
                origin.x + r, dev.locateplus.core.LPConfig.get().scanMaxY(world) + 1.0,
                origin.z + r);
    }

    /** A container's custom name if it has one, otherwise its block id without the namespace. */
    private static String containerName(BlockEntity be) {
        if (be instanceof LockableContainerBlockEntity lockable) {
            try {
                if (lockable.getCustomName() != null) {
                    return lockable.getCustomName().getString();
                }
            } catch (Throwable ignored) {
                // fall through to the block id
            }
        }
        try {
            return Registries.BLOCK.getId(be.getCachedState().getBlock()).getPath();
        } catch (Throwable t) {
            return "container";
        }
    }

    private static String entityName(Entity entity) {
        if (entity.getCustomName() != null) {
            return entity.getCustomName().getString();
        }
        if (entity instanceof ArmorStandEntity) {
            return "armour stand";
        }
        return Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
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
            LPLog.error("Item scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Item scan failed", t);
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
