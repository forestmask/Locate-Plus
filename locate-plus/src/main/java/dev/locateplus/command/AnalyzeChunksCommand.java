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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.ScanResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.ChatReporter;
import dev.locateplus.report.ExportWriter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.BlockMatcher;
import dev.locateplus.scan.BlockScanJob;
import dev.locateplus.scan.EntityScanJob;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** {@code /lp analyze blocks|entities|both [radius] [export] [forceload]}. */
public final class AnalyzeChunksCommand {

    private AnalyzeChunksCommand() {
    }

    /** The {@code analyze} subtree, attached to {@code /lp} by {@link HelpCommand}. */
    static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("analyze")
                .then(mode("blocks", AnalyzeChunksCommand::blocks))
                .then(mode("entities", AnalyzeChunksCommand::entities))
                .then(mode("both", AnalyzeChunksCommand::both));
    }

    /**
     * Builds one mode's subtree: {@code <n> chunks|blocks [export] [forceload]}.
     *
     * {@link RadiusArg#read} does the range validation, so an oversized radius is handled here
     * exactly as it is in every other command.
     */
    private static LiteralArgumentBuilder<ServerCommandSource> mode(String name, Runner runner) {
        LiteralArgumentBuilder<ServerCommandSource> root = literal(name)
                .executes(ctx -> runner.run(ctx,
                        Radius.ofLoaded(), false, false));

        RequiredArgumentBuilder<ServerCommandSource, Integer> number =
                argument("radius", IntegerArgumentType.integer(0, Radius.hardMaxBlocks()));

        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");
            number.then(literal(word)
                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), false, false))

                    // ...
                    .then(literal("forceload")
                            .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), false, true))
                            .then(literal("export")
                                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, true))))

                    // ...
                    .then(literal("export")
                            .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, false))
                            .then(literal("forceload")
                                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, true)))));
        }
        return root.then(number);
    }

    @FunctionalInterface
    private interface Runner {
        int run(CommandContext<ServerCommandSource> ctx, Radius radius, boolean export,
                boolean forceload) throws CommandSyntaxException;
    }

    // ---- modes --------------------------------------------------------------------------------

    private static int blocks(CommandContext<ServerCommandSource> ctx, Radius radius,
                              boolean export, boolean forceload) {
        return runBlocks(ctx.getSource(), radius, export, forceload, null);
    }

    private static int entities(CommandContext<ServerCommandSource> ctx, Radius radius,
                                boolean export, boolean forceload) {
        return runEntities(ctx.getSource(), radius, export, forceload, null);
    }

    /** Both scans, chained. */
    private static int both(CommandContext<ServerCommandSource> ctx, Radius radius,
                            boolean export, boolean forceload) {
        ServerCommandSource source = ctx.getSource();
        Msg.heading(source, "Analysis");
        return runBlocks(source, radius, export, forceload,
                () -> runEntities(source, radius, export, forceload, null, " (2 of 2)"));
    }

    // ---- implementations ----------------------------------------------------------------------

    private static int runBlocks(ServerCommandSource source, Radius radius, boolean export,
                                 boolean forceload, Runnable then) {
        String suffix = then != null ? " (1 of 2)" : "";
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        ScanRegion region = chunkRegion(originBlock, radius, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ScanResult result = new ScanResult(ScanResult.Kind.BLOCKS,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                radius.chunks(), 0, region.boundsDescription(), forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());
        result.setRequestedRadius(typed(radius));
        Msg.info(source, "Analyzing blocks in " + describe(radius) + " ("
                + Msg.count(region.totalChunks(), "chunk") + ")...");

        LPScheduler.submit(new BlockScanJob(world, region, BlockMatcher.allNonAir(), result,
                export, 0, origin, false,
                done -> {
                    ChatReporter.blockAnalysis(source, done, export, suffix);
                    if (export) {
                        exportAsync(source, done);
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                    if (then != null) {
                        then.run();
                    }
                },
                error -> Chat.error(source, "Block analysis failed: " + error.getMessage())));
        return 1;
    }

    private static int runEntities(ServerCommandSource source, Radius radius, boolean export,
                                   boolean forceload, Runnable then) {
        return runEntities(source, radius, export, forceload, then, "");
    }

    private static int runEntities(ServerCommandSource source, Radius radius, boolean export,
                                   boolean forceload, Runnable then, String suffix) {
        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        BlockPos originBlock = BlockPos.ofFloored(origin);

        ScanRegion region = chunkRegion(originBlock, radius, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ScanResult result = new ScanResult(ScanResult.Kind.ENTITIES,
                world.getRegistryKey().getValue().toString(), originBlock, origin,
                radius.chunks(), 0, region.boundsDescription(), forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());
        result.setRequestedRadius(typed(radius));
        Msg.info(source, "Analyzing entities in " + describe(radius) + " ("
                + Msg.count(region.totalChunks(), "chunk") + ")...");

        TargetSpec everything = new TargetSpec() {
            @Override
            public String label() {
                return "all entities";
            }

            @Override
            public EntityQuery bind(ServerCommandSource src) {
                return EntityQuery.all();
            }
        };

        LPScheduler.submit(new EntityScanJob(world, source, region, everything, result, 0, origin,
                done -> {
                    ChatReporter.entityAnalysis(source, done, export, suffix);
                    if (export) {
                        exportAsync(source, done);
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                    if (then != null) {
                        then.run();
                    }
                },
                error -> Chat.error(source, "Entity analysis failed: " + error.getMessage())));
        return 1;
    }

    /**
     * Both units describe a reach, so both produce a disc; only the number differs.
     *
     * Kept as one call rather than branching on the unit, so this command cannot drift from what
     * {@link ScanRegion#forRadius} builds for every other command.
     */
    private static ScanRegion chunkRegion(BlockPos origin, Radius radius, boolean forceload,
                                          ServerWorld world) {
        return ScanRegion.forRadius(origin, radius, forceload, world);
    }

    /** The radius as typed, for echoing back in advice: {@code "4 chunks"}, {@code "64 blocks"}. */
    private static String typed(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? radius.chunks() + " chunks"
                : radius.blocks() + " blocks";
    }

    /** "4 chunks" or "64 blocks (4 chunks)", whichever the player asked for. */
    private static String describe(Radius radius) {
        return radius.isLoadedArea() ? "the loaded area"
                : radius.typedUnit() == Radius.Unit.CHUNKS
                        ? Msg.chunkReach(radius.chunks())
                        : radius.describeBlocksFirst();
    }

    /**
     * Write both export files on the background pool.
     *
     * Chat feedback is safe from another thread because {@code sendFeedback} only enqueues a
     * packet.
     */
    private static void exportAsync(ServerCommandSource source, ScanResult result) {
        Msg.info(source, "Writing export in the background...");
        LPScheduler.background().execute(() -> ExportWriter.writeAsyncLogged(result,
                paths -> {
                    Msg.success(source, "Export written.");
                    Msg.field(source, "File", String.valueOf(paths[0].getFileName()));
                    Msg.note(source, "in " + Msg.exportPath());
                },
                error -> Msg.error(source, "Export failed: " + error.getMessage())));
    }
}
