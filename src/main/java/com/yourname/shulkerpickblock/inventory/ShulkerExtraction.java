package com.yourname.shulkerpickblock.inventory;

import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import com.yourname.shulkerpickblock.util.ExtractionResult;
import com.yourname.shulkerpickblock.util.ShulkerInventoryHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * The one inventory mutation this mod performs, shared by every side that runs it: the client
 * prediction, the integrated server, and a dedicated server that has the mod installed (via
 * {@link com.yourname.shulkerpickblock.network.PickFromShulkerPayload}). Lives in the common source
 * set so it has no client dependencies. Because each side re-scans deterministically with the same
 * settings, they all land on the same slots and stay in sync.
 */
public final class ShulkerExtraction {

    private ShulkerExtraction() {
    }

    /** What a single {@link #apply} did, for driving server sync and player messages. */
    public record Outcome(ExtractionResult result, ItemStack finalBox, ItemStack displaced, boolean aborted) {
        /** True when a previously-held item was swapped into the box to avoid destroying it. */
        public boolean swappedIntoBox() {
            return !displaced.isEmpty();
        }
    }

    /**
     * Applies one shulker extraction to {@code inv} <em>in place</em>, conserving items.
     *
     * <p>If the destination hotbar slot already holds an item, simply overwriting it would delete
     * that item. Instead the displaced item is swapped into the box's just-vacated internal slot, so
     * nothing is lost. The one item that can't go there is another shulker box (vanilla forbids
     * nesting): then nothing is mutated and the outcome is {@link Outcome#aborted()}.
     *
     * @return the outcome, or {@code null} if no shulker box held {@code targetItem}
     */
    public static Outcome apply(Inventory inv, Item targetItem, int hotbarSlot,
                                boolean scanOffhand, SourceSelectionStrategy strategy) {
        Optional<ExtractionResult> maybe = ShulkerInventoryHelper.findAndExtract(
                inv, targetItem, scanOffhand, strategy);
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
}
