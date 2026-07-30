package com.yourname.shulkerpickblock.gui;

import com.yourname.shulkerpickblock.config.HotbarSlotStrategy;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * In-game configuration screen (FR-25), reachable from Mod Menu's gear button (via
 * {@link com.yourname.shulkerpickblock.compat.modmenu.ModMenuIntegration}) and from
 * {@code /shulkerpickblock config} so it also works without Mod Menu installed.
 *
 * <p>Built from vanilla widgets only — no Cloth Config / YACL dependency — so the mod stays
 * dependency-free at runtime apart from Fabric API.
 *
 * <p>Edits are made against a {@linkplain ModConfig#copy() working copy}: <em>Done</em> hands it
 * to {@link ModConfig#apply(ModConfig)} (applies + writes the TOML), while <em>Cancel</em> and
 * Escape discard it. That keeps this screen and {@code /shulkerpickblock reload} using the exact
 * same "swap the active instance" path, so nothing can drift out of sync.
 */
public class ShulkerPickBlockConfigScreen extends Screen {

    private static final int OPTION_WIDTH = 158;
    private static final int FOOTER_BUTTON_WIDTH = 100;
    private static final int COLUMNS = 2;

    private final Screen parent;
    private ModConfig working;

    /**
     * Rebuilt from scratch on every {@link #init()} — {@code init()} runs again on resize and
     * after {@link #rebuildWidgets()} (the Reset button), and re-adding to a retained layout
     * would stack a second copy of every widget on top of the first.
     */
    private HeaderAndFooterLayout layout;

    public ShulkerPickBlockConfigScreen(Screen parent) {
        super(Component.translatable("text.shulkerpickblock.config.title"));
        this.parent = parent;
        this.working = ModConfig.get().copy();
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);

        GridLayout grid = new GridLayout().spacing(4);
        GridLayout.RowHelper rows = grid.createRowHelper(COLUMNS);

        rows.addChild(toggle("enabled", () -> working.enabled, v -> working.enabled = v));
        rows.addChild(toggle("scan_offhand", () -> working.scanOffhand, v -> working.scanOffhand = v));

        rows.addChild(cycle("source_selection", SourceSelectionStrategy.values(),
                working.sourceSelection, v -> working.sourceSelection = v));
        rows.addChild(cycle("hotbar_slot_strategy", HotbarSlotStrategy.values(),
                working.hotbarSlotStrategy, v -> working.hotbarSlotStrategy = v));

        rows.addChild(toggle("show_hud_message", () -> working.showHudMessage,
                v -> working.showHudMessage = v));
        rows.addChild(new HudDurationSlider());

        rows.addChild(toggle("litematica_compat", () -> working.litematicaCompat,
                v -> working.litematicaCompat = v));
        rows.addChild(toggle("debug_logging", () -> working.debugLogging, v -> working.debugLogging = v));

        rows.addChild(new StringWidget(OPTION_WIDTH * COLUMNS + 4, 18,
                Component.translatable("text.shulkerpickblock.config.hint"), this.font), COLUMNS);

        this.layout.addToContents(grid);

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("text.shulkerpickblock.config.reset"),
                        b -> resetToDefaults())
                .width(FOOTER_BUTTON_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("text.shulkerpickblock.config.reset.tooltip")))
                .build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .width(FOOTER_BUTTON_WIDTH).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .width(FOOTER_BUTTON_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
        }
    }

    /** Escape / Cancel: drop the working copy, leaving the active config untouched. */
    @Override
    public void onClose() {
        // 26.2 moved screen management to Minecraft.gui; a null parent closes the screen
        // entirely (the /shulkerpickblock config path, which has no parent screen).
        this.minecraft.gui.setScreen(this.parent);
    }

    private void saveAndClose() {
        ModConfig.apply(this.working);
        // 26.2 moved screen management to Minecraft.gui; a null parent closes the screen
        // entirely (the /shulkerpickblock config path, which has no parent screen).
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Restores every option to its default; still needs Done to be written to disk. */
    private void resetToDefaults() {
        this.working = new ModConfig();
        this.rebuildWidgets();
    }

    // ---- Widget factories ----

    private AbstractWidget toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(getter.getAsBoolean())
                .withTooltip(v -> Tooltip.create(tooltipFor(key)))
                .create(0, 0, OPTION_WIDTH, 20, label(key), (button, value) -> setter.accept(value));
    }

    /**
     * A cycling button over an enum's constants. Labels and tooltips come from
     * {@code text.shulkerpickblock.config.<key>.<CONSTANT>} so every state is translatable.
     */
    private <T extends Enum<T>> AbstractWidget cycle(String key, T[] values, T initial, Consumer<T> setter) {
        return CycleButton.<T>builder(v -> valueLabel(key, v), initial)
                .withValues(values)
                .withTooltip(v -> Tooltip.create(valueTooltip(key, v)))
                .create(0, 0, OPTION_WIDTH, 20, label(key), (button, value) -> setter.accept(value));
    }

    /** Slider over the HUD notification duration, in ticks, clamped to the config's own range. */
    private class HudDurationSlider extends AbstractSliderButton {
        HudDurationSlider() {
            super(0, 0, OPTION_WIDTH, 20, Component.empty(),
                    toSliderValue(working.hudMessageDurationTicks));
            this.updateMessage();
            this.setTooltip(Tooltip.create(tooltipFor("hud_message_duration_ticks")));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("text.shulkerpickblock.config.hud_message_duration_ticks")
                    .append(": ")
                    .append(Component.translatable("text.shulkerpickblock.config.ticks", ticks())));
        }

        @Override
        protected void applyValue() {
            working.hudMessageDurationTicks = ticks();
        }

        private int ticks() {
            int span = ModConfig.HUD_DURATION_MAX_TICKS - ModConfig.HUD_DURATION_MIN_TICKS;
            return ModConfig.HUD_DURATION_MIN_TICKS + Mth.floor(this.value * span + 0.5D);
        }
    }

    private static double toSliderValue(int ticks) {
        int span = ModConfig.HUD_DURATION_MAX_TICKS - ModConfig.HUD_DURATION_MIN_TICKS;
        return Mth.clamp((double) (ticks - ModConfig.HUD_DURATION_MIN_TICKS) / span, 0.0D, 1.0D);
    }

    // ---- Translation-key helpers ----

    private static Component label(String key) {
        return Component.translatable("text.shulkerpickblock.config." + key);
    }

    private static Component tooltipFor(String key) {
        return Component.translatable("text.shulkerpickblock.config." + key + ".tooltip");
    }

    private static Component valueLabel(String key, Enum<?> value) {
        return Component.translatable("text.shulkerpickblock.config." + key + "."
                + value.name().toLowerCase(Locale.ROOT));
    }

    private static Component valueTooltip(String key, Enum<?> value) {
        return Component.translatable("text.shulkerpickblock.config." + key + "."
                + value.name().toLowerCase(Locale.ROOT) + ".tooltip");
    }
}
