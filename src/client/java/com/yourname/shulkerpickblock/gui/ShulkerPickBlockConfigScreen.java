package com.yourname.shulkerpickblock.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.shulkerpickblock.config.HotbarSlotStrategy;
import com.yourname.shulkerpickblock.config.ModConfig;
import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.TooltipAccessor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;
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
 *
 * <p><b>1.19 – 1.19.2:</b> these versions predate the {@code HeaderAndFooterLayout} /
 * {@code GridLayout} / {@code LinearLayout} / {@code StringWidget} / {@code Tooltip} classes the
 * newer releases build this screen from, so the same layout is placed by hand with the newer
 * layout's metrics (33px header and footer, title centred in the header, the 2-column grid centred
 * horizontally 30px below the header, the footer row centred with 8px gaps), {@link Label} stands
 * in for {@code StringWidget}, and tooltips (wrapped at the newer {@code Tooltip}'s 170px) are drawn
 * in {@link #render}. {@code Screen.render} here doesn't draw the background, so it is drawn first.
 */
public class ShulkerPickBlockConfigScreen extends Screen {

    private static final int OPTION_WIDTH = 158;
    private static final int OPTION_HEIGHT = 20;
    private static final int FOOTER_BUTTON_WIDTH = 100;
    private static final int COLUMNS = 2;
    private static final int GRID_SPACING = 4;
    private static final int FOOTER_SPACING = 8;
    private static final int HINT_HEIGHT = 18;
    /** Same metrics as the newer releases' {@code HeaderAndFooterLayout}. */
    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 33;
    private static final int CONTENT_MARGIN_TOP = 30;
    /** Width {@code Tooltip.create} wraps at in the newer releases. */
    private static final int TOOLTIP_WIDTH = 170;

    private final Screen parent;
    private ModConfig working;

    public ShulkerPickBlockConfigScreen(Screen parent) {
        super(Component.translatable("text.shulkerpickblock.config.title"));
        this.parent = parent;
        this.working = ModConfig.get().copy();
    }

    /**
     * Places every widget from scratch — {@code init()} runs again on resize and after
     * {@link #rebuildWidgets()} (the Reset button), which clears the previous widgets first.
     */
    @Override
    protected void init() {
        // Header: the title, centred in the header band.
        int titleWidth = this.font.width(this.title);
        this.addRenderableWidget(new Label((this.width - titleWidth) / 2, (HEADER_HEIGHT - 9) / 2,
                titleWidth, 9, this.title, this.font));

        // Contents: a 2-column grid of options plus a full-width hint row.
        int gridWidth = OPTION_WIDTH * COLUMNS + GRID_SPACING * (COLUMNS - 1);
        int optionRows = 4;
        int gridHeight = optionRows * (OPTION_HEIGHT + GRID_SPACING) + HINT_HEIGHT;
        int gridX = (this.width - gridWidth) / 2;
        int gridY = Math.min(HEADER_HEIGHT + CONTENT_MARGIN_TOP, this.height - FOOTER_HEIGHT - gridHeight);

        AbstractWidget[] options = {
                toggle("enabled", () -> working.enabled, v -> working.enabled = v),
                toggle("scan_offhand", () -> working.scanOffhand, v -> working.scanOffhand = v),
                cycle("source_selection", SourceSelectionStrategy.values(),
                        working.sourceSelection, v -> working.sourceSelection = v),
                cycle("hotbar_slot_strategy", HotbarSlotStrategy.values(),
                        working.hotbarSlotStrategy, v -> working.hotbarSlotStrategy = v),
                toggle("show_hud_message", () -> working.showHudMessage,
                        v -> working.showHudMessage = v),
                new HudDurationSlider(),
                toggle("litematica_compat", () -> working.litematicaCompat,
                        v -> working.litematicaCompat = v),
                toggle("debug_logging", () -> working.debugLogging, v -> working.debugLogging = v),
        };
        for (int i = 0; i < options.length; i++) {
            AbstractWidget option = options[i];
            option.x = gridX + (i % COLUMNS) * (OPTION_WIDTH + GRID_SPACING);
            option.y = gridY + (i / COLUMNS) * (OPTION_HEIGHT + GRID_SPACING);
            this.addRenderableWidget(option);
        }

        this.addRenderableWidget(new Label(gridX, gridY + optionRows * (OPTION_HEIGHT + GRID_SPACING),
                gridWidth, HINT_HEIGHT, Component.translatable("text.shulkerpickblock.config.hint"), this.font));

        // Footer: Reset / Cancel / Done, centred in the footer band with 8px gaps.
        int footerWidth = FOOTER_BUTTON_WIDTH * 3 + FOOTER_SPACING * 2;
        int footerX = (this.width - footerWidth) / 2;
        int footerY = this.height - FOOTER_HEIGHT + (FOOTER_HEIGHT - OPTION_HEIGHT) / 2;
        this.addRenderableWidget(new TooltipButton(footerX, footerY,
                Component.translatable("text.shulkerpickblock.config.reset"), b -> resetToDefaults(),
                Component.translatable("text.shulkerpickblock.config.reset.tooltip")));
        this.addRenderableWidget(new Button(footerX + FOOTER_BUTTON_WIDTH + FOOTER_SPACING, footerY,
                FOOTER_BUTTON_WIDTH, OPTION_HEIGHT, CommonComponents.GUI_CANCEL, b -> onClose()));
        this.addRenderableWidget(new Button(footerX + 2 * (FOOTER_BUTTON_WIDTH + FOOTER_SPACING), footerY,
                FOOTER_BUTTON_WIDTH, OPTION_HEIGHT, CommonComponents.GUI_DONE, b -> saveAndClose()));
    }

    /** Background, widgets, then the hovered widget's tooltip on top (as the newer releases do). */
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        for (GuiEventListener child : this.children()) {
            if (child instanceof TooltipAccessor accessor && child.isMouseOver(mouseX, mouseY)) {
                List<FormattedCharSequence> tooltip = accessor.getTooltip();
                if (!tooltip.isEmpty()) {
                    this.renderTooltip(poseStack, tooltip, mouseX, mouseY);
                }
                break;
            }
        }
    }

    /** Escape / Cancel: drop the working copy, leaving the active config untouched. */
    @Override
    public void onClose() {
        // Pre-26.2: screens are set on Minecraft itself; a null parent closes the screen
        // entirely (the /shulkerpickblock config path, which has no parent screen).
        this.minecraft.setScreen(this.parent);
    }

    private void saveAndClose() {
        ModConfig.apply(this.working);
        // Pre-26.2: screens are set on Minecraft itself; a null parent closes the screen
        // entirely (the /shulkerpickblock config path, which has no parent screen).
        this.minecraft.setScreen(this.parent);
    }

    /** Restores every option to its default; still needs Done to be written to disk. */
    private void resetToDefaults() {
        this.working = new ModConfig();
        this.rebuildWidgets();
    }

    // ---- Widget factories ----

    private AbstractWidget toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(getter.getAsBoolean())
                .withTooltip(v -> wrap(tooltipFor(key)))
                .create(0, 0, OPTION_WIDTH, OPTION_HEIGHT, label(key), (button, value) -> setter.accept(value));
    }

    /**
     * A cycling button over an enum's constants. Labels and tooltips come from
     * {@code text.shulkerpickblock.config.<key>.<CONSTANT>} so every state is translatable.
     */
    private <T extends Enum<T>> AbstractWidget cycle(String key, T[] values, T initial, Consumer<T> setter) {
        return CycleButton.<T>builder(v -> valueLabel(key, v))
                .withValues(values)
                .withInitialValue(initial)
                .withTooltip(v -> wrap(valueTooltip(key, v)))
                .create(0, 0, OPTION_WIDTH, OPTION_HEIGHT, label(key), (button, value) -> setter.accept(value));
    }

    private List<FormattedCharSequence> wrap(Component text) {
        return this.font.split(text, TOOLTIP_WIDTH);
    }

    /** Slider over the HUD notification duration, in ticks, clamped to the config's own range. */
    private class HudDurationSlider extends AbstractSliderButton implements TooltipAccessor {
        HudDurationSlider() {
            super(0, 0, OPTION_WIDTH, OPTION_HEIGHT, Component.empty(),
                    toSliderValue(working.hudMessageDurationTicks));
            this.updateMessage();
        }

        @Override
        public List<FormattedCharSequence> getTooltip() {
            return wrap(tooltipFor("hud_message_duration_ticks"));
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

    /** A footer button with a tooltip (the newer releases' {@code Button.builder().tooltip(...)}). */
    private class TooltipButton extends Button implements TooltipAccessor {
        private final Component tooltip;

        TooltipButton(int x, int y, Component message, OnPress onPress, Component tooltip) {
            super(x, y, FOOTER_BUTTON_WIDTH, OPTION_HEIGHT, message, onPress);
            this.tooltip = tooltip;
        }

        @Override
        public List<FormattedCharSequence> getTooltip() {
            return wrap(this.tooltip);
        }
    }

    /**
     * Stand-in for the newer releases' {@code StringWidget}: inactive (never focused or clicked),
     * text centred horizontally and vertically in its box, white with a shadow, no narration.
     */
    private static class Label extends AbstractWidget {
        private final Font font;

        Label(int x, int y, int width, int height, Component message, Font font) {
            super(x, y, width, height, message);
            this.font = font;
            this.active = false;
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            Component message = this.getMessage();
            int textX = this.x + Math.round(0.5F * (this.getWidth() - this.font.width(message)));
            int textY = this.y + (this.getHeight() - 9) / 2;
            this.font.drawShadow(poseStack, message, textX, textY, 0xFFFFFF);
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
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
