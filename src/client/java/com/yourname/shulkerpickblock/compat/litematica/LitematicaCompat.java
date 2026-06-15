package com.yourname.shulkerpickblock.compat.litematica;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.inventory.ShulkerExtractionService;
import com.yourname.shulkerpickblock.util.ShulkerInventoryHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Optional Litematica Easy Place integration (SRS §4.3).
 *
 * <p>Litematica fires pick-block requests in rapid succession while the player holds the place
 * button over schematic ghost blocks. The {@link InventoryUtilsMixin} soft-target intercepts that
 * pipeline and routes each request through the same shulker scan used by vanilla pick block
 * (FR-18), so consecutive block types are supplied from different shulker boxes with no player
 * interaction (FR-19) — without touching Litematica's placement, rendering, or schematic logic
 * (FR-20).
 *
 * <p>This class holds the runtime gate. Whether the mixin is even applied is decided earlier by
 * {@link LitematicaMixinPlugin} (class-presence check); whether it <em>acts</em> is decided here
 * by config. If Litematica is absent, none of this runs and the mod loads cleanly (FR-21).
 */
public final class LitematicaCompat {

    private static boolean litematicaPresent;

    private LitematicaCompat() {
    }

    /** Called from client init: detects Litematica by mod id and logs the resulting state (FR-16). */
    public static void init() {
        litematicaPresent = FabricLoader.getInstance().isModLoaded(ShulkerPickBlock.LITEMATICA_MOD_ID);
        if (litematicaPresent) {
            ShulkerPickBlock.LOGGER.info("Litematica detected — Easy Place compatibility layer armed "
                    + "(active when litematica_compat=true).");
        } else {
            ShulkerPickBlock.LOGGER.info("Litematica not present — Easy Place compatibility layer skipped.");
        }
    }

    public static boolean isActive() {
        ModConfig config = ModConfig.get();
        return litematicaPresent && config.enabled && config.litematicaCompat;
    }

    /**
     * Invoked by the soft-target mixin before Litematica concludes the required item is missing.
     * If the item is not directly in the inventory, attempt to supply it from a shulker box so
     * Litematica then finds it in the hotbar and proceeds with placement.
     *
     * @param requiredStack the stack Litematica wants in hand (may be null/empty if the guessed
     *                      target signature did not match — handled defensively, FR-22/NFR-04)
     */
    public static void handlePick(ItemStack requiredStack) {
        try {
            if (!isActive() || requiredStack == null || requiredStack.isEmpty()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null) {
                return;
            }
            Item item = requiredStack.getItem();
            if (ShulkerInventoryHelper.containsDirectly(client.player.getInventory(), item,
                    ModConfig.get().scanOffhand)) {
                return; // Litematica will find it normally.
            }
            ShulkerExtractionService.extract(client, item);
        } catch (RuntimeException e) {
            // Never let a compat hiccup break Litematica or crash the client (FR-22 / NFR-04).
            ShulkerPickBlock.LOGGER.warn("Litematica compat handlePick failed; ignoring.", e);
        }
    }
}
