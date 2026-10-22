package com.yourname.shulkerpickblock.network;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.inventory.ShulkerExtraction;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * Server side of the mod. Registered from the common entrypoint, so it runs on dedicated servers
 * and on the single-player integrated server alike.
 *
 * <p>This is what makes survival work on a remote server: vanilla has no packet that changes the
 * contents of a shulker box <em>item</em>, so without the mod on the server the client's change is
 * only a prediction that gets reverted. With it, the server applies the same
 * {@link ShulkerExtraction} to the player's real inventory and confirms it back.
 *
 * <p>Safety: the request can only rearrange items the player already owns — the item comes out of a
 * box in their own inventory and anything displaced goes back into that box — so a modified client
 * can't create or destroy items with it.
 */
public final class ServerPickHandler {

    private ServerPickHandler() {
    }

    public static void register() {
        // Before 1.19.4 Fabric only has raw channel handlers, which run on the network thread:
        // decode the request here and run it on the server thread, as later Fabric versions do.
        ServerPlayNetworking.registerGlobalReceiver(PickFromShulkerPayload.TYPE,
                (server, player, handler, buf, responseSender) -> {
                    PickFromShulkerPayload payload = PickFromShulkerPayload.read(buf);
                    server.execute(() -> handle(payload, player));
                });
    }

    private static void handle(PickFromShulkerPayload payload, ServerPlayer player) {
        try {
            if (player == null || player.isSpectator() || !player.isAlive()
                    || payload.item() == null || payload.item() == Items.AIR
                    || payload.hotbarSlot() < 0 || payload.hotbarSlot() > 8) {
                resync(player);
                return;
            }

            ShulkerExtraction.Outcome outcome = ShulkerExtraction.apply(player.getInventory(),
                    payload.item(), payload.hotbarSlot(), payload.scanOffhand(), payload.strategy());
            if (outcome == null || outcome.aborted()) {
                // The client predicted a change the server didn't make: push the real slots back.
                resync(player);
                return;
            }
            player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException e) {
            ShulkerPickBlock.LOGGER.warn("Server-side shulker pick failed for {}; resyncing inventory.",
                    payload.item(), e);
            resync(player);
        }
    }

    private static void resync(ServerPlayer player) {
        if (player != null) {
            player.inventoryMenu.sendAllDataToRemote();
        }
    }
}
