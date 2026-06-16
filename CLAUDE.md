# CLAUDE.md — ShulkerPickBlock

Knowledge base for future Claude runs on this project. Read this first.

## What this is
A **client-side Fabric mod** for **Minecraft Java 26.1.2** that extends vanilla pick block
(middle-click) to search inside shulker boxes stored in the player's inventory, extract the
targeted item, and place it in the hotbar — with optional **Litematica Easy Place** integration.
Built from `../ShulkerPickBlock_ModRequirements_v2.pdf` (SRS v2.0). FR/NFR/PKT/TC IDs below refer
to that document.

## ⚠️ Build Environment Findings (READ BEFORE BUILDING)

This was authored in a sandbox on 2026-06-14. The findings below are empirical (curl + Loom),
not assumptions.

| Component | Status in sandbox | Notes |
|---|---|---|
| MC 26.1.2 | ✅ exists (latest release per Mojang manifest) | client/server jars downloadable; requires Java 25 |
| JDK 25.0.3 (Temurin) | ✅ installed | matches SRS §2 |
| Gradle | ✅ 9.4.0 on PATH; **wrapper bumped to 9.5.1** | Loom 1.17.11 needs Gradle plugin-API **9.5.0+**, so 9.4.0 alone fails. SRS said "9.4+"; 9.5.1 satisfies it. The on-PATH 9.4.0 only bootstraps the wrapper. |
| Fabric Loom 1.17.11 | ✅ loads | latest stable |
| Fabric Loader 0.18.4 / Fabric API 0.150.0+26.1.2 | ✅ on maven | |
| **Mappings for 26.1.2** | ❌ **NONE reachable** | Mojang `client_mappings`/`server_mappings` are **absent** from the 26.1.2 version JSON; Fabric `intermediary` 404s; Yarn has no 26.x build. All three (Yarn, intermediary, Mojmap) stop at **1.21.11**. Loom fails: *"Failed to find official mojang mappings for 26.1.2."* |

### Consequence
**The project cannot be compiled against 26.1.2 in this sandbox** — Loom can't deobfuscate the
game without mappings. This is an environment limitation, not a code problem. The author built
their *Trade Reorder* mod against 26.1.2, so their real machine evidently has working 26.1.2
mappings (a private/cached Yarn-26.x build, or Mojang's real mappings which exist in the world but
weren't reachable here).

### What WAS verified
The code was **compiled and fully built (jar + sources jar + mixin remap) against MC 1.21.11**, the
newest version with published Yarn mappings and the closest reachable proxy to 26.1.2:

```
./gradlew build -Pminecraft_version=1.21.11 -Pyarn_mappings=1.21.11+build.6 -Pfabric_version=0.141.4+1.21.11
# => BUILD SUCCESSFUL; build/libs/shulker-pick-block-1.0.0.jar produced
```

This caught and fixed 3 real API errors (see git/this file's history): `getPickStack` arity +
protected access, `ClientPlayerInteractionManager.hasCreativeInventory()` → `getCurrentGameMode()
== GameMode.CREATIVE`, and `ContainerComponent.copyTo` taking `DefaultedList<ItemStack>` not
`SimpleInventory`. The committed `gradle.properties` still targets **26.1.2**; the 1.21.11 values
are validation-only via `-P` overrides — do not commit them.

### Mapping-set caveat (Yarn vs Mojang)
Evidence suggests the ecosystem moved to **Mojang official mappings for 26.x** (Yarn/intermediary
froze at 1.21.11; current Fabric API modules use Mojmap names like `net.minecraft.resources.Identifier`).
The SRS says "Yarn", and this code is **Yarn-mapped** (verified vs 1.21.11 Yarn). If a Yarn-26.x
build is NOT available on the target machine, the build must switch to `loom.officialMojangMappings()`
**and** the Yarn type/method names must be translated to Mojmap. See the translation table at the
bottom of this file.

## How to build (on a machine with real 26.1.2 mappings)
```bash
cd shulker-pick-block
java -version                       # must report 25
gradle wrapper --gradle-version 9.5.1   # once; 9.5.0+ required by Loom 1.17.11
# Set yarn_mappings in gradle.properties to the actual 26.1.2 Yarn build
# (copy it from your Trade Reorder mod's gradle.properties — authoritative source).
gradlew.bat build                   # Windows
# Output: build/libs/shulker-pick-block-1.0.0.jar  <- install this
```
If 26.1.2 uses Mojang mappings instead of Yarn, see the caveat above.

## Architecture (all under `src/client/java/com/yourname/shulkerpickblock/`)
- `ShulkerPickBlock` — mod id, name, logger constants.
- `ShulkerPickBlockClient` — `ClientModInitializer`; loads config, registers command + HUD + tick,
  arms Litematica compat. Thin bootstrap only.
- `util/ShulkerInventoryHelper` — **pure scan/extract logic** (NFR-12, §7.4). No event-bus/render
  deps; unit-testable. Returns an `ExtractionResult` plan from copies; never mutates live inventory.
- `util/ExtractionResult` — record: source player slot, internal slot, extracted stack, updated box.
- `util/HotbarUsageTracker` — per-tick LRU tracking for the `LRU` hotbar strategy.
- `inventory/ShulkerExtractionService` — **commits** the plan: chooses hotbar slot (FR-06), writes
  inventory, syncs (creative = authoritative via `clickCreativeStack`; survival = client prediction),
  fires HUD. Single try/catch fallback point (NFR-04). One entry point for both pick paths (NFR-06).
- `mixin/client/MinecraftClientPickBlockMixin` — vanilla hook. **Targets `MinecraftClient.doItemPick()`
  at TAIL**, not `ClientPlayerInteractionManager` as SRS §7.1 guessed (that method only fires on the
  found-in-inventory path). Acts only when vanilla couldn't supply the item.
- `config/ModConfig` + `HotbarSlotStrategy` — flat-TOML read/write (no external dep), all SRS §6
  options, reloadable (FR-24).
- `command/ShulkerPickBlockCommands` — `/shulkerpickblock reload|status` via `fabric-command-api-v2`.
- `hud/PickBlockHud` — self-expiring "pulled from shulker" notification (FR-08).
- `compat/litematica/` — `LitematicaCompat` (detect + runtime gate), `LitematicaMixinPlugin`
  (class-presence gate, FR-22), `mixin/InventoryUtilsMixin` (`@Pseudo` soft-target, `require=0`).

## VERIFY against the real 26.1.2 mappings (checklist)
Compile-checked names are **bold** = confirmed vs 1.21.11 Yarn; the rest are runtime-resolved or
version-fragile and need re-checking on 26.1.2:
- ⚠️ Mixin target method **`MinecraftClient.doItemPick`** — name is runtime-resolved by Mixin; the
  refmap built clean vs 1.21.11 but confirm the method still exists/name in 26.1.2.
- ✅/⚠️ Litematica Easy Place entry **confirmed** against `sakura-ryoko/litematica` branch `26.2`
  (the active MC 26.x fork — upstream `maruohon` only goes to 1.21.1): Easy Place item supply runs
  through `WorldUtils.doEasyPlaceAction` → `InventoryUtils.schematicWorldPickBlock(ItemStack, BlockPos,
  Level, Minecraft)`, then aborts if `EntityUtils.getUsedHandForItem` returns null. The mod now hooks
  `schematicWorldPickBlock` at HEAD (`InventoryUtilsMixin`), **matched by name only** so it binds under
  intermediary runtime names (the old `setPickedItemToHand` descriptor hooks used `remap=false` +
  Mojmap descriptors and never bound in production — left in place but effectively dead). Confirm the
  installed Litematica build is the sakura-ryoko fork and that `schematicWorldPickBlock` still has a
  single overload; a second overload would require re-adding a descriptor to disambiguate.
- ⚠️ `HudRenderCallback` — **deprecated** in 1.21.x in favour of `rendering.v1.hud.HudElementRegistry`
  /`HudElement` (different "extract render state" model). May be **removed** in 26.1.2. If the HUD
  fails to compile, port to `HudElementRegistry`. Cosmetic feature, low risk.
- ✅ `BlockState.getPickStack(WorldView, BlockPos, boolean)`, `ContainerComponent.copyTo(DefaultedList)`
  /`.fromStacks(List)`, `DataComponentTypes.CONTAINER`, `PlayerInventory.getSelectedSlot()/setSelectedSlot()`
  /`getSwappableHotbarSlot()`, `ClientPlayerInteractionManager.getCurrentGameMode()/clickCreativeStack()`,
  `UpdateSelectedSlotC2SPacket` — all compiled clean vs 1.21.11; re-confirm on 26.1.2.
- ⚠️ Single-player sync chain (NEW, in `ShulkerExtractionService.syncToIntegratedServer`):
  `Minecraft.getSingleplayerServer()`, `MinecraftServer.getPlayerList().getPlayer(UUID)`,
  `ServerPlayer.getInventory()`, the public `ServerPlayer.inventoryMenu` field, and
  `AbstractContainerMenu.broadcastChanges()` — Mojmap names; **not yet compile-checked** (added after
  the 1.21.11 validation build). Confirm on the real 26.1.2 mapping set.
- ⚠️ Mixin `compatibilityLevel` is `JAVA_25` in both mixin JSONs — valid only if the Mixin shipped
  with Loader 0.18.4 defines that enum. Validated at game launch, not at build.

## Known limitations (honest)
1. **Survival sync works in single-player / LAN-host; a *remote vanilla* server still can't.**
   Vanilla has no packet that drains an *item-form* shulker box (only a *placed* shulker has a
   server container, and the server never expects an item's `CONTAINER` component to change). The
   fix (`ShulkerExtractionService.syncToIntegratedServer`) sidesteps this **without custom packets**
   by using the fact that in single-player / LAN-host the integrated server runs in the same JVM:
   after the client prediction, the same extraction is applied to the authoritative `ServerPlayer`
   inventory on the server thread (`Minecraft.getSingleplayerServer()` → `getPlayerList().getPlayer`
   → `getInventory().setItem` → `inventoryMenu.broadcastChanges()`), which confirms the prediction
   instead of reverting it. This is what fixed the original "ghost item snaps back into the box" bug
   (single-player survival). Paths by connection: **single-player / LAN-host (survival *and*
   creative) = authoritative**; **remote server + creative = authoritative** via
   `handleCreativeModeItemAdd`; **remote *vanilla* server + survival = prediction only, reverts** —
   **TC-11 (remote vanilla survival server) still cannot pass**, and a true fix there needs a
   server-side companion mod (out of SRS scope).
2. **Mod Menu config screen (FR-25) is deferred** — config is fully usable via TOML +
   `/shulkerpickblock reload`. A Mod Menu screen needs a `modCompileOnly` Mod Menu dep + a hand-built
   or Cloth Config screen; not implemented. TODO.
3. **Litematica Easy Place hook** targets the confirmed `InventoryUtils.schematicWorldPickBlock`
   (explicit Mojmap descriptor — the 26.x runtime uses Mojang names, verified by `javap` on the
   installed `litematica-fabric-26.1.2-0.27.4.jar`). **Root-cause bug found & fixed:** the mixin was
   armed but never applied — log showed `target fi.dy.masa.litematica.util.InventoryUtils was loaded
   too early`, because `LitematicaMixinPlugin.isClassPresent` used `Class.forName` (which *loads* the
   class) during mixin bootstrap. Fixed to a `getResource(".class")` presence check that doesn't load
   the class. **Lesson: never `Class.forName` a `@Pseudo` soft-target's class in a mixin plugin.**
   Possible follow-up: a server-thread race in single-player between our `server.execute` extraction
   and the Easy Place placement packet — pre-staging happens at `schematicWorldPickBlock` HEAD (queued
   before the place packet is sent), so it should win, but watch for occasional first-click misses.
4. **SRS says "six shulker colour variants" (FR-09)** — actually vanilla has 17 (16 dyed + plain).
   Code uses `instanceof ShulkerBoxBlock`, which covers *all* variants correctly.

## Requirements traceability (summary)
- FR-01..08 (core pick) — ✅ implemented (`Mixin` + `Service` + `Helper`). FR-06 LRU via tracker.
- FR-09..14 (shulker/data-components) — ✅ via Data Components API; all-variants; FR-13/14 honoured.
- FR-15 / PKT-01..06 (server sync) — ⚠️ partial: single-player/LAN-host authoritative (survival +
  creative) via the integrated server; remote creative authoritative; remote vanilla survival still
  prediction-only (see limitation 1). PKT-06 (no custom packets) honoured.
- FR-16..22 (Litematica) — ✅ scaffolded with graceful disable; FR-17/18 target method needs verify.
- FR-23/24 (config + reload) — ✅. FR-25 (Mod Menu) — ❌ deferred.
- NFR-01..06 perf/safety — ✅ (single-pass scan, try/catch fallback, single mutation).
- NFR-10..13 maintainability — ✅ (Javadoc on mixins, helper isolated, compat in own package).
- TC-01..07,13,14 — should pass in **singleplayer/creative**. TC-08/09 (Easy Place) — depends on the
  Litematica target verify. TC-10/12 — ✅. **TC-11 (vanilla survival server) — won't pass** (limit 1).

## Yarn → Mojang mapping translation table (if 26.1.2 needs Mojmap)
| Yarn (used here) | Mojang (Mojmap) |
|---|---|
| `MinecraftClient` / `doItemPick` | `Minecraft` / `pickBlock` (verify) |
| `ClientPlayerInteractionManager` | `MultiPlayerGameMode` |
| `PlayerInventory` / `getSelectedSlot` | `Inventory` / `getSelectedSlot` |
| `ItemStack`, `Item`, `BlockItem`, `ShulkerBoxBlock` | same simple names, `net.minecraft.world.item.*` / `world.level.block.*` |
| `DataComponentTypes.CONTAINER` / `ContainerComponent` | `DataComponents.CONTAINER` / `ItemContainerContents` |
| `DrawContext` / `RenderTickCounter` / `Identifier` | `GuiGraphics` / `DeltaTracker` / `ResourceLocation` (`net.minecraft.resources`) |
| `UpdateSelectedSlotC2SPacket` | `ServerboundSetCarriedItemPacket` |
| `BlockState.getPickStack(WorldView,BlockPos,boolean)` | `BlockState.getCloneItemStack(...)` (verify) |
Note: names above are best-effort; confirm against the actual 26.1.2 Mojmap.

## TODO / next steps
1. On a machine with 26.1.2 mappings: set `yarn_mappings` (or switch to Mojmap), run `gradlew build`,
   fix any residual VERIFY-list mismatches.
2. Launch in a 26.1.2 dev client; walk TC-01..14 (expect TC-11 to fail by design).
3. Confirm the Litematica `InventoryUtils` target method against the installed Litematica build;
   update `InventoryUtilsMixin.method`.
4. Implement the Mod Menu screen (FR-25).
5. If HUD won't compile on 26.1.2, port `PickBlockHud` to `HudElementRegistry`.
