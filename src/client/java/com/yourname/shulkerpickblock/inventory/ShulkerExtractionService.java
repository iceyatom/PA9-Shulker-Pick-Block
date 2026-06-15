package com.yourname.shulkerpickblock.inventory;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.ShulkerPickBlockClient;
import com.yourname.shulkerpickblock.config.HotbarSlotStrategy;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.hud.PickBlockHud;
import com.yourname.shulkerpickblock.util.ExtractionResult;
import com.yourname.shulkerpickblock.util.ShulkerInventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.world.GameMode;

import java.util.Optional;

/**
 * Commits a {@link ShulkerInventoryHelper} extraction plan into the player's live inventory and
 * synchronises it with the server as well as vanilla allows. This is the single entry point used
 * by both the vanilla pick-block mixin and the Litematica compat layer, so the
 * one-extraction-per-event guarantee (NFR-06) and the never-crash guarantee (NFR-04) live here.
 *
 * <h2>Server synchronisation — important limitation</h2>
 * The SRS (§8, PKT-01…06) assumes a shulker box stored as an <em>item</em> can be opened as a
 * server-side container and drained with slot-click packets. Vanilla Minecraft provides no such
 * mechanism: only a shulker box <em>placed in the world</em> has a server container, and the
 * server never expects an item-form shulker's {@code CONTAINER} component to change. Therefore:
 * <ul>
 *   <li><b>Creative mode</b> — fully authoritative. Both the reduced shulker box and the
 *       extracted item are written with {@code CreativeInventoryActionC2SPacket}
 *       ({@link ClientPlayerInteractionManager#clickCreativeStack}), which vanilla servers accept
 *       in creative. This is the primary supported path and covers the common Litematica
 *       creative-building use case.</li>
 *   <li><b>Survival</b> — only client-side prediction is possible; a vanilla server will
 *       reconcile (revert) it because no legal packet describes the change. Works in
 *       single-player against the integrated server only until the next authoritative sync.
 *       TC-11 (vanilla survival server sync) cannot pass with vanilla packets — documented in
 *       README/CLAUDE.</li>
 * </ul>
 * No custom payload packets are used (PKT-06).
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
    public static boolean extract(MinecraftClient client, Item targetItem) {
        ModConfig config = ModConfig.get();
        if (!config.enabled || client == null || targetItem == null) {
            return false;
        }

        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager interaction = client.interactionManager;
        if (player == null || interaction == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();

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

            commit(client, interaction, inventory, result, hotbarSlot);

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

    /** Writes the extraction into the inventory, authoritatively in creative, predicted otherwise. */
    private static void commit(MinecraftClient client,
                               ClientPlayerInteractionManager interaction,
                               PlayerInventory inventory,
                               ExtractionResult result,
                               int hotbarSlot) {
        // Always update the client-side view first so the change is visible immediately.
        inventory.setStack(result.playerSlot(), result.updatedShulkerStack());
        inventory.setStack(hotbarSlot, result.extractedStack());

        if (interaction.getCurrentGameMode() == GameMode.CREATIVE) {
            // Authoritative on vanilla servers in creative (PKT-05 honoured the only way vanilla allows).
            interaction.clickCreativeStack(result.updatedShulkerStack(), toScreenSlotId(result.playerSlot()));
            interaction.clickCreativeStack(result.extractedStack(), toScreenSlotId(hotbarSlot));
        } else if (ShulkerPickBlock.LOGGER.isDebugEnabled() || ModConfig.get().debugLogging) {
            ShulkerPickBlock.LOGGER.warn("Survival extraction is client-side prediction only; a vanilla "
                    + "server will not persist the shulker change (see ShulkerExtractionService docs).");
        }
    }

    /** FR-06 hotbar slot selection. */
    private static int chooseHotbarSlot(PlayerInventory inventory, HotbarSlotStrategy strategy) {
        return switch (strategy) {
            case CURRENT_SLOT -> getSelectedSlot(inventory);
            case LRU -> ShulkerPickBlockClient.hotbarTracker().leastRecentlyUsedSlot();
            // VANILLA: an empty hotbar slot if one exists, otherwise the selected slot.
            case VANILLA -> inventory.getSwappableHotbarSlot();
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

    private static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    private static void setSelectedSlot(MinecraftClient client, ClientPlayerEntity player,
                                        PlayerInventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }
}
