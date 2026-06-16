package com.yourname.shulkerpickblock.compat.litematica.mixin;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>DIAGNOSTIC ONLY — remove (or set {@code debug_logging=false}) once the Easy Place flow is
 * mapped.</b> This class exists to answer one question empirically: <em>what is Litematica's real
 * Easy Place call chain on this exact build?</em>
 *
 * <h2>How it works</h2>
 * Rather than guessing Litematica's internal method names/descriptors (fragile across builds, and
 * tangled by the Yarn↔Mojmap↔intermediary remapping that bites {@code @Pseudo} soft-targets), this
 * hooks a method we <em>own</em> and can target reliably: the vanilla block-use funnel
 * {@code MultiPlayerGameMode.useItemOn}. Litematica's Easy Place ultimately routes through it to
 * place a ghost block. At {@code HEAD} we walk the current stack trace and, if any frame belongs to
 * Litematica ({@code fi.dy.masa.*}), we log just those frames. That printout <em>is</em> the Easy
 * Place call chain, in this build's real runtime names — e.g. you should see something like
 * {@code WorldUtils.handleEasyPlace} → {@code doEasyPlaceAction}. Decompile those methods to find
 * the item-lookup step, and that is where the real shulker-extraction hook belongs.
 *
 * <h2>Why it can't crash anything</h2>
 * <ul>
 *   <li>Lives in the Litematica mixin config, so {@link com.yourname.shulkerpickblock.compat.litematica.LitematicaMixinPlugin}
 *       only applies it when Litematica is actually installed.</li>
 *   <li>{@code require = 0}: if {@code useItemOn} is renamed in a future mapping, the injector
 *       matches nothing and silently no-ops instead of failing.</li>
 *   <li>Gated on {@code debug_logging}; does nothing in normal play.</li>
 *   <li>The handler swallows everything — a diagnostic must never affect gameplay.</li>
 * </ul>
 *
 * <h2>How to use it</h2>
 * <ol>
 *   <li>Set {@code debug_logging = true} in {@code config/shulkerpickblock.toml} (or
 *       {@code /shulkerpickblock reload} after editing).</li>
 *   <li>Put the needed item <em>directly in your hotbar</em> and do a normal, <em>successful</em>
 *       Easy Place — the chain only prints when placement actually reaches {@code useItemOn}.</li>
 *   <li>Read the {@code [EasyPlace trace]} lines in {@code logs/latest.log}; those Litematica
 *       frames are the methods to inspect/hook.</li>
 * </ol>
 *
 * <p>VERIFY: {@code MultiPlayerGameMode.useItemOn(...)} returning {@code InteractionResult} — stable
 * in recent Mojmap, but confirm on 26.1.2 if no trace ever prints during a successful Easy Place.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class EasyPlaceTraceMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0, expect = 0)
    private void shulkerpickblock$traceEasyPlace(CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (!ModConfig.get().debugLogging) {
                return;
            }

            StringBuilder litematicaFrames = new StringBuilder();
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().startsWith("fi.dy.masa")) {
                    litematicaFrames.append("\n    at ").append(frame);
                }
            }
            if (litematicaFrames.isEmpty()) {
                return; // Manual/vanilla interaction — no Litematica in the call stack; ignore.
            }

            String heldItem = describeHeldItem();
            ShulkerPickBlock.LOGGER.info("[EasyPlace trace] useItemOn reached via Litematica (held={}):{}",
                    heldItem, litematicaFrames);
        } catch (Throwable t) {
            // Diagnostics must never break gameplay (NFR-04).
        }
    }

    private static String describeHeldItem() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return "<no player>";
        }
        ItemStack held = client.player.getMainHandItem();
        return held.isEmpty() ? "<empty>" : (held.getItem() + " x" + held.getCount());
    }
}
