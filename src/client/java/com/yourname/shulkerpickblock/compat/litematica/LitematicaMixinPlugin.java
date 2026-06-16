package com.yourname.shulkerpickblock.compat.litematica;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;

/**
 * Mixin config plugin that gates the Litematica compat mixin so it is only applied when
 * Litematica is actually installed (FR-22, NFR-13).
 *
 * <p>Mixins are applied very early — before mod init and before the config is read — so this only
 * checks Litematica's <em>presence</em> (mod id + target class loadable). The runtime config
 * toggle {@code litematica_compat} is honoured later inside {@link LitematicaCompat#isActive()}.
 * If Litematica's target class is missing, the mixin is silently not applied and a warning is
 * logged, rather than letting a hard injection failure crash the client.
 */
public class LitematicaMixinPlugin implements IMixinConfigPlugin {

    private static final String LITEMATICA_TARGET = "fi.dy.masa.litematica.util.InventoryUtils";

    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        boolean modPresent = FabricLoader.getInstance().isModLoaded(ShulkerPickBlock.LITEMATICA_MOD_ID);
        boolean classPresent = modPresent && isClassPresent(LITEMATICA_TARGET);
        enabled = modPresent && classPresent;

        // DIAGNOSTIC: confirm whether the Litematica mixins are armed at all.
        ShulkerPickBlock.LOGGER.info("[Litematica] mixin plugin: modPresent={}, targetClassPresent={}, "
                + "mixinsEnabled={}", modPresent, classPresent, enabled);

        if (modPresent && !classPresent) {
            ShulkerPickBlock.LOGGER.warn("Litematica is present but {} was not found — Easy Place "
                    + "compat disabled (Litematica internals may have changed for this build).",
                    LITEMATICA_TARGET);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return enabled;
    }

    /**
     * Checks whether the target class is on the classpath <em>without loading it</em>.
     *
     * <p>This MUST NOT use {@code Class.forName}: doing so during the mixin-bootstrap phase forces
     * the target ({@code fi.dy.masa.litematica.util.InventoryUtils}) to be defined before Mixin is
     * ready to transform it, which makes our {@code @Pseudo} mixin fail with "target was loaded too
     * early" and silently never apply. Looking the class up as a {@code .class} resource answers the
     * presence question while leaving the class unloaded, so Mixin can transform it when Litematica
     * (or our injector) loads it normally later.
     */
    private static boolean isClassPresent(String className) {
        String resourcePath = className.replace('.', '/') + ".class";
        return LitematicaMixinPlugin.class.getClassLoader().getResource(resourcePath) != null;
    }

    // --- Remaining IMixinConfigPlugin methods: defaults ---

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(java.util.Set<String> myTargets, java.util.Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
