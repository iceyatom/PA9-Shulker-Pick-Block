package com.yourname.shulkerpickblock.config;

/**
 * Controls which hotbar slot receives an item extracted from a shulker box (FR-06).
 *
 * <ul>
 *   <li>{@link #VANILLA} — the slot vanilla pick block would have chosen
 *       (an empty hotbar slot if one exists, otherwise the selected slot).</li>
 *   <li>{@link #CURRENT_SLOT} — always the currently selected hotbar slot.</li>
 *   <li>{@link #LRU} — the least-recently-used (non-locked) hotbar slot.</li>
 * </ul>
 */
public enum HotbarSlotStrategy {
    VANILLA,
    CURRENT_SLOT,
    LRU;

    /** Parses a config string case-insensitively, falling back to {@link #VANILLA}. */
    public static HotbarSlotStrategy fromString(String value) {
        if (value == null) {
            return VANILLA;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VANILLA;
        }
    }
}
