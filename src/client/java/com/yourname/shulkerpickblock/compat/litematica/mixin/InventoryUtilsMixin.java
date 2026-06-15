package com.yourname.shulkerpickblock.compat.litematica.mixin;

import com.yourname.shulkerpickblock.compat.litematica.LitematicaCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
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
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.InventoryUtils", remap = false)
public abstract class InventoryUtilsMixin {

    @Inject(
            method = "setPickedItemToHand",
            at = @At("HEAD"),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void shulkerpickblock$beforeSetPickedItem(ItemStack stack, MinecraftClient mc, CallbackInfo ci) {
        LitematicaCompat.handlePick(stack);
    }
}
