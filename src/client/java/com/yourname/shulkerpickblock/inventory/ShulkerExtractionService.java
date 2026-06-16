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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

            int hotbarSlot = chooseHotbarSlot(inventory, config.hotbarSlotStrategy);

            // Client-side prediction; the outcome also drives server sync and the player messages.
            Outcome outcome = applyExtraction(inventory, targetItem, hotbarSlot, config);
            if (outcome == null) {
                if (config.debugLogging) {
                    ShulkerPickBlock.LOGGER.info("No shulker box contained {}", targetItem);
                }
                return false; // FR-08: fall back to vanilla
            }
            if (outcome.aborted()) {
                // The destination slot holds a shulker box and there's no room — a shulker box can't
                // be nested, so we declined rather than destroy it. Tell the player and defer.
                PickBlockHud.showMessage(Component.translatable("message.shulkerpickblock.hand_full",
                        outcome.result().extractedStack().getHoverName()));
                if (config.debugLogging) {
                    ShulkerPickBlock.LOGGER.info("Aborted pick of {}: held shulker box can't be stored "
                            + "in the source box (inventory full).", targetItem);
                }
                return false;
            }

            ExtractionResult result = outcome.result();

            // FR-05: make it the active held item.
            setSelectedSlot(client, player, inventory, hotbarSlot);
            ShulkerPickBlockClient.hotbarTracker().touch(hotbarSlot);

            // Make the change authoritative (single-player server / creative packets / prediction).
            syncAuthoritative(client, interaction, outcome, hotbarSlot, targetItem, config);

            if (config.showHudMessage) {
                PickBlockHud.show(result.extractedStack());
            }
            if (outcome.swappedIntoBox()) {
                // Item conservation: the previously-held item went into the box's vacated slot.
                PickBlockHud.showMessage(Component.translatable("message.shulkerpickblock.swapped_into_box",
                        outcome.displaced().getHoverName()));
            }
            if (config.debugLogging) {
                ShulkerPickBlock.LOGGER.info("Pulled {} from shulker slot {} (internal {}) -> hotbar {}{}",
                        targetItem, result.playerSlot(), result.internalSlot(), hotbarSlot,
                        outcome.swappedIntoBox() ? " (swapped held item into box)" : "");
            }
            return true;
        } catch (RuntimeException e) {
            // NFR-04: never crash; log and fall back to vanilla.
            ShulkerPickBlock.LOGGER.warn("Shulker extraction failed for {}; falling back to vanilla.",
                    targetItem, e);
            return false;
        }
    }

    /** What a single {@link #applyExtraction} did, for driving server sync and player messages. */
    private record Outcome(ExtractionResult result, ItemStack finalBox, ItemStack displaced, boolean aborted) {
        /** True when a previously-held item was swapped into the box to avoid destroying it. */
        boolean swappedIntoBox() {
            return !displaced.isEmpty();
        }
    }

    /**
     * Applies one shulker extraction to {@code inv} <em>in place</em>, conserving items.
     *
     * <p>If the destination hotbar slot already holds an item, simply overwriting it would delete
     * that item (the bug this guards against, hit when the inventory is completely full). Instead we
     * swap the displaced item into the box's just-vacated internal slot — the slot the extracted item
     * came from — so nothing is lost. The one item that can't go there is another shulker box (vanilla
     * forbids nesting): in that case we mutate nothing and report {@link Outcome#aborted()}, leaving
     * the held box untouched.
     *
     * <p>This is the single source of truth for the mutation, shared by the client prediction and the
     * authoritative integrated-server apply. Because it re-scans deterministically and reads the
     * displaced item from the same slot, both sides compute identical results and stay in sync.
     *
     * @return the outcome, or {@code null} if no shulker box held {@code targetItem}
     */
    private static Outcome applyExtraction(Inventory inv, Item targetItem, int hotbarSlot, ModConfig config) {
        Optional<ExtractionResult> maybe = ShulkerInventoryHelper.findAndExtract(
                inv, targetItem, config.scanOffhand, config.preferLargestStack);
        if (maybe.isEmpty()) {
            return null;
        }
        ExtractionResult result = maybe.get();

        ItemStack displaced = inv.getItem(hotbarSlot).copy();
        ItemStack finalBox = result.updatedShulkerStack();
        boolean swappedIntoBox = false;

        if (!displaced.isEmpty()) {
            if (ShulkerInventoryHelper.isShulkerBox(displaced)) {
                // A shulker box can't be stored inside another shulker box — abort, don't delete it.
                return new Outcome(result, finalBox, ItemStack.EMPTY, true);
            }
            finalBox = ShulkerInventoryHelper.withInternalItem(
                    result.updatedShulkerStack(), result.internalSlot(), displaced);
            swappedIntoBox = true;
        }

        inv.setItem(result.playerSlot(), finalBox);
        inv.setItem(hotbarSlot, result.extractedStack());
        return new Outcome(result, finalBox, swappedIntoBox ? displaced : ItemStack.EMPTY, false);
    }

    /**
     * Makes the already-applied client prediction authoritative, by whichever mechanism the
     * connection allows:
     * <ul>
     *   <li><b>Single-player / LAN host</b> — re-apply on the integrated server's thread.</li>
     *   <li><b>Remote server, creative</b> — creative slot packets (the final box, which may now hold
     *       the swapped-in item, plus the extracted stack).</li>
     *   <li><b>Remote vanilla server, survival</b> — unsupported; prediction only (see class docs).</li>
     * </ul>
     */
    private static void syncAuthoritative(Minecraft client, MultiPlayerGameMode interaction,
                                          Outcome outcome, int hotbarSlot, Item targetItem, ModConfig config) {
        ExtractionResult result = outcome.result();
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            syncToIntegratedServer(server, client.player.getUUID(), targetItem, hotbarSlot, config);
        } else if (interaction.getPlayerMode() == GameType.CREATIVE) {
            interaction.handleCreativeModeItemAdd(outcome.finalBox(), toScreenSlotId(result.playerSlot()));
            interaction.handleCreativeModeItemAdd(result.extractedStack(), toScreenSlotId(hotbarSlot));
        } else if (ShulkerPickBlock.LOGGER.isDebugEnabled() || config.debugLogging) {
            ShulkerPickBlock.LOGGER.warn("Survival extraction against a remote server is client-side "
                    + "prediction only; a vanilla server will revert it (see ShulkerExtractionService docs).");
        }
    }

    /**
     * Re-applies the extraction on the integrated server's own thread against its authoritative
     * inventory ({@link #applyExtraction}, so the displaced-item swap is handled identically). The
     * deterministic re-scan lands on the same slots the client predicted, so the player sees no
     * flicker; {@code broadcastChanges} then confirms the slots back to the client immediately.
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
                Outcome outcome = applyExtraction(serverPlayer.getInventory(), targetItem, hotbarSlot, config);
                if (outcome == null || outcome.aborted()) {
                    return; // nothing in a box, or aborted identically to the client.
                }
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
