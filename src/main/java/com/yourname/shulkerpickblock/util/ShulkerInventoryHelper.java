package com.yourname.shulkerpickblock.util;

import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

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
 *   <li>Before 1.20.5: contents in NBT {@code BlockEntityTag.Items}, read/written with
 *       {@code ContainerHelper.loadAllItems/saveAllItems} (see {@code toStacks}/{@code writeStacks})</li>
 *   <li>{@code PlayerInventory#getStack(int)} indexing; off-hand at slot 40</li>
 * </ul>
 */
public final class ShulkerInventoryHelper {

    /** Off-hand slot index in {@link Inventory}. */
    public static final int OFF_HAND_SLOT = Inventory.SLOT_OFFHAND;

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
     * included only when {@code scanOffhand} is true (FR-10). Which of several matching stacks
     * wins is decided by {@code strategy} (FR-07) — see {@link SourceSelectionStrategy}. The
     * ranking is applied both <em>within</em> a box (across its 27 internal slots) and
     * <em>between</em> boxes, so the chosen stack is the global best under that strategy;
     * {@link SourceSelectionStrategy#FIRST_FOUND} instead stops at the very first match in slot
     * order.
     *
     * @return a populated plan, or {@link Optional#empty()} if no box holds the item
     */
    public static Optional<ExtractionResult> findAndExtract(Inventory inventory,
                                                            Item targetItem,
                                                            boolean scanOffhand,
                                                            SourceSelectionStrategy strategy) {
        if (inventory == null || targetItem == null) {
            return Optional.empty();
        }
        if (strategy == null) {
            strategy = SourceSelectionStrategy.DEFAULT;
        }

        int bestPlayerSlot = -1;
        int bestInternalSlot = -1;
        int bestCount = 0;

        // Iterate main inventory (0–35) then the off-hand (40); worst case 36×27 + 27 checks (NFR-01).
        for (int playerSlot : scanOrder(scanOffhand)) {
            ItemStack boxStack = inventory.getItem(playerSlot);
            if (!isShulkerBox(boxStack)) {
                continue;
            }


            // Pick this box's best matching internal slot under the active strategy.
            int localSlot = -1;
            int localCount = 0;
            NonNullList<ItemStack> contents = toStacks(boxStack);
            for (int i = 0; i < SHULKER_SLOTS; i++) {
                ItemStack inner = contents.get(i);
                if (inner.isEmpty() || inner.getItem() != targetItem) {
                    continue;
                }
                if (localSlot < 0 || strategy.isBetter(inner.getCount(), localCount)) {
                    localCount = inner.getCount();
                    localSlot = i;
                    if (strategy.stopsAtFirstMatch()) {
                        break;
                    }
                }
            }

            if (localSlot < 0) {
                continue; // this box doesn't contain the target
            }

            if (bestPlayerSlot < 0 || strategy.isBetter(localCount, bestCount)) {
                bestCount = localCount;
                bestPlayerSlot = playerSlot;
                bestInternalSlot = localSlot;
            }

            if (strategy.stopsAtFirstMatch()) {
                break;
            }
        }

        if (bestPlayerSlot < 0) {
            return Optional.empty();
        }

        return Optional.of(buildResult(inventory.getItem(bestPlayerSlot), bestPlayerSlot, bestInternalSlot));
    }

    /**
     * Builds the immutable extraction plan from copies of the source box: removes the chosen
     * internal stack (FR-13) and updates the box's stored contents (NBT), leaving a still-valid
     * (possibly empty) shulker box item (FR-14).
     */
    private static ExtractionResult buildResult(ItemStack sourceBox, int playerSlot, int internalSlot) {
        NonNullList<ItemStack> contents = toStacks(sourceBox);

        ItemStack extracted = contents.get(internalSlot).copy();
        contents.set(internalSlot, ItemStack.EMPTY); // FR-13: emptied internal slot

        ItemStack updatedBox = sourceBox.copy();
        writeStacks(updatedBox, contents);

        return new ExtractionResult(playerSlot, internalSlot, extracted, updatedBox);
    }

    /**
     * Returns a copy of {@code box} with {@code stack} placed into internal slot {@code slot}
     * (expected to be empty — e.g. the slot a prior extraction just vacated). Used to swap a
     * displaced held item back into the box instead of destroying it when the destination hotbar
     * slot is already occupied and the inventory has no room. Leaves a valid shulker box item.
     */
    public static ItemStack withInternalItem(ItemStack box, int slot, ItemStack stack) {
        NonNullList<ItemStack> contents = toStacks(box);
        contents.set(slot, stack.copy());
        ItemStack updated = box.copy();
        writeStacks(updated, contents);
        return updated;
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
    public static boolean containsDirectly(Inventory inventory, Item targetItem, boolean scanOffhand) {
        if (inventory == null || targetItem == null) {
            return false;
        }
        for (int slot : scanOrder(scanOffhand)) {
            ItemStack stack = inventory.getItem(slot);
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

    /**
     * Reads a shulker box item's contents into a fixed 27-slot list, preserving slot positions.
     * Before 1.20.5 the contents live in NBT ({@code BlockEntityTag.Items}), read the same way the
     * shulker box block entity loads them ({@link ContainerHelper#loadAllItems}).
     */
    private static NonNullList<ItemStack> toStacks(ItemStack box) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
        CompoundTag tag = BlockItem.getBlockEntityData(box);
        if (tag != null && tag.contains(ShulkerBoxBlockEntity.ITEMS_TAG, 9)) { // 9 = list tag
            ContainerHelper.loadAllItems(tag, stacks);
        }
        return stacks;
    }

    /**
     * Writes {@code stacks} back as {@code box}'s contents (the NBT counterpart of setting the
     * CONTAINER component). Any other block-entity data on the item is kept; an all-empty list
     * leaves a valid, empty shulker box, exactly as vanilla saves one.
     */
    private static void writeStacks(ItemStack box, NonNullList<ItemStack> stacks) {
        CompoundTag existing = BlockItem.getBlockEntityData(box);
        CompoundTag tag = existing == null ? new CompoundTag() : existing.copy();
        tag.remove(ShulkerBoxBlockEntity.ITEMS_TAG);
        tag.remove("id"); // re-added by setBlockEntityData when the tag is non-empty
        ContainerHelper.saveAllItems(tag, stacks, false);
        BlockItem.setBlockEntityData(box, BlockEntityType.SHULKER_BOX, tag);
    }
}
