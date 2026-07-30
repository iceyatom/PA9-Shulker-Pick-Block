package com.yourname.shulkerpickblock.config;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration backed by {@code config/shulkerpickblock.toml} (SRS §6, FR-23).
 *
 * <p>The file uses a flat key/value TOML subset (no nested tables), so a tiny hand-rolled
 * reader/writer is used instead of pulling in a TOML library — this keeps the mod free of
 * bundled dependencies beyond Fabric API. The config is reloadable at runtime via
 * {@code /shulkerpickblock reload} (FR-24); {@link #load()} simply re-reads the file and
 * repopulates the singleton fields, so other systems read {@link #get()} each time rather
 * than caching values.
 */
public final class ModConfig {
    public static final String FILE_NAME = ShulkerPickBlock.MOD_ID + ".toml";

    private static final int HUD_DURATION_MIN = 10;
    private static final int HUD_DURATION_MAX = 200;

    private static volatile ModConfig INSTANCE = new ModConfig();

    /** Lower bound of {@link #hudMessageDurationTicks}, exposed for the config screen's slider. */
    public static final int HUD_DURATION_MIN_TICKS = HUD_DURATION_MIN;
    /** Upper bound of {@link #hudMessageDurationTicks}, exposed for the config screen's slider. */
    public static final int HUD_DURATION_MAX_TICKS = HUD_DURATION_MAX;

    // ---- Options (defaults per SRS §6) ----
    public boolean enabled = true;
    public boolean scanOffhand = true;
    public SourceSelectionStrategy sourceSelection = SourceSelectionStrategy.DEFAULT;
    public HotbarSlotStrategy hotbarSlotStrategy = HotbarSlotStrategy.VANILLA;
    public boolean showHudMessage = true;
    public int hudMessageDurationTicks = 40;
    public boolean litematicaCompat = true;
    public boolean debugLogging = false;

    public static ModConfig get() {
        return INSTANCE;
    }

    /** A copy of this config — used by the config screen so Cancel can discard edits. */
    public ModConfig copy() {
        ModConfig c = new ModConfig();
        c.enabled = enabled;
        c.scanOffhand = scanOffhand;
        c.sourceSelection = sourceSelection;
        c.hotbarSlotStrategy = hotbarSlotStrategy;
        c.showHudMessage = showHudMessage;
        c.hudMessageDurationTicks = hudMessageDurationTicks;
        c.litematicaCompat = litematicaCompat;
        c.debugLogging = debugLogging;
        return c;
    }

    /**
     * Makes {@code cfg} the active config and writes it to disk (used by the config screen's
     * Save button, FR-25). Never throws: a failed write is logged and the in-memory values are
     * still applied, so the screen's edits always take effect for the session.
     */
    public static void apply(ModConfig cfg) {
        cfg.clamp();
        INSTANCE = cfg;
        try {
            cfg.save(configPath());
        } catch (IOException | RuntimeException e) {
            ShulkerPickBlock.LOGGER.warn("Failed to write {}; changes apply to this session only. Cause: {}",
                    FILE_NAME, e.toString());
        }
        if (cfg.debugLogging) {
            ShulkerPickBlock.LOGGER.info("Config saved: {}", cfg.summary());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Loads (or creates) the config file and swaps it in as the active instance.
     * Never throws: on any error it logs a warning and keeps/falls back to defaults
     * so a malformed file can never break pick block (NFR-04).
     */
    public static void load() {
        Path path = configPath();
        ModConfig cfg = new ModConfig();
        try {
            if (Files.notExists(path)) {
                cfg.save(path);
                ShulkerPickBlock.LOGGER.info("Created default config at {}", path);
            } else {
                Map<String, String> values = parse(Files.readAllLines(path, StandardCharsets.UTF_8));
                cfg.applyFrom(values);
                cfg.clamp();
                // Rewrite so any new keys / corrected ranges are persisted back.
                cfg.save(path);
            }
        } catch (IOException | RuntimeException e) {
            ShulkerPickBlock.LOGGER.warn("Failed to load {}; using defaults. Cause: {}", FILE_NAME, e.toString());
            cfg = new ModConfig();
        }
        INSTANCE = cfg;
        if (cfg.debugLogging) {
            ShulkerPickBlock.LOGGER.info("Config loaded: {}", cfg.summary());
        }
    }

    private void applyFrom(Map<String, String> v) {
        enabled = boolOr(v, "enabled", enabled);
        scanOffhand = boolOr(v, "scan_offhand", scanOffhand);
        sourceSelection = readSourceSelection(v);
        hotbarSlotStrategy = HotbarSlotStrategy.fromString(v.getOrDefault("hotbar_slot_strategy",
                hotbarSlotStrategy.name()));
        showHudMessage = boolOr(v, "show_hud_message", showHudMessage);
        hudMessageDurationTicks = intOr(v, "hud_message_duration_ticks", hudMessageDurationTicks);
        litematicaCompat = boolOr(v, "litematica_compat", litematicaCompat);
        debugLogging = boolOr(v, "debug_logging", debugLogging);
    }

    /**
     * Reads {@code source_selection}, falling back to the pre-1.1.0 boolean
     * {@code prefer_largest_stack} key so existing config files keep their meaning
     * ({@code true} → {@code LARGEST_STACK}, {@code false} → {@code FIRST_FOUND}). The legacy
     * key is not written back, so it disappears the first time the file is rewritten.
     */
    private SourceSelectionStrategy readSourceSelection(Map<String, String> v) {
        String value = v.get("source_selection");
        if (value != null) {
            return SourceSelectionStrategy.fromString(value);
        }
        if (v.containsKey("prefer_largest_stack")) {
            return boolOr(v, "prefer_largest_stack", true)
                    ? SourceSelectionStrategy.LARGEST_STACK
                    : SourceSelectionStrategy.FIRST_FOUND;
        }
        return sourceSelection;
    }

    private void clamp() {
        if (hudMessageDurationTicks < HUD_DURATION_MIN) {
            hudMessageDurationTicks = HUD_DURATION_MIN;
        } else if (hudMessageDurationTicks > HUD_DURATION_MAX) {
            hudMessageDurationTicks = HUD_DURATION_MAX;
        }
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, render().getBytes(StandardCharsets.UTF_8));
    }

    /** Renders the documented TOML file (SRS §6 wording mirrored in the comments). */
    private String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ShulkerPickBlock.MOD_NAME).append(" configuration\n");
        sb.append("# Reload at runtime with /shulkerpickblock reload\n\n");

        comment(sb, "Master toggle — disables all mod behaviour when false.");
        sb.append("enabled = ").append(enabled).append("\n\n");

        comment(sb, "Include the off-hand slot when searching for shulker boxes.");
        sb.append("scan_offhand = ").append(scanOffhand).append("\n\n");

        comment(sb, "LARGEST_STACK | SMALLEST_STACK | FIRST_FOUND — which matching stack to pull");
        comment(sb, "when several carried boxes hold the target item (FR-07).");
        comment(sb, "  LARGEST_STACK  = biggest stack wins; keeps stacks whole.");
        comment(sb, "  SMALLEST_STACK = smallest stack wins; uses up leftovers first.");
        comment(sb, "  FIRST_FOUND    = first match in slot order.");
        sb.append("source_selection = \"").append(sourceSelection.name()).append("\"\n\n");

        comment(sb, "VANILLA | CURRENT_SLOT | LRU — which hotbar slot receives the item (FR-06).");
        sb.append("hotbar_slot_strategy = \"").append(hotbarSlotStrategy.name()).append("\"\n\n");

        comment(sb, "Display a brief HUD notification when an item is pulled from a shulker box.");
        sb.append("show_hud_message = ").append(showHudMessage).append("\n\n");

        comment(sb, "How long (in game ticks) the HUD notification is shown. Range: 10-200.");
        sb.append("hud_message_duration_ticks = ").append(hudMessageDurationTicks).append("\n\n");

        comment(sb, "Enable the Litematica Easy Place compatibility layer (ignored if Litematica absent).");
        sb.append("litematica_compat = ").append(litematicaCompat).append("\n\n");

        comment(sb, "Write verbose scan diagnostics to the Fabric log. Development use only.");
        sb.append("debug_logging = ").append(debugLogging).append("\n");
        return sb.toString();
    }

    private static void comment(StringBuilder sb, String text) {
        sb.append("# ").append(text).append("\n");
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "enabled=%s scanOffhand=%s source=%s strategy=%s hud=%s/%dt litematica=%s debug=%s",
                enabled, scanOffhand, sourceSelection, hotbarSlotStrategy, showHudMessage,
                hudMessageDurationTicks, litematicaCompat, debugLogging);
    }

    // ---- Minimal flat-TOML parsing helpers ----

    private static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            out.put(key, unquote(value));
        }
        return out;
    }

    /** Strips a trailing {@code #} comment that is not inside a quoted string. */
    private static String stripComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean boolOr(Map<String, String> v, String key, boolean def) {
        String s = v.get(key);
        if (s == null) {
            return def;
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        return def;
    }

    private static int intOr(Map<String, String> v, String key, int def) {
        String s = v.get(key);
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Convenience for the Mod Menu screen / command output: a snapshot list of option lines. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("enabled = " + enabled);
        out.add("scan_offhand = " + scanOffhand);
        out.add("source_selection = " + sourceSelection);
        out.add("hotbar_slot_strategy = " + hotbarSlotStrategy);
        out.add("show_hud_message = " + showHudMessage);
        out.add("hud_message_duration_ticks = " + hudMessageDurationTicks);
        out.add("litematica_compat = " + litematicaCompat);
        out.add("debug_logging = " + debugLogging);
        return out;
    }
}
