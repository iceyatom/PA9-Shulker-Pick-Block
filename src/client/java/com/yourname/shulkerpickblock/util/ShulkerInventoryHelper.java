package com.yourname.shulkerpickblock.util;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

import java.util.Optional;

/**
 * Pure scanning / extraction logic for inventory-stored shulker boxes (SRS §7.4).
 *
 * <p>Deliberately has no dependency on the Minecraft event bus, rendering, networking, or the
 * mixin classes — it only touches inventory/item/data-component types. That makes it
 * unit-testable in isolation (NFR-12) and keeps the mixins thin.
 *
 * <p>All reads are non-destructive: candidates are evaluated against the live inventory but the
 * resulting {@link ExtractionResult} is built from {@link ItemStack#copy() copies}, so this
 * class never mutates the player's inventory itself (NFR-05). Committing the plan is the
 * caller's job.
 *
 * <h2>26.1.2 Yarn verification points</h2>
 * Names below are best-effort against the modern Yarn lineage (1.21.x) and must be checked
 * against the actual 26.1.2 Yarn build (no Yarn build was reachable when this was authored):
 * <ul>
 *   <li>{@code ShulkerBoxBlock}, {@code BlockItem#getBlock()}</li>
 *   <li>{@code DataComponentTypes.CONTAINER} → {@code ContainerComponent}</li>
 *   <li>{@code ContainerComponent#copyTo(DefaultedList)} and {@code ContainerComponent.fromStacks(List)}</li>
 *   <li>{@code PlayerInventory#getStack(int)} indexing; off-hand at slot 40</li>
 * </ul>
 */
public final class ShulkerInventoryHelper {

    /** Off-hand slot index in {@link PlayerInventory} ({@code PlayerInventory.OFF_HAND_SLOT}). */
    public static final int OFF_HAND_SLOT = 40;

    /** Highest main-inventory slot (0–35 = 9 hotbar + 27 storage). */
    public static final int MAIN_INVENTORY_MAX = 35;

    /** Internal slot count of a vanilla shulker box (FR-04). */
    public static final int SHULKER_SLOTS = 27;

    private ShulkerInventoryHelper() {
    }

    /**
     * Scans every top-level shulker box in the player's inventory for {@code targetItem} and,
     * if found, returns a plan to extract one internal stack of it (FR-03 … FR-08).
     *
     * <p>Nested shulker boxes are intentionally not recursed into (FR-11). The off-hand box is
     * included only when {@code scanOffhand} is true (FR-10). When {@code preferLargestStack} is
     * true, the box whose largest single internal stack of the target is biggest wins, to avoid
     * splitting partial stacks across boxes (FR-07); otherwise the first match in slot order is
     * used.
     *
     * @return a populated plan, or {@link Optional#empty()} if no box holds the item
     */
    public static Optional<ExtractionResult> findAndExtract(PlayerInventory inventory,
                                                            Item targetItem,
                                                            boolean scanOffhand,
                                                            boolean preferLargestStack) {
        if (inventory == null || targetItem == null) {
            return Optional.empty();
        }

        int bestPlayerSlot = -1;
        int bestInternalSlot = -1;
        int bestCount = 0;

        // Iterate main inventory (0–35) then the off-hand (40); worst case 36×27 + 27 checks (NFR-01).
        for (int playerSlot : scanOrder(scanOffhand)) {
            ItemStack boxStack = inventory.getStack(playerSlot);
            if (!isShulkerBox(boxStack)) {
                continue;
            }

            ContainerComponent container = boxStack.getOrDefault(DataComponentTypes.CONTAINER,
                    ContainerComponent.DEFAULT);

            // Find the internal slot in this box with the most of the target item.
            int localSlot = -1;
            int localCount = 0;
            DefaultedList<ItemStack> contents = toStacks(container);
            for (int i = 0; i < SHULKER_SLOTS; i++) {
                ItemStack inner = contents.get(i);
                if (!inner.isEmpty() && inner.getItem() == targetItem && inner.getCount() > localCount) {
                    localCount = inner.getCount();
                    localSlot = i;
                }
            }

            if (localSlot < 0) {
                continue; // this box doesn't contain the target
            }

            if (localCount > bestCount) {
                bestCount = localCount;
                bestPlayerSlot = playerSlot;
                bestInternalSlot = localSlot;
            }

            if (!preferLargestStack) {
                // First-found order: stop at the first box that has the item.
                break;
            }
        }

        if (bestPlayerSlot < 0) {
            return Optional.empty();
        }

        return Optional.of(buildResult(inventory.getStack(bestPlayerSlot), bestPlayerSlot, bestInternalSlot));
    }

    /**
     * Builds the immutable extraction plan from copies of the source box: removes the chosen
     * internal stack (FR-13) and updates the box's CONTAINER component, leaving a still-valid
     * (possibly empty) shulker box item (FR-14).
     */
    private static ExtractionResult buildResult(ItemStack sourceBox, int playerSlot, int internalSlot) {
        DefaultedList<ItemStack> contents = toStacks(sourceBox.getOrDefault(
                DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT));

        ItemStack extracted = contents.get(internalSlot).copy();
        contents.set(internalSlot, ItemStack.EMPTY); // FR-13: emptied internal slot

        ItemStack updatedBox = sourceBox.copy();
        updatedBox.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));

        return new ExtractionResult(playerSlot, internalSlot, extracted, updatedBox);
    }

    /** True if the item is any vanilla shulker box (covers all dyed + uncoloured variants, FR-09). */
    public static boolean isShulkerBox(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * True if {@code targetItem} is directly present in the hotbar / main inventory (and
     * optionally the off-hand) as a normal stack — i.e. vanilla pick block can already satisfy
     * it and the mod must not interfere (FR-02). Stacks <em>inside</em> shulker boxes do not
     * count here.
     */
    public static boolean containsDirectly(PlayerInventory inventory, Item targetItem, boolean scanOffhand) {
        if (inventory == null || targetItem == null) {
            return false;
        }
        for (int slot : scanOrder(scanOffhand)) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                return true;
            }
        }
        return false;
    }

    private static int[] scanOrder(boolean scanOffhand) {
        int n = MAIN_INVENTORY_MAX + 1 + (scanOffhand ? 1 : 0);
        int[] slots = new int[n];
        for (int i = 0; i <= MAIN_INVENTORY_MAX; i++) {
            slots[i] = i;
        }
        if (scanOffhand) {
            slots[n - 1] = OFF_HAND_SLOT;
        }
        return slots;
    }

    /** Copies a CONTAINER component into a fixed 27-slot list, preserving slot positions. */
    private static DefaultedList<ItemStack> toStacks(ContainerComponent container) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(SHULKER_SLOTS, ItemStack.EMPTY);
        container.copyTo(stacks);
        return stacks;
    }
}
