# Locate Plus

Server-side scanning, inspection and safe-teleport commands for **Minecraft 1.20.1 / Fabric**.

Vanilla `/locate` only finds structures and biomes. This finds anything: any block, any entity,
modded or not.

No client mod required. Players joining a server with it installed do not need anything.

---

## Commands

| Command | What it does |
|---|---|
| `/lp` | In-game command list |
| `/locate block <id\|#tag> [n chunks\|blocks] [forceload]` | Nearest matching block |
| `/locate entity <id\|#tag> [n chunks\|blocks] [forceload]` | Nearest matching entity |
| `/locate biome\|structure\|poi <id\|#tag>` | Vanilla searches with a safe-teleport button |
| `/inspect <x> <y> <z> [forceload]` | Everything about one position |
| `/safetp [targets] <destination>` | Teleport somewhere you can stand |
| `/glow <target> <n> chunks\|blocks` | Outline entities through terrain |
| `/analyzechunks blocks\|entities\|both <n> chunks\|blocks [export] [forceload]` | Survey an area |
| `/purgeentities <target> <n> chunks\|blocks [export]` | Remove entities, with a log |

All commands need permission level 2 (OP, or cheats in singleplayer).

See [GUIDE.md](GUIDE.md) for full documentation with examples.

---

## Building

Requires **JDK 17**.

```
./gradlew build
```

The jar lands in `build/libs/`. On Windows use `gradlew.bat build`.

To run the tests only:

```
./gradlew test
```

---

## Project layout

```
src/main/java/dev/locateplus/
├── command/    Brigadier registration, one file per command
├── core/       scheduler, tick tasks, constants, logging
├── entity/     entity target resolution
├── inspect/    the /inspect sections
├── model/      scan results and records
├── platform/   loader abstraction
├── report/     chat formatting and file export
├── scan/       scan jobs, chunk access, highlights
├── teleport/   safe-location search
└── util/       small helpers
```

The mod loader is only referenced in two files, `fabric/FabricPlatform.java` and
`fabric/LocatePlusFabric.java`. Everything else is plain Minecraft and JDK code behind a
`Platform` interface, which is what makes a Forge or NeoForge port practical later.

See [PORTING.md](PORTING.md) for notes on other loaders and Minecraft versions, including a
"where to change things" table.

---

## Testing

[TESTING.md](TESTING.md) is a manual checklist covering every command and error path.

The automated tests cover argument parsing, chunk region maths, the export memory budget, and
command-tree serialisability. That last one matters: an unregistered argument type will kick every
joining player with "Invalid player data", and the console cannot catch it.

---

## Contributing

Issues and pull requests are welcome.

If you add a command, keep the argument types vanilla. Custom `ArgumentType` classes have to be
registered on the client as well, and an unregistered one breaks login. `CommandTreeSerializationTest`
enforces this.

---

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Anyone may use, modify and redistribute this mod, including in modpacks. Any fork or addon built
on it must also be open source under the same license.
