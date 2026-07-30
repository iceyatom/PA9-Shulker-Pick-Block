package com.yourname.shulkerpickblock.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.yourname.shulkerpickblock.gui.ShulkerPickBlockConfigScreen;

/**
 * Puts the gear button on the mod's entry in Mod Menu's list (FR-25).
 *
 * <p>Declared under the {@code modmenu} entrypoint in {@code fabric.mod.json}. Mod Menu is a
 * {@code compileOnly} dependency and only Mod Menu itself reads that entrypoint key, so when Mod
 * Menu isn't installed this class is never loaded and its missing supertypes can't cause a
 * {@link NoClassDefFoundError}. Nothing else in the mod references this class — the screen it
 * returns is plain vanilla and is also reachable via {@code /shulkerpickblock config}.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ShulkerPickBlockConfigScreen::new;
    }
}
