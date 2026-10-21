package com.yourname.shulkerpickblock.config;

import java.util.Locale;

/**
 * Controls which shulker box (and which stack inside it) an item is pulled from when more than
 * one carried box holds the target item (FR-07).
 *
 * <ul>
 *   <li>{@link #LARGEST_STACK} — the biggest matching stack wins. Avoids fragmenting stacks:
 *       you get a full stack where one exists, and partial stacks are left alone.</li>
 *   <li>{@link #SMALLEST_STACK} — the smallest matching stack wins. Consumes the leftovers
 *       first, which consolidates boxes over time and keeps full stacks in reserve.</li>
 *   <li>{@link #FIRST_FOUND} — the first match in slot order (inventory slot 0…35, then the
 *       off-hand; within a box, internal slot order). Cheapest and fully predictable.</li>
 * </ul>
 *
 * <p>Replaces the old boolean {@code prefer_largest_stack} option; {@link ModConfig} still reads
 * that key for backwards compatibility ({@code true} → {@link #LARGEST_STACK}, {@code false} →
 * {@link #FIRST_FOUND}).
 */
public enum SourceSelectionStrategy {
    LARGEST_STACK,
    SMALLEST_STACK,
    FIRST_FOUND;

    /** The default used for a fresh config and for any unparseable value. */
    public static final SourceSelectionStrategy DEFAULT = LARGEST_STACK;

    /** Parses a config string case-insensitively, falling back to {@link #DEFAULT}. */
    public static SourceSelectionStrategy fromString(String value) {
        if (value == null) {
            return DEFAULT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /**
     * True if a candidate stack of {@code candidateCount} items beats the current best of
     * {@code bestCount}. Only meaningful for the two size-ranked strategies —
     * {@link #FIRST_FOUND} stops scanning at its first hit and never gets here with a rival.
     */
    public boolean isBetter(int candidateCount, int bestCount) {
        return switch (this) {
            case LARGEST_STACK -> candidateCount > bestCount;
            case SMALLEST_STACK -> candidateCount < bestCount;
            case FIRST_FOUND -> false;
        };
    }

    /** True if the scan can stop as soon as one box yields a match. */
    public boolean stopsAtFirstMatch() {
        return this == FIRST_FOUND;
    }
}
