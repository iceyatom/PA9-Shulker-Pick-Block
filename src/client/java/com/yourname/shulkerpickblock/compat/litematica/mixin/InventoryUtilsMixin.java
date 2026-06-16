package com.yourname.shulkerpickblock.compat.litematica.mixin;

import com.yourname.shulkerpickblock.compat.litematica.LitematicaCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Soft-target compat mixin into Litematica's inventory utility (SRS §7.2, FR-17/FR-18).
 *
 * <p>{@link Pseudo} + a string class target mean this compiles and loads without any compile-time
 * Litematica dependency, which is essential because Litematica publishes no stable public API and
 * its internals may change between 26.1.2 builds (Risk: "Litematica API changes", HIGH).
 *
 * <h2>Safety / graceful degradation (FR-22)</h2>
 * <ul>
 *   <li>{@code LitematicaMixinPlugin} only applies this mixin when Litematica's target class is
 *       actually loadable.</li>
 *   <li>{@code require = 0, expect = 0} mean that if the target <em>method</em> has been renamed
 *       or its signature changed, the injector simply matches nothing and no-ops, instead of
 *       throwing and crashing the client.</li>
 *   <li>The handler ({@link LitematicaCompat#handlePick}) is fully guarded and never throws.</li>
 * </ul>
 *
 * <h2>VERIFY before shipping with Litematica</h2>
 * The target method below is a best-effort guess and <b>must be confirmed against the pinned
 * Litematica build for 26.1.2</b>. Litematica's Easy Place item-supply path historically lives in
 * {@code InventoryUtils} (e.g. a {@code setPickedItemToHand(ItemStack, MinecraftClient)}-style
 * method, or is reached via {@code WorldUtils.doSchematicWorldPickBlock}). Update {@code method}
 * and the handler parameters to match the real descriptor; until then this no-ops harmlessly.
 *
 * <p>We inject at {@code HEAD} and do <em>not</em> cancel: we merely pre-stage the required item
 * into the hotbar so Litematica's own logic then finds it and proceeds unchanged (FR-20).
 *
 * <h2>Real Easy Place entry point (confirmed against sakura-ryoko/litematica branch 26.2)</h2>
 * Easy Place's item supply runs through {@code WorldUtils.doEasyPlaceAction}, which calls
 * {@code InventoryUtils.schematicWorldPickBlock(ItemStack, BlockPos, Level, Minecraft)} and then
 * aborts if {@code EntityUtils.getUsedHandForItem} can't find the stack in hand. That is the method
 * we must pre-empt — the {@code setPickedItemToHand} hooks below sit on a different (schematic
 * pick-block hotkey) path and, being matched by explicit Mojmap descriptors under {@code remap=false},
 * historically never bound at runtime (production runs intermediary names). The
 * {@code schematicWorldPickBlock} hook is matched by <b>name only</b> (no descriptor) precisely so it
 * binds regardless of the runtime mapping set.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.InventoryUtils", remap = false)
public abstract class InventoryUtilsMixin {

    /**
     * The actual Easy Place / schematic pick-block item-supply call — confirmed via {@code javap} on
     * {@code litematica-fabric-26.1.2-0.27.4.jar}: both {@code WorldUtils.doEasyPlaceAction} and
     * {@code WorldUtils.doSchematicWorldPickBlock} invoke this exact method. The descriptor below is
     * the verbatim runtime signature (the 26.x runtime uses Mojang names, so {@code remap = false}
     * with these literal names matches directly). We pre-stage the item from a shulker box at HEAD so
     * Litematica's own inventory lookup that follows then finds it loose and swaps it to hand.
     */
    @Inject(
            method = "schematicWorldPickBlock(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void shulkerpickblock$beforeSchematicWorldPickBlock(ItemStack stack, BlockPos pos,
                                                                       Level schematicWorld, Minecraft mc,
                                                                       CallbackInfo ci) {
        LitematicaCompat.handlePick(stack);
    }

    @Inject(
            method = "setPickedItemToHand(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void shulkerpickblock$beforeSetPickedItem(ItemStack stack, Minecraft mc, CallbackInfo ci) {
        LitematicaCompat.handlePick(stack);
    }

    @Inject(
            method = "setPickedItemToHand(ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void shulkerpickblock$beforeSetPickedItemInSlot(int slot, ItemStack stack, Minecraft mc,
                                                                   CallbackInfo ci) {
        LitematicaCompat.handlePick(stack);
    }
}
