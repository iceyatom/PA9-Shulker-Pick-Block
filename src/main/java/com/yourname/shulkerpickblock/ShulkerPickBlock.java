package com.yourname.shulkerpickblock;

import com.yourname.shulkerpickblock.network.ServerPickHandler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint (runs on dedicated servers and on clients) plus shared constants and the
 * mod-wide logger.
 *
 * <p>Kept free of Minecraft client types: this class lives in the common source set and is
 * loaded on dedicated servers, where client classes don't exist.
 */
public final class ShulkerPickBlock implements ModInitializer {
    /** Matches the {@code id} in {@code fabric.mod.json}. */
    public static final String MOD_ID = "shulkerpickblock";

    /** Human-readable name used in log lines and the HUD. */
    public static final String MOD_NAME = "ShulkerPickBlock";

    /** Litematica's mod id, used for the optional Easy Place compatibility layer (FR-16). */
    public static final String LITEMATICA_MOD_ID = "litematica";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        // Registers the pick request packet and its server handler. On a dedicated server this is
        // the whole mod; on a client it also serves the single-player integrated server.
        ServerPickHandler.register();
    }
}
