package com.yourname.shulkerpickblock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the mod-wide logger.
 *
 * <p>Kept deliberately free of Minecraft client types so it can be referenced from
 * anywhere (mixins, utils, compat) without dragging in render or event-bus state.
 */
public final class ShulkerPickBlock {
    /** Matches the {@code id} in {@code fabric.mod.json}. */
    public static final String MOD_ID = "shulkerpickblock";

    /** Human-readable name used in log lines and the HUD. */
    public static final String MOD_NAME = "ShulkerPickBlock";

    /** Litematica's mod id, used for the optional Easy Place compatibility layer (FR-16). */
    public static final String LITEMATICA_MOD_ID = "litematica";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private ShulkerPickBlock() {
    }
}
