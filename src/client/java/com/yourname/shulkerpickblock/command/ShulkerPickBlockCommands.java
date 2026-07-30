package com.yourname.shulkerpickblock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.gui.ShulkerPickBlockConfigScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Registers the {@code /shulkerpickblock} client command (FR-24).
 *
 * <ul>
 *   <li>{@code /shulkerpickblock config} — opens the settings screen (same one Mod Menu's gear
 *       button opens, so the GUI is reachable without Mod Menu installed).</li>
 *   <li>{@code /shulkerpickblock reload} — re-reads the TOML config at runtime, no restart.</li>
 *   <li>{@code /shulkerpickblock status} — prints the current effective configuration.</li>
 * </ul>
 *
 * <p>This is a purely client-side command (registered via {@code fabric-command-api-v2}'s
 * {@link ClientCommands}); it sends no packets to the server.
 */
public final class ShulkerPickBlockCommands {

    private ShulkerPickBlockCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal(ShulkerPickBlock.MOD_ID)
                        .then(ClientCommands.literal("config").executes(ctx -> {
                            // Deferred to the next client tick: the chat screen is still being
                            // closed as the command runs, and it would overwrite our screen.
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> client.gui.setScreen(
                                    new ShulkerPickBlockConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(ctx -> {
                            ModConfig.load();
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + ShulkerPickBlock.MOD_NAME + "] Config reloaded."));
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + ShulkerPickBlock.MOD_NAME + "] Current config:"));
                            for (String line : ModConfig.get().describe()) {
                                ctx.getSource().sendFeedback(Component.literal("  " + line));
                            }
                            return 1;
                        }))
        );
    }
}
