package com.yourname.shulkerpickblock.util;

import net.minecraft.world.item.ItemStack;

/**
 * The outcome of a successful shulker-box scan (SRS §7.4).
 *
 * <p>This is a <em>plan</em>, not a mutation: {@link ShulkerInventoryHelper} computes it from
 * copies and never touches the live inventory. The caller (the extraction service) is
 * responsible for committing {@link #updatedShulkerStack()} back into {@link #playerSlot()}
 * and routing {@link #extractedStack()} to the hotbar, plus any server synchronisation. This
 * separation keeps the scanning logic unit-testable without a running game (NFR-12) and keeps
 * the single-mutation guarantee (NFR-06) in one place.
 *
 * @param playerSlot          slot in the player inventory holding the source shulker box
 *                            (0–35 main, or {@link ShulkerInventoryHelper#OFF_HAND_SLOT})
 * @param internalSlot        slot inside the shulker box (0–26) the item was taken from
 * @param extractedStack      the stack removed from the shulker box (one full internal stack)
 * @param updatedShulkerStack the shulker box item with its CONTAINER component updated to
 *                            reflect the removal (FR-13); stays a valid, possibly-empty
 *                            shulker box item rather than being consumed (FR-14)
 */
public record ExtractionResult(
        int playerSlot,
        int internalSlot,
        ItemStack extractedStack,
        ItemStack updatedShulkerStack
) {
}
