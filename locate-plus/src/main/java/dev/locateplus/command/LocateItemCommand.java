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

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.model.ItemResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.ItemExportWriter;
import dev.locateplus.report.ItemReporter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.ItemMatcher;
import dev.locateplus.scan.ItemScanJob;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /locate item <item_id|#tag> [<n> chunks|blocks] [export] [forceload]}.
 *
 * Like the rest of the mod this uses only vanilla argument types, so the command tree still
 * serialises correctly to a vanilla client at login.
 */
public final class LocateItemCommand {

    private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
            id -> Text.literal("No item or item tag matching '" + id + "'"));

    private LocateItemCommand() {
    }

    /** The {@code item} subtree, ready to attach to {@code /locate}. */
    static LiteralArgumentBuilder<ServerCommandSource> build() {
        LiteralArgumentBuilder<ServerCommandSource> item = literal("item");

        RequiredArgumentBuilder<ServerCommandSource,
                RegistryPredicateArgumentType.RegistryPredicate<Item>> target =
                argument("item", RegistryPredicateArgumentType.registryPredicate(RegistryKeys.ITEM))
                        .executes(ctx -> run(ctx, Radius.ofLoaded(), false, false));

        RequiredArgumentBuilder<ServerCommandSource, Integer> number =
                argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType
                        .integer(0, Radius.hardMaxBlocks()));

        // export and forceload may be given in either order, matching /lp analyze.
        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");
            number.then(literal(word)
                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), false, false))

                    .then(literal("forceload")
                            .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), false, true))
                            .then(literal("export")
                                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, true))))

                    .then(literal("export")
                            .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, false))
                            .then(literal("forceload")
                                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, true)))));
        }

        return item.then(target.then(number));
    }

    private static int run(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                           boolean export, boolean forceload) throws CommandSyntaxException {
        return scan(ctx, radiusSpec, export, forceload, toItemMatcher(
                RegistryPredicateArgumentType.getPredicate(ctx, "item", RegistryKeys.ITEM,
                        UNKNOWN_ITEM)));
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's item matcher. */
    private static ItemMatcher toItemMatcher(
            RegistryPredicateArgumentType.RegistryPredicate<Item> predicate)
            throws CommandSyntaxException {
        com.mojang.datafixers.util.Either<net.minecraft.registry.RegistryKey<Item>, TagKey<Item>>
                key = predicate.getKey();
        boolean missing = key.map(
                k -> !Registries.ITEM.contains(k),
                tag -> Registries.ITEM.getEntryList(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_ITEM.create(key.map(k -> k.getValue().toString(),
                    tag -> "#" + tag.id()));
        }
        return key.map(
                itemKey -> ItemMatcher.ofItem(itemKey.getValue().toString(),
                        Registries.ITEM.get(itemKey)),
                ItemMatcher::ofTag);
    }

    private static int scan(CommandContext<ServerCommandSource> ctx, Radius radiusSpec,
                            boolean export, boolean forceload, ItemMatcher matcher)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ItemResult result = new ItemResult(matcher.label(),
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                region.chunkRadius(), radius, region.boundsDescription(), forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());

        Msg.info(source, "Searching for " + matcher.label() + " within "
                + describeRequest(radiusSpec) + " (" + Msg.count(region.totalChunks(), "chunk")
                + ")...");

        LPScheduler.submit(new ItemScanJob(world, region, matcher, result, radius, origin,
                done -> {
                    ItemReporter.report(source, done, radiusSpec, export);
                    if (export) {
                        exportAsync(source, done);
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                },
                error -> Chat.error(source, "Item search failed: " + error.getMessage())));
        return 1;
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's item matcher. */

    private static String describeRequest(Radius radius) {
        return radius.isLoadedArea() ? "the loaded area"
                : radius.typedUnit() == Radius.Unit.CHUNKS
                        ? Msg.chunkReach(radius.chunks())
                        : radius.describeBlocksFirst();
    }

    private static void exportAsync(ServerCommandSource source, ItemResult result) {
        Msg.info(source, "Writing export in the background...");
        LPScheduler.background().execute(() -> ItemExportWriter.writeAsyncLogged(result,
                path -> {
                    Msg.success(source, "Export written.");
                    Msg.field(source, "File", String.valueOf(path.getFileName()));
                    Msg.note(source, "in " + Msg.exportPath());
                },
                error -> Msg.error(source, "Export failed: " + error.getMessage())));
    }
}
