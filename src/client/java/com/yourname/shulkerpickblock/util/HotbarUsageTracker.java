package com.yourname.shulkerpickblock.util;

/**
 * Tracks how recently each of the nine hotbar slots was the player's selected slot, so the
 * {@code LRU} hotbar strategy (FR-06) can pick the least-recently-used slot.
 *
 * <p>Updated once per client tick from the player's current selected slot. Single-threaded
 * (client thread only), so no synchronisation is needed.
 */
public final class HotbarUsageTracker {
    private static final int HOTBAR_SIZE = 9;

    private final long[] lastUsedTick = new long[HOTBAR_SIZE];
    private long tick;

    /** Records that {@code selectedSlot} is in use this tick. Call once per client tick. */
    public void recordTick(int selectedSlot) {
        tick++;
        if (selectedSlot >= 0 && selectedSlot < HOTBAR_SIZE) {
            lastUsedTick[selectedSlot] = tick;
        }
    }

    /** Marks a slot as just-used (e.g. right after we place an extracted item into it). */
    public void touch(int slot) {
        if (slot >= 0 && slot < HOTBAR_SIZE) {
            lastUsedTick[slot] = ++tick;
        }
    }

    /** @return the hotbar slot (0–8) with the oldest last-used timestamp. */
    public int leastRecentlyUsedSlot() {
        int best = 0;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (lastUsedTick[i] < oldest) {
                oldest = lastUsedTick[i];
                best = i;
            }
        }
        return best;
    }
}
