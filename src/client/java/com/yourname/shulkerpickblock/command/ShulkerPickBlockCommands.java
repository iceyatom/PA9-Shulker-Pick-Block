package com.yourname.shulkerpickblock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.ModConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * Registers the {@code /shulkerpickblock} client command (FR-24).
 *
 * <ul>
 *   <li>{@code /shulkerpickblock reload} — re-reads the TOML config at runtime, no restart.</li>
 *   <li>{@code /shulkerpickblock status} — prints the current effective configuration.</li>
 * </ul>
 *
 * <p>This is a purely client-side command (registered via {@code fabric-command-api-v2}'s
 * {@link ClientCommandManager}); it sends no packets to the server.
 */
public final class ShulkerPickBlockCommands {

    private ShulkerPickBlockCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal(ShulkerPickBlock.MOD_ID)
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            ModConfig.load();
                            ctx.getSource().sendFeedback(Text.literal(
                                    "[" + ShulkerPickBlock.MOD_NAME + "] Config reloaded."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal(
                                    "[" + ShulkerPickBlock.MOD_NAME + "] Current config:"));
                            for (String line : ModConfig.get().describe()) {
                                ctx.getSource().sendFeedback(Text.literal("  " + line));
                            }
                            return 1;
                        }))
        );
    }
}
