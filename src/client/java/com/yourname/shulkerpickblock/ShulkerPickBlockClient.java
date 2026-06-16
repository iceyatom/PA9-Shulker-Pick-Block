package com.yourname.shulkerpickblock;

import com.yourname.shulkerpickblock.command.ShulkerPickBlockCommands;
import com.yourname.shulkerpickblock.compat.litematica.LitematicaCompat;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.hud.PickBlockHud;
import com.yourname.shulkerpickblock.util.HotbarUsageTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * Client entrypoint (declared under {@code entrypoints.client} in {@code fabric.mod.json}).
 *
 * <p>Wires up the four runtime pieces and nothing else — all behaviour lives in the dedicated
 * services so this stays a thin bootstrap:
 * <ol>
 *   <li>loads the TOML config (FR-23),</li>
 *   <li>registers the {@code /shulkerpickblock} command (FR-24),</li>
 *   <li>drives the HUD notification + LRU hotbar usage tracker from the client tick,</li>
 *   <li>arms the optional Litematica compat layer (FR-16).</li>
 * </ol>
 * The vanilla pick-block hook itself is a mixin and needs no registration here.
 *
 * <p>VERIFY against 26.1.2's Fabric API: the {@link HudRenderCallback} signature and the
 * {@code fabric-command-api-v2} / {@code fabric-lifecycle-events-v1} entry points.
 */
public class ShulkerPickBlockClient implements ClientModInitializer {

    private static final HotbarUsageTracker HOTBAR_TRACKER = new HotbarUsageTracker();

    public static HotbarUsageTracker hotbarTracker() {
        return HOTBAR_TRACKER;
    }

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                ShulkerPickBlockCommands.register(dispatcher));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                HOTBAR_TRACKER.recordTick(client.player.getInventory().getSelectedSlot());
            }
            PickBlockHud.tick();
        });

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.HELD_ITEM_TOOLTIP,
                Identifier.fromNamespaceAndPath(ShulkerPickBlock.MOD_ID, "picked_item"),
                (context, tickCounter) -> PickBlockHud.render(context));

        LitematicaCompat.init();

        ShulkerPickBlock.LOGGER.info("{} initialised ({}).", ShulkerPickBlock.MOD_NAME,
                ModConfig.get().summary());
    }
}
