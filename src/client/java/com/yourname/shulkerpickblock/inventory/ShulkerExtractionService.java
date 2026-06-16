package com.yourname.shulkerpickblock.inventory;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.ShulkerPickBlockClient;
import com.yourname.shulkerpickblock.config.HotbarSlotStrategy;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.hud.PickBlockHud;
import com.yourname.shulkerpickblock.util.ExtractionResult;
import com.yourname.shulkerpickblock.util.ShulkerInventoryHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;

import java.util.Optional;
import java.util.UUID;

/**
 * Commits a {@link ShulkerInventoryHelper} extraction plan into the player's live inventory and
 * synchronises it with the server as well as vanilla allows. This is the single entry point used
 * by both the vanilla pick-block mixin and the Litematica compat layer, so the
 * one-extraction-per-event guarantee (NFR-06) and the never-crash guarantee (NFR-04) live here.
 *
 * <h2>Server synchronisation</h2>
 * The server owns the inventory, so a client-only {@code setItem} is just a prediction the server
 * reverts on its next sync — the "ghost item that snaps back into the box" bug. How we make the
 * change authoritative depends on the connection:
 * <ul>
 *   <li><b>Single-player / LAN host</b> — the primary supported path. The integrated server runs in
 *       this same JVM, so we apply the same extraction to its authoritative {@link ServerPlayer}
 *       inventory on the server thread ({@link #syncToIntegratedServer}). The server then broadcasts
 *       the real slots and the prediction is confirmed. This is what fixes survival single-player
 *       (and a LAN world the player is hosting), in both survival and creative.</li>
 *   <li><b>Remote server, creative</b> — no integrated server, but creative slot packets
 *       ({@code ServerboundSetCreativeModeSlotPacket} via {@code handleCreativeModeItemAdd}) are
 *       authoritative on vanilla servers in creative, so we send those.</li>
 *   <li><b>Remote vanilla server, survival</b> — genuinely unsupported. Vanilla has no packet that
 *       drains an item-form shulker box (only a <em>placed</em> shulker has a server container), so
 *       prediction is all that is possible and it will revert. TC-11 cannot pass against a vanilla
 *       server; a modded-server companion would be required (out of SRS scope).</li>
 * </ul>
 * No custom payload packets are used (PKT-06) — the single-player fix reuses the integrated server
 * directly instead.
 */
public final class ShulkerExtractionService {

    private ShulkerExtractionService() {
    }

    /**
     * Attempts to satisfy a pick block for {@code targetItem} from an inventory shulker box.
     *
     * @return {@code true} if an item was extracted and placed in the hotbar; {@code false} if
     *         nothing matched or the mod deferred — in which case the caller should fall back to
     *         vanilla behaviour (FR-08).
     */
    public static boolean extract(Minecraft client, Item targetItem) {
        ModConfig config = ModConfig.get();
        if (!config.enabled || client == null || targetItem == null) {
            return false;
        }

        LocalPlayer player = client.player;
        MultiPlayerGameMode interaction = client.gameMode;
        if (player == null || interaction == null) {
            return false;
        }
        Inventory inventory = player.getInventory();

        try {
            // FR-02 fast path: if the item is already directly available, defer to vanilla.
            if (ShulkerInventoryHelper.containsDirectly(inventory, targetItem, config.scanOffhand)) {
                return false;
            }

            Optional<ExtractionResult> maybe = ShulkerInventoryHelper.findAndExtract(
                    inventory, targetItem, config.scanOffhand, config.preferLargestStack);
            if (maybe.isEmpty()) {
                if (config.debugLogging) {
                    ShulkerPickBlock.LOGGER.info("No shulker box contained {}", targetItem);
                }
                return false; // FR-08: fall back to vanilla
            }

            ExtractionResult result = maybe.get();
            int hotbarSlot = chooseHotbarSlot(inventory, config.hotbarSlotStrategy);

            commit(client, interaction, inventory, result, hotbarSlot, targetItem, config);

            // FR-05: make it the active held item.
            setSelectedSlot(client, player, inventory, hotbarSlot);
            ShulkerPickBlockClient.hotbarTracker().touch(hotbarSlot);

            if (config.showHudMessage) {
                PickBlockHud.show(result.extractedStack());
            }
            if (config.debugLogging) {
                ShulkerPickBlock.LOGGER.info("Pulled {} from shulker in slot {} (internal {}) -> hotbar {}",
                        targetItem, result.playerSlot(), result.internalSlot(), hotbarSlot);
            }
            return true;
        } catch (RuntimeException e) {
            // NFR-04: never crash; log and fall back to vanilla.
            ShulkerPickBlock.LOGGER.warn("Shulker extraction failed for {}; falling back to vanilla.",
                    targetItem, e);
            return false;
        }
    }

    /**
     * Writes the extraction into the inventory and makes it stick.
     *
     * <p>The client-side write is only a <em>prediction</em>: the server owns the inventory and will
     * reconcile (revert) any slot the client changed on its own — that is the "ghost item that jumps
     * back into the box" symptom. To make the change authoritative we mutate the server's inventory
     * too, choosing the only mechanism that actually works for each connection:
     * <ul>
     *   <li><b>Single-player / LAN host</b> — the integrated server lives in this same JVM, so we
     *       hand the same extraction to its thread and apply it to the authoritative
     *       {@link ServerPlayer} inventory. The server then broadcasts the real slots back and the
     *       prediction is confirmed instead of reverted. This is the path that fixes the bug.</li>
     *   <li><b>Remote server, creative</b> — no integrated server here, but creative slot packets
     *       are authoritative on vanilla servers, so we send those.</li>
     *   <li><b>Remote vanilla server, survival</b> — genuinely unsupported: vanilla has no packet
     *       that drains an item-form shulker box (see class docs). Prediction only; will revert.</li>
     * </ul>
     */
    private static void commit(Minecraft client,
                               MultiPlayerGameMode interaction,
                               Inventory inventory,
                               ExtractionResult result,
                               int hotbarSlot,
                               Item targetItem,
                               ModConfig config) {
        // Always update the client-side view first so the change is visible immediately.
        inventory.setItem(result.playerSlot(), result.updatedShulkerStack());
        inventory.setItem(hotbarSlot, result.extractedStack());

        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            // Single-player / LAN host: make the integrated server's inventory match (authoritative).
            syncToIntegratedServer(server, client.player.getUUID(), targetItem, hotbarSlot, config);
        } else if (interaction.getPlayerMode() == GameType.CREATIVE) {
            // Remote server in creative: creative slot packets are honoured by vanilla.
            interaction.handleCreativeModeItemAdd(result.updatedShulkerStack(), toScreenSlotId(result.playerSlot()));
            interaction.handleCreativeModeItemAdd(result.extractedStack(), toScreenSlotId(hotbarSlot));
        } else if (ShulkerPickBlock.LOGGER.isDebugEnabled() || config.debugLogging) {
            ShulkerPickBlock.LOGGER.warn("Survival extraction against a remote server is client-side "
                    + "prediction only; a vanilla server will revert it (see ShulkerExtractionService docs).");
        }
    }

    /**
     * Applies the extraction to the authoritative server inventory on the integrated server's own
     * thread. Re-scans the server's inventory (rather than trusting the client's copy) so the move
     * is computed from authoritative state — this both prevents duplication and, because the scan is
     * deterministic, lands on exactly the same slots the client predicted, so the player sees no
     * flicker. After the write, {@code broadcastChanges} pushes the confirmed slots back to the
     * client immediately rather than waiting for the next inventory tick.
     *
     * <p>VERIFY against 26.1.2 mappings: {@code Minecraft#getSingleplayerServer()},
     * {@code MinecraftServer#getPlayerList().getPlayer(UUID)}, {@code ServerPlayer#getInventory()},
     * the {@code ServerPlayer.inventoryMenu} field and {@code AbstractContainerMenu#broadcastChanges()}.
     */
    private static void syncToIntegratedServer(MinecraftServer server,
                                               UUID playerId,
                                               Item targetItem,
                                               int hotbarSlot,
                                               ModConfig config) {
        server.execute(() -> {
            try {
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
                if (serverPlayer == null) {
                    return;
                }
                Inventory serverInventory = serverPlayer.getInventory();
                Optional<ExtractionResult> maybe = ShulkerInventoryHelper.findAndExtract(
                        serverInventory, targetItem, config.scanOffhand, config.preferLargestStack);
                if (maybe.isEmpty()) {
                    return; // server no longer has it in a box; leave its state untouched.
                }
                ExtractionResult serverResult = maybe.get();
                serverInventory.setItem(serverResult.playerSlot(), serverResult.updatedShulkerStack());
                serverInventory.setItem(hotbarSlot, serverResult.extractedStack());
                serverPlayer.inventoryMenu.broadcastChanges();
            } catch (RuntimeException e) {
                // NFR-04: never crash the server thread; the client prediction simply reverts.
                ShulkerPickBlock.LOGGER.warn("Server-side shulker sync failed for {}; "
                        + "client view may revert.", targetItem, e);
            }
        });
    }

    /** FR-06 hotbar slot selection. */
    private static int chooseHotbarSlot(Inventory inventory, HotbarSlotStrategy strategy) {
        return switch (strategy) {
            case CURRENT_SLOT -> getSelectedSlot(inventory);
            case LRU -> ShulkerPickBlockClient.hotbarTracker().leastRecentlyUsedSlot();
            // VANILLA: an empty hotbar slot if one exists, otherwise the selected slot.
            case VANILLA -> inventory.getSuitableHotbarSlot();
        };
    }

    /**
     * Converts a {@link PlayerInventory} slot index to the {@code PlayerScreenHandler} /
     * creative-action slot id used by {@code CreativeInventoryActionC2SPacket}:
     * hotbar 0–8 → 36–44, storage 9–35 → 9–35, off-hand 40 → 45. Mirrors vanilla
     * {@code doItemPick}'s use of {@code 36 + selectedSlot} for the hotbar.
     */
    private static int toScreenSlotId(int playerSlot) {
        if (playerSlot >= 0 && playerSlot <= 8) {
            return 36 + playerSlot;
        }
        if (playerSlot == ShulkerInventoryHelper.OFF_HAND_SLOT) {
            return 45;
        }
        return playerSlot; // 9–35 map 1:1
    }

    // --- selected-slot access centralised here; VERIFY against 26.1.2 Yarn ---
    // Recent MC encapsulated the old public `selectedSlot` field behind
    // getSelectedSlot()/setSelectedSlot(int). If 26.1.2 still exposes the field, swap these two.

    private static int getSelectedSlot(Inventory inventory) {
        return inventory.getSelectedSlot();
    }

    private static void setSelectedSlot(Minecraft client, LocalPlayer player,
                                        Inventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
