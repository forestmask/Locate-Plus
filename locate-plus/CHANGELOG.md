# Changelog

## 2.0.0

### Breaking

Upgrading from 1.0.0 needs two things: relearn the two renamed commands, and know that a chunk
radius covers a much larger area than the same number used to.

- **A chunk radius now means a reach, not a total.** `20 chunks` covers everything within 20 chunks
  in every direction, which is 1,257 chunks, rather than the 20 nearest ones. This matches how a
  block radius already behaved and how the number reads. Use `0 chunks` for the chunk you are
  standing in and nothing else. Messages say "20 chunks out" so the two cannot be confused.
- **`/analyzechunks` is now `/lp analyze`** and **`/inspect` is now `/lp inspect`**. Everything
  this mod adds beyond the vanilla `/locate` verbs sits under `/lp`. The old names are gone rather
  than deprecated, so anything calling them, a command block or a datapack, needs updating.

### Added

- **`/locate item <id|#tag>`** finds an item wherever it is: chests, barrels, hoppers and modded
  containers, shulker boxes sitting inside other containers, items on the ground, mob equipment,
  item frames, and player inventories and ender chests. Results are grouped by location, biggest
  pile first, and each line says what is holding them.
- **A config file** at `config/locate-plus/config.json`, with `/lp config` to see what is loaded
  and `/lp reload` to re-read it without restarting. 40 settings covering permission level, which
  commands exist, default radii, what `/locate item` searches, export contents, and every limit
  the mod applies. A file with a syntax error is left alone rather than overwritten, out-of-range
  values are clamped, and an older file gains new keys automatically.
- **`/lp visualize <n> chunks|blocks [seconds]`** draws the boundary of the area a scan would
  cover, as a line of particles only you can see. It scans nothing itself. The line is pale where
  chunks are loaded and red where they are not, so you can see in advance which parts a scan would
  miss. It stays anchored where you ran it and lasts as long as you ask, so a boundary further out
  than the game will draw can be walked out to and inspected. Only the edge is worked out, so the
  cost follows the radius rather than the area and any radius the scan commands accept can be
  outlined.
- **`teleport_button_mode`** decides what a `[Teleport]` button in chat does. The default,
  `suggest`, puts the command in the chat box ready to send. A results list puts many buttons a
  line apart, and a mistimed click that moves you across the world is worse than one that types a
  command you can read first. Set it to `run` to teleport on the click.
- **`teleport_button_safe`** decides which teleport that button uses. On by default, so it runs
  `/safetp` and finds somewhere you can stand. Turn it off for a plain `/tp` to the exact block the
  result names.
- **`safetp_vanilla_fallback`** decides what `/safetp` does when there is nowhere safe anywhere
  near the destination, such as a spot sealed inside solid rock. On by default, so the teleport
  goes ahead to the exact position asked for, the same as vanilla `/tp`, and the message says the
  landing was not checked. Turn it off to have the teleport cancelled and stay where you are.
- **`scan_min_y` and `scan_max_y`** set the height band every scan reads. The defaults cover an
  overworld in full, from the bedrock floor at -64 to the build limit at 320, so nothing is missed
  out of the box. Narrowing them is the single biggest saving available on a large scan, since most
  of the cost is the vertical column: a band of 0 to 40 reads 10,496 blocks of a chunk instead of
  36,864. Each dimension clamps the pair to what it actually has, so the nether stops at its own
  floor and ceiling rather than reading empty space.
- **`/lp stop`** cancels every scan in progress and releases the chunks they were holding open.
- **`/lp clear [all|glow|markers]`** removes what the mod is showing. On its own, or with `all`
  or `both`, it clears everything: the region outline, the glow on entities and the block markers.
  Only what this mod put there is touched, so glow from a beacon or another mod is left alone.

### Changed

- The warning before a large scan now says the work is capped per tick, so a big chunk count reads
  as slow rather than as something that will freeze the server. It names the actual budget, which
  `scan_time_budget_ms` can change.
- Scans now say how many chunks they are about to cover, and warn before a large one starts with
  a rough time and a reminder that `/lp stop` exists. A chunk figure is a reach, so the area grows
  with its square: 10 chunks is 317 chunks and finishes in seconds, 100 chunks is 31,417 and takes
  several minutes. The number alone did not make that obvious.
- A command given no radius now covers every chunk the server has loaded, rather than falling back
  to a fixed default. On a normal single-player world that is a few hundred chunks around you, and
  it means `/lp analyze blocks` on its own is a useful thing to type. The area grows with the view
  distance and with how many players are online, so two runs can legitimately differ.
- `/inspect` is now `/lp inspect`.
- Exports list coordinates for the kinds of block you choose, and give a plain count for the rest.
  Four independent switches: `export_modded_blocks` and `export_placed_blocks` are on by default,
  `export_notable_blocks` (ores and rare finds) and `export_natural_blocks` (stone, dirt, water)
  are off. A one-chunk survey went from 1.80 MB to 0.008 MB with nothing useful lost. Counts stay
  exact at every setting.
- The marker on `/locate block` was world wide. It was
  spawned for everyone in range, which announced the position of whatever was found.
- Glowing cannot be shown to one player, so `/locate entity` marking its result is visible to
  anyone nearby. `glow_located_entities` turns the marker off, and the chat reply now says so when
  it is used.
- A result is one chat line rather than two. The coordinate and its teleport button now sit beside
  the tally instead of on a line of their own, which halves the length of every report.
- Chat lists show 60 results before collapsing, up from 15, and the figure is no longer a setting.
  It is not a matter of taste: the client keeps a hundred chat messages, so a longer report scrolls
  its own heading out of view. Sixty covers essentially every scan without truncating, and an
  export has no limit at all.
- Result lines are trimmed to fit the chat width instead of wrapping onto a second line, which
  broke the alignment of every row beneath. The `minecraft:` prefix is dropped, since it is on
  almost every id and says nothing, and coordinates now live on the teleport button's tooltip
  where they already were. A modded id too long even then is shortened, with the full value on
  hover and written out complete in exports.
- A truncated list names the setting that controls it and how many entries were cut.
- Radius ceilings are 16,000 blocks and 1,000 chunks, and both are configurable. The previous
  100,000 block ceiling described a disc of 122 million chunks and would exhaust memory building
  the region before any scanning began.
- Block tallying reuses the previous block's entry rather than looking it up again, worth doing
  because stone and deepslate arrive in long unbroken runs. About 70% off the hottest line in a
  scan; 1.8 million blocks in 199 ms.
- `fabric.mod.json` requires `~1.20.1` rather than `>=1.20.1`. The old range advertised 1.21 and
  later, where the mod cannot load at all.

### Fixed

- A force-loading scan held every chunk it touched until it finished, so peak memory grew with the
  size of the request: thirty thousand chunks is several gigabytes of resident chunk data, and a
  server with four would be killed part way through rather than merely slowed. Chunks are visited
  once and never revisited, so a ticket is now handed back once the scan has moved past it, and
  peak residency is the same whatever the radius. Verified by force-loading 1,257 chunks and
  reading 41.8 million blocks inside a 700 MB heap.
- Chunk-count scans tested entities against a distance instead of against the chunks they actually
  covered, so entity and block results disagreed about the same area. Players were worst affected,
  gathered from the server's player list with nothing bounding them at all: a chunk scan could
  report items in the inventory of a player anywhere in the world. `/locate entity`,
  `/glow` and `/purgeentities` now share one exact test.
- `/safetp` described where the target was using compass directions and per-axis figures, which
  is a lot to act on and easy to get wrong: "4 west and 1 south" is three facts to hold before the
  first step. The block is now outlined instead, so there is nothing to describe and nothing to
  read. Landings went back to being the genuinely nearest safe spot, since a landing no longer has
  to be describable, only close.
- Markers were particles only, and particles are drawn behind whatever is in front of them, so a
  marker on a buried block showed nothing at all. That is the one case a marker exists for. Both
  `/locate block` and teleports now put a glowing outline on the block, a glass cube at the
  block's true size, using the one vanilla effect that draws through terrain. Landings also
  turn you to face the target so the outline is on screen when you arrive. The outline goes away
  the moment the block it is pointing at stops being that block, so mining what you came for
  clears the marker without a command. It also expires after a minute like everything else this
  mod shows, which covers the searches that turn up the wrong thing and are simply walked away
  from; `block_marker_seconds` changes that, and 0 means no limit. `block_marker_fill` changes
  what the marker is made of and refuses blocks that render nothing, and
  `block_marker_colour` sets the outline colour from a hex code or a dye name.
  The marker is visible to anyone nearby, because glowing is entity state rather than something
  sent to one client. The particle marker that used to sit on the same block is gone, along with
  `particle_duration_seconds`: two effects in one place read as clutter rather than emphasis.
- Teleporting to an item in a chest minecart marked nothing. The outline goes on a block, and a
  minecart holds its items at a position whose block is the rail underneath, so the rail was
  outlined and the thing actually carrying the items was left plain. Anything holding items at the
  destination now glows instead, which covers minecarts, chest boats and hoppers on rails. A mob
  that happens to be standing there is left alone, since it is not what the teleport was for.
- `/safetp` stepped sideways to open ground rather than putting you in a composter or a cauldron,
  which vanilla `/tp` enters happily. Whether one can be entered depends on what is in it, so that
  is what decides now: an empty composter is stood in and a filled one is stood on, since the
  compost takes the room. An empty cauldron and a water cauldron are stood in, and a lava one is
  refused outright.
- `/safetp` would not land you on a block you can stand on, such as a composter or a chest. It
  searched outwards from the position given without first asking whether the space just above it
  was clear, so it stepped sideways to open ground instead of putting you on top of the thing you
  named.
- The block scan loop was tidied where it costs most. The origin and the radius flag are read
  once per section rather than once per block, a row whose X already falls outside the radius is
  skipped without touching its sixteen blocks, and the true distance is built from the parts
  already worked out instead of asking the vector again. A matcher for a plain id or a tag now
  answers with a direct comparison rather than through a lambda wrapped in a try block, and the
  palette test is built once per scan rather than once per section. Around a tenth off a scan of
  four million blocks, with identical counts.
- The entity search no longer rebuilds its bounds from the config for every candidate, which is
  most of why a survey of a hundred chunks of entities got noticeably quicker.
- The item search read the height band and the nesting depth from the config for every container
  and every nested stack. Both are read once per scan now.
- The region outline rebuilt its two dust effects, and a chunk position per post, on every redraw.
  A redraw happens four times a second for as long as an outline is showing.
- The block marker tick copied its whole tracking map every tick. The map tolerates being walked
  while entries are removed, so the copy was pure waste.
- `/safetp` searched a cube reaching sixty-four blocks in every direction, which is over two
  million positions when it finds nothing, and it took the best part of a second on a miss. The
  reach is sixteen now: the work grows with the square of it, and a landing further out than that
  is answered better by the column and surface fallbacks anyway. A teleport into loaded terrain
  went from around 300 ms to 3 ms.
- `/safetp` left a glowing outline on whatever it teleported to. A teleport is an action rather
  than a search, and the marker had to be cleared by hand afterwards. Nothing is marked now; the
  commands that find things still mark their own results.
- The teleport button on `/locate biome` and `/locate structure` aimed at the surface heightmap,
  which in a dimension with a ceiling is the bedrock roof. Nothing can stand there, so the
  teleport searched down and landed at the bottom of the world. Under a ceiling the button aims at
  the middle of the dimension instead, which is the open space people build in.
- The list of blocks `/safetp` treats as harmful was rebuilt from what each block does on contact
  rather than from a handful of names. Powder snow in a cauldron, a lit campfire and a lit candle
  were all missed, and pointed dripstone and an end gateway were absent. An unlit campfire and an
  unlit candle are safe to stand on, and a water cauldron is safe to stand in, so those are left
  alone. Blocks that only slow or cushion, honey, slime, cobweb and a lily pad, were never
  harmful and still are not.
- `/safetp` to an unstandable block on a solid pillar fell through the pillar itself and landed in
  the first cave underneath. Walking down the inside of a pillar finds nothing, because rock is not
  standable, and carrying on regardless leaves the player somewhere unrelated. The column is
  followed to its last solid block now and the search steps out at that height, which is what
  somebody means by the bottom of a pillar.
- `/safetp` gave up on a block that cannot be stood on or inside, such as a filled composter,
  powder snow, a lava cauldron or a chest at the height limit. Standing on one means the space
  above it, which does not exist when the block is on the top layer of the world, and the search
  stopped there. Five columns are followed down now, the target's own and the four touching it,
  and the highest floor among them wins. A pillar with an unstandable block on its tip answers
  with the ledge beside it, or with the ground the pillar stands on, however far down that is.
- The last-resort drop stopped after forty-eight blocks and refused anything below that, which is
  shorter than a pillar built from the ground to the height limit. It runs the full height of the
  world now, since by the time it is reached the alternative is refusing outright.
- `/safetp` refused every position on the top layer of a world, reporting no safe location for a
  block that was plainly standable. Standing somewhere needs the space above it to be clear, and
  the block above the ceiling is not a block at all, so the check treated it as solid. A build on
  the height limit is exactly where that bites, and a taller dimension from a datapack makes it
  easy to reach. Space past the ceiling counts as open now.
- `/safetp` could drop you far below the destination when a good spot was a couple of blocks away.
  Only the chunk the destination falls in was made resident, so any position just over a chunk
  boundary read as empty and the search never saw it. With nothing found nearby the column
  fallback took over and followed the drop down, which is how a teleport to a pillar standing on a
  cliff ended up under the cliff. The chunks around the destination are loaded first now, and the
  fallback drop is capped so a floor far below can no longer beat a spot at the side.
- `/safetp` to the top of a tall pillar reported no safe location at all, when the ground the
  pillar stands on was directly below and perfectly standable. The column is now followed down to
  the first floor, as a last resort after the search around the position has come up empty. That
  ordering matters: reaching for it sooner traded a spot a couple of blocks away for a drop of
  eighty.
- `/locate item` counted a player's held and worn items twice: once as equipment on an entity and
  again as part of the player's inventory. A player holding a stack of 64 was reported as 128.
- `/safetp` said "the target is glowing" after every landing that was not exact, including the
  ones with nothing to glow. A marker is only placed on a solid block, so a landing near open air,
  which is what the biome and structure teleport buttons usually give, promised something that was
  never drawn. The wording now follows what the marker actually did.
- `/locate item` results were hard to read at a glance. The summary said `Where: 96 container, 12
  dropped, 64 entity, 128 player`, naming the internal categories; it now reads `96 in containers,
  12 on the ground, 64 on mobs, 128 on players`. The total and the spread are one line rather than
  two, and the stack count has gone from it: `11 apples in 9 stacks` invited the wrong sum, because
  a stack means 64 and a part-full one is not a stack in the sense anybody counts in. It reads
  `11 apples across 9 places` instead. A single result still says how many slots it occupies, which
  is the difference between one full chest slot and an item dotted about a chest. Result lines end
  with the distance so a column can be read down to find the nearest worthwhile pile, and the count
  is written `256x` to separate it from the coordinates.
- `/safetp` offered no completion at all on its coordinates. Suppressing them cleared out a
  confusing pair of candidates, the relative `~ ~ ~` and the numeric position of whatever block was
  under the crosshair, but it also removed the useful one. Only `~ ~ ~` is offered now.
- Raising `max_block_radius` or `max_chunk_radius` in the config did nothing above the shipped
  value. Both were clamped against the default rather than against what the command argument
  parses, so the setting could only ever be lowered. They now reach 64,000 blocks and 4,000
  chunks, which is what the argument itself accepts.
- `/safetp` took coordinates outside the world and tried to search there. Past 30,000,000 blocks
  out no terrain can be generated, so the request failed on the server thread and every chunk it
  asked for failed with it. The destination is now checked the same way `/tp` checks it, and a
  position outside the world is refused with a message saying where the edge is.
- `/inspect` silently hid part of a container. The item list stopped at ten with no indication,
  and the renamed-item line named three of however many there were.
- The "add export" hint after a block-radius scan named a chunk radius, pointing at a different
  area than the one just scanned.
- "... and 1 more block types" read as a plural when one result remained.

## 1.0.0

First release. `/locate block` and `/locate entity` for finding any block or entity rather than
only structures, `/inspect` for everything about a position, `/safetp`, `/glow`,
`/analyzechunks` and `/purgeentities`.
