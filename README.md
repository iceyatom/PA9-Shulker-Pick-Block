# ShulkerPickBlock

A **client-side Fabric mod** for **Minecraft Java Edition 26.1.2** that extends vanilla **pick block**
(middle-click) to reach inside the shulker boxes in your inventory.

When you pick-block a placed block and the matching item isn't in your hotbar or main inventory,
ShulkerPickBlock scans the shulker boxes you're carrying (including your off-hand), pulls the item
out, and puts it in your hand — **no need to place the shulker box down first.** It also hooks
**Litematica's Easy Place** so shulker-stored blocks are supplied automatically while you build.

> Status: **built and verified in-game on Minecraft 26.1.2** (Fabric Loader 0.19.2, Fabric API
> 0.151.0+26.1.2). Vanilla pick-block-from-shulker works in single-player survival and creative, and
> the **Litematica Easy Place** integration is confirmed working against Litematica
> `0.27.4` (the sakura-ryoko 26.x fork). Builds with **Mojang official mappings** — see
> [Building](#building) and `CLAUDE.md`.

## Features
- **Pick block from inventory shulker boxes** — main inventory (slots 0–35) and the off-hand.
- **Reads/writes via the Data Components API** (`minecraft:container`) — no legacy NBT, no opening
  the box in the world.
- **Smart source selection** — prefers the box with the largest stack of the target item so you
  don't fragment stacks (configurable).
- **Never destroys your held item** — if the destination hotbar slot is occupied (e.g. your
  inventory is completely full), the previously-held item is swapped into the box's just-vacated
  slot instead of being overwritten, with an on-screen notice. If the held item is itself a shulker
  box (which can't be nested), the pick is declined rather than risk losing it.
- **Configurable hotbar placement** — `VANILLA`, `CURRENT_SLOT`, or `LRU` (least-recently-used).
- **Litematica Easy Place compatibility** — optional; auto-detected; supplies consecutive block
  types from different boxes with no extra clicks. Safely disables itself if Litematica's internals
  don't match (never crashes).
- **HUD notification** when an item is pulled (toggle + duration configurable).
- **Runtime-reloadable config**: `/shulkerpickblock reload`.
- Works without Litematica or Mod Menu; suggests them but doesn't require them.

## Requirements
| | Version |
|---|---|
| Minecraft | Java Edition **26.1.2** |
| Fabric Loader | **0.18.4+** |
| Fabric API | **0.150.0+26.1.2** |
| Java | **25** (required by MC 26.1.x) |
| Optional | Litematica (Easy Place), Mod Menu |

## Installation
1. Install **Fabric Loader 0.18.4** for Minecraft 26.1.2 via the Fabric installer.
2. Put **Fabric API 0.150.0+26.1.2** in `.minecraft/mods/`.
3. Put **`shulker-pick-block-1.0.0.jar`** in `.minecraft/mods/`.
4. *(Optional)* Add a compatible **Litematica** build for Easy Place integration.
5. Launch the `fabric-loader-26.1.2` profile.

## Usage
Middle-click (pick block) a block as usual. If the item isn't already in your inventory but is
inside a shulker box you're carrying, it's pulled into your hand automatically. With Litematica's
Easy Place on, just hold the place button — required blocks are supplied from your boxes.

### Commands
- `/shulkerpickblock reload` — reload the config file without restarting.
- `/shulkerpickblock status` — print the current effective config.

## Configuration
File: `.minecraft/config/shulkerpickblock.toml` (created on first run).

| Option | Type | Default | Description |
|---|---|---|---|
| `enabled` | bool | `true` | Master toggle. |
| `scan_offhand` | bool | `true` | Include the off-hand slot when searching. |
| `prefer_largest_stack` | bool | `true` | Prefer the box with the most of the target item. |
| `hotbar_slot_strategy` | enum | `VANILLA` | `VANILLA` \| `CURRENT_SLOT` \| `LRU`. |
| `show_hud_message` | bool | `true` | Show a HUD note when an item is pulled. |
| `hud_message_duration_ticks` | int | `40` | HUD duration in ticks (10–200). |
| `litematica_compat` | bool | `true` | Enable Easy Place integration (ignored if Litematica absent). |
| `debug_logging` | bool | `false` | Verbose scan diagnostics. Dev use only. |

## Important limitation — remote (vanilla) servers
This mod is **fully authoritative in single-player and on a LAN world you host** — in both
**survival and creative**. The extraction is mirrored onto the integrated server's inventory (it
runs in the same process), so the item really moves and stays put rather than reverting.

**On a *remote* vanilla server it cannot truly sync** in survival: Minecraft provides no packet to
modify an item-form shulker box's contents (only a *placed* box has a server-side container), so the
extraction is client-side prediction only and the server will revert it. (Remote **creative** still
works, via creative-mode slot packets.) This is a Minecraft protocol limitation, not a bug — a real
fix for remote survival would need a server-side companion mod (out of scope). See `CLAUDE.md` for
the full technical explanation.

## Building
Requires **JDK 25** and **Gradle 9.5+** (Fabric Loom 1.17.11 needs Gradle plugin API 9.5.0+).

```bash
cd shulker-pick-block
java -version                            # must report 25
gradle wrapper --gradle-version 9.5.1    # once
gradlew.bat build                        # Windows   (./gradlew build on macOS/Linux)
# -> build/libs/shulker-pick-block-1.0.0.jar
```

**Mappings note.** This builds against **26.1.2 with Mojang official mappings**
(`loom.officialMojangMappings()` in `build.gradle`) — the 26.x runtime uses Mojang names, so the
source is written in Mojmap (`Minecraft`, `MultiPlayerGameMode`, `ServerPlayer`, etc.). Yarn builds
for 26.x are not published, so do **not** set `yarn_mappings`. If `gradlew build` reports a missing
mappings configuration, ensure `build.gradle`'s `dependencies` block contains
`mappings loom.officialMojangMappings()`.

## Compatibility
- Survival and creative game modes (see survival-multiplayer note above).
- No server-side mod required for single-player/creative; designed not to disturb other
  inventory mods (e.g. Inventory Profiles Next, Mouse Tweaks) that don't intercept pick block at
  the same point.
- Litematica integration is version-fragile by nature (Litematica has no stable public API). It
  works by pre-staging the needed item out of a shulker box at the head of Litematica's own
  `InventoryUtils.schematicWorldPickBlock` — the single method both Easy Place and schematic
  pick-block route through — so Litematica's normal lookup then finds it loose and swaps it to hand.
  **Verified against Litematica `litematica-fabric-26.1.2-0.27.4` (sakura-ryoko fork).** Pin your
  tested Litematica build; the compat layer disables itself rather than crash if the internals don't
  match. (Litematica source for 26.x lives in the sakura-ryoko fork, not maruohon upstream.)

## License
MIT — see [LICENSE](LICENSE).
