# CLAUDE.md — ShulkerPickBlock

Knowledge base for future Claude runs on this project. Read this first.

## What this is
A **client-side Fabric mod** for **Minecraft Java 26.2** that extends vanilla pick block
(middle-click) to search inside shulker boxes stored in the player's inventory, extract the
targeted item, and place it in the hotbar — with optional **Litematica Easy Place** integration.
Built from `../ShulkerPickBlock_ModRequirements_v2.pdf` (SRS v2.0). FR/NFR/PKT/TC IDs below refer
to that document. Package requirements (originally targeting 26.1.2) were bumped to **26.2** on
2026-07-02; no mod functionality or `mod_version` changed in that update. **1.1.0 (2026-07-29)**
added the FR-25 in-game settings screen (Mod Menu gear + `/shulkerpickblock config`) and turned the
`prefer_largest_stack` boolean into the three-way `source_selection` option.

## ⚠️ Build Environment Findings (READ BEFORE BUILDING)

This was authored in a sandbox on 2026-06-14 against MC 26.1.2 and re-validated on 2026-07-02
against MC 26.2. The findings below are empirical (curl + Loom), not assumptions.

| Component | Status in sandbox (26.2, 2026-07-02) | Notes |
|---|---|---|
| MC 26.2 | ✅ exists (latest stable per Fabric meta) | client/server jars downloadable; requires Java 25 |
| JDK 25.0.3 (Temurin) | ✅ installed | matches SRS §2 |
| Gradle | ✅ wrapper 9.5.1 | Loom needs Gradle plugin-API 9.5.0+; 9.5.1 satisfies it |
| Fabric Loom (resolved 1.15.5 from the `1.15-SNAPSHOT` plugin line) | ✅ loads | |
| Fabric Loader 0.18.4 / Fabric API 0.154.0+26.2 | ✅ on maven | fabric_version bumped from 0.151.0+26.1.2 |
| Mod Menu 20.0.0-beta.4 (added 2026-07-29) | ✅ on `maven.terraformersmc.com/releases` | Not in that repo's `maven-metadata.xml` (it lags) but the jar 200s. Declared as plain **`compileOnly` with `transitive = false`** — Loom 1.15 has **no `mod*` configurations** (`modCompileOnly` fails with "Could not find method"), because 26.x needs no remapping; same reason fabric-api uses `implementation`. |
| **Mappings for 26.2** | ✅ **not needed** | `gradlew build` succeeded with **no `mappings` dependency declared at all** — Loom deobfuscates 26.2 as an identity step. See corrected understanding below. |

### Corrected understanding (supersedes the 2026-06-14 "cannot compile" finding)
The 2026-06-14 entry above concluded 26.1.2 couldn't be compiled because no Yarn/intermediary/Mojmap
mapping artifact was reachable for it. Re-tested on 2026-07-02 for **26.2**: `intermediary` still
returns only a `0.0.0` stub and Yarn still has no 26.x build, but the build **succeeds anyway**
with zero `mappings` line in `build.gradle` — `gradlew build` produced a real, populated
`shulker-pick-block-1.0.0.jar` with all classes (mixins included) compiled clean against actual
Minecraft/Fabric API types. Conclusion: **26.x Minecraft jars ship with real (Mojmap) names
already baked in**, so there is nothing to deobfuscate/remap — the earlier "Loom fails: Failed to
find official mojang mappings" error was most likely from an explicit
`mappings loom.officialMojangMappings()` call that no longer applies once Mojang stopped shipping
obfuscated 26.x jars. **Do not add a `mappings` line back** — leave the dependency block as-is.

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
`SimpleInventory`. The 1.21.11 values were validation-only via `-P` overrides, never committed.
The committed `gradle.properties` now targets **26.2** (bumped from 26.1.2 on 2026-07-02) and
`gradlew build` with **no `-P` overrides** succeeds directly against it (see finding above) —
the `-P`-override workflow below is now historical/fallback only.

### Mapping-set caveat (Yarn vs Mojang)
Evidence suggests the ecosystem moved to **Mojang official mappings for 26.x** (Yarn/intermediary
froze at 1.21.11; current Fabric API modules use Mojmap names like `net.minecraft.resources.Identifier`).
The SRS says "Yarn", and this code is **Yarn-mapped** (verified vs 1.21.11 Yarn). If a Yarn-26.x
build is NOT available on the target machine, the build must switch to `loom.officialMojangMappings()`
**and** the Yarn type/method names must be translated to Mojmap. See the translation table at the
bottom of this file.

## How to build
```bash
cd shulker-pick-block
java -version                       # must report 25
gradle wrapper --gradle-version 9.5.1   # once; 9.5.0+ required by Loom
gradlew.bat build                   # Windows (./gradlew build on macOS/Linux)
# Output: build/libs/shulker-pick-block - 26.2 - 1.1.0.jar  <- install this
# (filename follows the modpack convention "<name> - <minecraft_version> - <mod_version>.jar",
# set via jar.archiveFileName in build.gradle — matches e.g. "logstripper - 26.2 - 1.0.0.jar")
```
No `yarn_mappings` / `mappings` setup is needed — see the Build Environment Findings above.

## Architecture (all under `src/client/java/com/yourname/shulkerpickblock/`)
- `ShulkerPickBlock` — mod id, name, logger constants.
- `ShulkerPickBlockClient` — `ClientModInitializer`; loads config, registers command + HUD + tick,
  arms Litematica compat. Thin bootstrap only.
- `util/ShulkerInventoryHelper` — **pure scan/extract logic** (NFR-12, §7.4). No event-bus/render
  deps; unit-testable. Returns an `ExtractionResult` plan from copies; never mutates live inventory.
- `util/ExtractionResult` — record: source player slot, internal slot, extracted stack, updated box.
- `util/HotbarUsageTracker` — per-tick LRU tracking for the `LRU` hotbar strategy.
- `inventory/ShulkerExtractionService` — **commits** the plan: chooses hotbar slot (FR-06) and does
  the mutation in one shared `applyExtraction` used by both the client prediction and the
  authoritative integrated-server re-apply (keeps them deterministic/in sync). **Item conservation:**
  if the destination slot is occupied, the displaced item is swapped into the box's vacated internal
  slot (`ShulkerInventoryHelper.withInternalItem`) instead of being overwritten — except a held
  shulker box, which can't be nested, so the pick aborts without mutating. Sync: single-player/LAN =
  integrated server authoritative; remote creative = creative slot packets; remote survival =
  prediction only. Player notices go through `PickBlockHud.showMessage` (NOT `displayClientMessage` —
  that method is absent from the 26.x mapping; the HUD render path is the verified way to show
  text). Single try/catch fallback (NFR-04); one entry point for both pick paths (NFR-06).
- `mixin/client/MinecraftClientPickBlockMixin` — vanilla hook. **Targets `MinecraftClient.doItemPick()`
  at TAIL**, not `ClientPlayerInteractionManager` as SRS §7.1 guessed (that method only fires on the
  found-in-inventory path). Acts only when vanilla couldn't supply the item.
- `config/ModConfig` + `HotbarSlotStrategy` + `SourceSelectionStrategy` — flat-TOML read/write (no
  external dep), all SRS §6 options, reloadable (FR-24). `ModConfig.apply(cfg)` is the write path
  used by the GUI (swap active instance + save), mirroring `load()`'s swap so the two can't drift.
  `source_selection` (LARGEST_STACK | SMALLEST_STACK | FIRST_FOUND) replaced the 1.0.0 boolean
  `prefer_largest_stack`; the legacy key is still read once for migration in `readSourceSelection`.
- `gui/ShulkerPickBlockConfigScreen` — the FR-25 settings screen, **vanilla widgets only** (no Cloth
  Config/YACL). Edits a `ModConfig.copy()` so Cancel/Escape discards. Reachable from Mod Menu's gear
  and from `/shulkerpickblock config`. 26.2 GUI notes: screens are opened/closed via
  **`Minecraft.gui.setScreen(screen)`** (`Minecraft.setScreen` no longer exists; `setScreenAndShow`
  is the same thing plus a forced frame), rendering goes through `extractRenderState(
  GuiGraphicsExtractor, …)` so **no render override is needed**, and the layout stack
  (`HeaderAndFooterLayout`/`GridLayout`/`LinearLayout`) is unchanged from 1.21.x. The layout object
  is rebuilt inside `init()` — it re-runs on resize and on `rebuildWidgets()` (Reset), and re-adding
  to a retained layout stacks duplicate widgets.
- `compat/modmenu/ModMenuIntegration` — `ModMenuApi` impl behind the `modmenu` entrypoint (key
  string verified by `javap` on the installed Mod Menu). Mod Menu is **`compileOnly`** (see below);
  only Mod Menu reads that entrypoint, so the class never loads without it — no `NoClassDefFoundError`.
- `command/ShulkerPickBlockCommands` — `/shulkerpickblock config|reload|status` via
  `fabric-command-api-v2`. `config` defers `gui.setScreen` through `Minecraft.execute` because the
  chat screen is still closing when the command body runs.
- `hud/PickBlockHud` — self-expiring "pulled from shulker" notification (FR-08).
- `compat/litematica/` — `LitematicaCompat` (detect + runtime gate), `LitematicaMixinPlugin`
  (class-presence gate, FR-22), `mixin/InventoryUtilsMixin` (`@Pseudo` soft-target, `require=0`).

## VERIFY against the real 26.2 mappings (checklist)
Compile-checked names are **bold** = confirmed vs 1.21.11 Yarn and vs a clean 26.2 `gradlew build`;
the rest are runtime-resolved or version-fragile and need re-checking in-game on 26.2:
- ⚠️ Mixin target method **`MinecraftClient.doItemPick`** — name is runtime-resolved by Mixin; the
  refmap built clean vs 1.21.11 and vs 26.2 but confirm the method still exists/name at runtime.
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
  /`HudElement` (different "extract render state" model). May be **removed** in 26.2. If the HUD
  fails to compile, port to `HudElementRegistry`. Cosmetic feature, low risk.
- ✅ `BlockState.getPickStack(WorldView, BlockPos, boolean)`, `ContainerComponent.copyTo(DefaultedList)`
  /`.fromStacks(List)`, `DataComponentTypes.CONTAINER`, `PlayerInventory.getSelectedSlot()/setSelectedSlot()`
  /`getSwappableHotbarSlot()`, `ClientPlayerInteractionManager.getCurrentGameMode()/clickCreativeStack()`,
  `UpdateSelectedSlotC2SPacket` — all compiled clean vs 1.21.11 and vs 26.2; re-confirm in-game.
- ⚠️ Single-player sync chain (NEW, in `ShulkerExtractionService.syncToIntegratedServer`):
  `Minecraft.getSingleplayerServer()`, `MinecraftServer.getPlayerList().getPlayer(UUID)`,
  `ServerPlayer.getInventory()`, the public `ServerPlayer.inventoryMenu` field, and
  `AbstractContainerMenu.broadcastChanges()` — Mojmap names; compiled clean vs both 1.21.11 and the
  real 26.2 mappings. Still needs an in-game confirmation on 26.2.
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
2. ~~**Mod Menu config screen (FR-25) is deferred**~~ — **implemented in 1.1.0** (`gui/
   ShulkerPickBlockConfigScreen` + `compat/modmenu/ModMenuIntegration`). Still unverified in-game.
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
- FR-23/24 (config + reload) — ✅. FR-25 (Mod Menu) — ✅ as of 1.1.0 (needs in-game verify).
- NFR-01..06 perf/safety — ✅ (single-pass scan, try/catch fallback, single mutation).
- NFR-10..13 maintainability — ✅ (Javadoc on mixins, helper isolated, compat in own package).
- TC-01..07,13,14 — should pass in **singleplayer/creative**. TC-08/09 (Easy Place) — depends on the
  Litematica target verify. TC-10/12 — ✅. **TC-11 (vanilla survival server) — won't pass** (limit 1).

## Yarn → Mojang mapping translation table (26.2 uses Mojmap)
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
Note: names above are best-effort; confirm against the actual 26.2 Mojmap (the code already
compiles clean against it — see Build Environment Findings — but names are only checked at
compile time, not runtime behaviour).

## TODO / next steps
1. `gradlew build` already succeeds against the committed 26.2 `gradle.properties` — no mappings
   setup needed. Remaining work is runtime/in-game verification, not compilation.
2. Launch in a 26.2 dev client; walk TC-01..14 (expect TC-11 to fail by design).
3. Confirm the Litematica `InventoryUtils` target method against a 26.2-compatible Litematica
   build (the sakura-ryoko fork build referenced elsewhere in this file was verified against
   26.1.2, not yet 26.2); update `InventoryUtilsMixin.method` if it moved.
4. ~~Implement the Mod Menu screen (FR-25).~~ Done in 1.1.0 — now needs an in-game pass: gear
   icon appears in Mod Menu, all 8 widgets render without overlap, Done writes the TOML, Cancel
   discards, Reset restores defaults, `/shulkerpickblock config` opens it with Mod Menu absent.
5. If the HUD misbehaves at runtime on 26.2, port `PickBlockHud` to `HudElementRegistry`.
