package com.yourname.shulkerpickblock.mixin.client;

import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.inventory.ShulkerExtractionService;
import com.yourname.shulkerpickblock.util.ShulkerInventoryHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extends vanilla pick block to source items from inventory shulker boxes (FR-01).
 *
 * <h2>What is intercepted and why</h2>
 * Middle-click pick block is resolved in {@code MinecraftClient.doItemPick()}: it computes the
 * stack for the targeted block, and in survival places it in the hotbar only if it already
 * exists in the inventory (otherwise it is a no-op). We inject at {@code TAIL} — <em>after</em>
 * vanilla has fully resolved (FR-01) — and only act when the item is still not present in the
 * inventory, i.e. vanilla could not satisfy the pick. In that case we hand off to
 * {@link ShulkerExtractionService} to pull the item from a shulker box.
 *
 * <h2>Deviation from SRS §7.1 (intentional, documented)</h2>
 * The SRS suggested targeting {@code ClientPlayerInteractionManager.pickFromInventory}, flagged
 * as "verify name". That method is only invoked on the <em>found-in-inventory</em> path, so it
 * never fires for the not-found case this mod must handle. The actual vanilla resolution lives in
 * {@code MinecraftClient.doItemPick()}, so we target that. The SRS constraint NFR-11 (target only
 * methods declared on the target class, never inherited) is preserved: {@code doItemPick} is
 * declared on {@code MinecraftClient}.
 *
 * <p>VERIFY against the 26.1.2 Yarn build: the method name {@code doItemPick}, the public fields
 * {@code crosshairTarget}/{@code world}/{@code player} on {@code MinecraftClient}, and the
 * pick-stack resolution call in {@link #shulkerpickblock$resolvePickStack}.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientPickBlockMixin {

    @Inject(method = "doItemPick", at = @At("TAIL"))
    private void shulkerpickblock$afterPick(CallbackInfo ci) {
        ModConfig config = ModConfig.get();
        if (!config.enabled) {
            return;
        }

        MinecraftClient client = (MinecraftClient) (Object) this;
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return;
        }

        // Only block picks are in scope (entities can't be stored in shulker boxes).
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        ItemStack target = shulkerpickblock$resolvePickStack(state, world, pos);
        if (target.isEmpty()) {
            return;
        }
        Item item = target.getItem();

        // FR-02: vanilla already had it (hotbar/inventory) or creative just spawned it — defer.
        if (ShulkerInventoryHelper.containsDirectly(player.getInventory(), item, config.scanOffhand)) {
            return;
        }

        // FR-03 … FR-08: vanilla could not supply the item — try the shulker boxes.
        ShulkerExtractionService.extract(client, item);
    }

    /**
     * Resolves the item a block pick would yield via the public {@code BlockState#getPickStack(
     * WorldView, BlockPos, boolean includeData)} (confirmed against 1.21.11 Yarn — the block-level
     * overload is protected). VERIFY unchanged in 26.1.2. {@code includeData=false} matches a plain
     * (non-ctrl) pick, which is all this mod needs since it matches by item identity.
     */
    private static ItemStack shulkerpickblock$resolvePickStack(BlockState state, ClientWorld world, BlockPos pos) {
        return state.getPickStack(world, pos, false);
    }
}
