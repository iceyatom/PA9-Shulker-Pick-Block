package com.yourname.shulkerpickblock.hud;

import com.yourname.shulkerpickblock.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * A brief, self-expiring HUD notification shown when an item is pulled from a shulker box
 * (FR-08, {@code show_hud_message}). State is a tiny countdown driven by the client tick; it is
 * client-thread only so it needs no synchronisation.
 *
 * <p>The render hook ({@code HudRenderCallback}) and tick hook are registered in
 * {@code ShulkerPickBlockClient}. VERIFY the Fabric HUD render API name/signature against the
 * version of Fabric API bundled for 26.1.2 — recent Fabric API revisions moved from
 * {@code HudRenderCallback} to a layered HUD registration API.
 */
public final class PickBlockHud {
    private static ItemStack stack = ItemStack.EMPTY;
    private static int remainingTicks;

    private PickBlockHud() {
    }

    /** Starts the notification for the configured duration. */
    public static void show(ItemStack extracted) {
        stack = extracted == null ? ItemStack.EMPTY : extracted.copy();
        remainingTicks = ModConfig.get().hudMessageDurationTicks;
    }

    /** Decrement the countdown; call once per client tick. */
    public static void tick() {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    /** Draws the notification just above the hotbar while it is active. */
    public static void render(DrawContext context) {
        if (remainingTicks <= 0 || stack.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }

        Text message = Text.translatable("hud.shulkerpickblock.picked", stack.getName());
        int width = client.textRenderer.getWidth(message);
        int x = (context.getScaledWindowWidth() - width) / 2;
        int y = context.getScaledWindowHeight() - 59 - 13;

        // Fade out over the final 10 ticks.
        int alpha = (int) (Math.min(1.0f, remainingTicks / 10.0f) * 255.0f);
        int color = (alpha << 24) | 0xFFFFFF;
        context.drawTextWithShadow(client.textRenderer, message, x, y, color);
    }
}
