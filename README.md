# Locate Plus

Server-side scanning and inspection commands for **Minecraft 1.20.1 / Fabric**.

Vanilla `/locate` only finds structures and biomes. This finds anything: any block, any entity,
modded or not.

Requires Fabric API. No client install needed.

## Commands

| Command | What it does |
|---|---|
| `/lp` | In-game command list |
| `/locate block <id\|#tag> <n> chunks\|blocks` | Nearest matching block |
| `/locate entity <id\|#tag> <n> chunks\|blocks` | Nearest matching entity |
| `/locate biome\|structure\|poi <id\|#tag>` | Vanilla searches, with a safe-teleport button |
| `/inspect <x> <y> <z>` | Redstone, light, mob spawning, crops, containers, entity data |
| `/safetp [targets] <destination>` | Teleport somewhere you can actually stand |
| `/glow <target> <n> chunks\|blocks` | Outline entities through terrain |
| `/analyzechunks blocks\|entities\|both <n> chunks\|blocks [export]` | Survey an area |
| `/purgeentities <target> <n> chunks\|blocks [export]` | Remove entities, with a log |

Needs permission level 2. Add `forceload` to most commands to include unloaded chunks.

## Good to know

**The radius unit is required.** Write `64 blocks` or `4 chunks`, never just `64`. Plural only.
Both units are echoed back so nothing is ambiguous.

**Chunk counts are literal.** `4 chunks` scans exactly four chunks, starting with the one you are
standing in and spreading outwards. A block radius covers whatever chunks that distance reaches.

**Radius is horizontal.** The full height of the world is always included, so flying high above
something still finds it.

**Targets accept ids, tags and selectors.** `minecraft:zombie`, `#minecraft:skeletons`, `@e`,
`@a`, `@e[type=minecraft:creeper]`. Modded ids work in any namespace. Searching a tag lists each
matching type separately with its own count and teleport button.

**Nothing is force-loaded unless you ask.** Without `forceload` a scan only reads chunks already
in memory and never generates terrain. Skipped chunks are counted and reported.

**No radius limit.** Scan a hundred chunks if you want. Large scans warn about the cost and then
run. Scans are sliced across ticks, so they do not freeze the server.

**Teleports tell you where you landed**, for example `(3 blocks below the target)`. Slabs, stairs,
paths and shallow water all count as somewhere you can stand. The `[Teleport]` buttons in chat run
`/safetp`, so they get the same safety checks.

**Exports** are written to `config/locate-plus/exports/` as a text file, on a background thread.
Chat only shows the top 15 types; use `export` for every coordinate.

**Players are never removed** by `/purgeentities`, however the target is written. It also produces
no drops, no XP and no death messages.

**Glow and particles last one minute.** `/glow` is entities only.

## Defaults

| | |
|---|---|
| `/locate` radius | 64 blocks |
| `/analyzechunks` radius | 4 chunks |
| Permission level | 2 (OP, or cheats in singleplayer) |

## Future

It's just me making this in my free time. 

I want to port it to other versions and loaders eventually, but it'll happen when it happens. Thanks for checking it out.
