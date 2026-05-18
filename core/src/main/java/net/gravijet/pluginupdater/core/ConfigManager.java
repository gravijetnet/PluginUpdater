package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.util.CC;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and exposes the plugin configuration from {@code config.yml}.
 *
 * <p>If the file does not exist it is created with annotated defaults so the
 * server operator can fill in their plugin list without hunting for documentation.
 */
public class ConfigManager {

    // ── Default config written on first start ──────────────────────────────
    private static final String DEFAULT_CONFIG =
        "# PluginUpdater — auto-update plugins from GitHub or Modrinth releases\n" +
        "#\n" +
        "# Global GitHub token: used for all GitHub repos unless overridden per plugin.\n" +
        "# Leave empty (\"\") for public repos (no Authorization header sent).\n" +
        "github-access-token: \"\"\n" +
        "\n" +
        "# Global Modrinth token: used for all Modrinth projects unless overridden per plugin.\n" +
        "# Optional — only needed for private projects or to raise the rate limit.\n" +
        "modrinth-access-token: \"\"\n" +
        "\n" +
        "# How often (in minutes) to check for updates while the server is running.\n" +
        "# Set to 0 to disable periodic checks (updates only on startup/shutdown).\n" +
        "check-interval-minutes: 30\n" +
        "\n" +
        "plugins:\n" +
        "  # Each entry needs: name, repo, asset-pattern (glob), and optionally source.\n" +
        "  # source: github  (default) — repo is \"owner/repo\"\n" +
        "  # source: modrinth         — repo is the Modrinth project slug or ID\n" +
        "  # Optionally add access-token to override the global token for that entry.\n" +
        "  #\n" +
        "  # --- GitHub examples ---\n" +
        "  # - name: \"EssentialsX\"\n" +
        "  #   source: github\n" +
        "  #   repo: \"EssentialsX/Essentials\"\n" +
        "  #   asset-pattern: \"EssentialsX-*.jar\"\n" +
        "  #\n" +
        "  # - name: \"LuckPerms\"\n" +
        "  #   source: github\n" +
        "  #   repo: \"LuckPerms/LuckPerms\"\n" +
        "  #   asset-pattern: \"LuckPerms-Bukkit-*.jar\"\n" +
        "  #\n" +
        "  # --- Modrinth examples ---\n" +
        "  # - name: \"Sodium\"\n" +
        "  #   source: modrinth\n" +
        "  #   repo: \"sodium\"\n" +
        "  #   asset-pattern: \"sodium-fabric-*.jar\"\n" +
        "  #\n" +
        "  # - name: \"Geyser\"\n" +
        "  #   source: modrinth\n" +
        "  #   repo: \"geyser\"\n" +
        "  #   asset-pattern: \"Geyser-Spigot.jar\"\n";

    private final Path dataFolder;
    private final Logger logger;

    private String globalToken = "";
    private String modrinthGlobalToken = "";
    private int checkIntervalMinutes = 30;
    private final List<PluginEntry> plugins = new ArrayList<>();

    public ConfigManager(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Loads (or creates) {@code config.yml} from the data folder. */
    @SuppressWarnings("unchecked")
    public void load() throws IOException {
        Path configFile = dataFolder.resolve("config.yml");
        // Reset all fields so re-loading a config with missing keys reverts to defaults.
        globalToken = "";
        modrinthGlobalToken = "";
        checkIntervalMinutes = 30;
        plugins.clear();

        if (!Files.exists(configFile)) {
            Files.createDirectories(dataFolder);
            Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            // Created default config.yml, no log to avoid spam
        }

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> data;
        try (InputStream is = Files.newInputStream(configFile)) {
            data = yaml.load(is);
        }

        if (data == null) {
            logger.warning(CC.c("&c[PluginUpdater] &7config.yml appears empty — no plugins will be tracked."));
            return;
        }

        // Global GitHub token
        Object tok = data.get("github-access-token");
        globalToken = tok != null ? tok.toString().trim() : "";

        // Global Modrinth token
        Object modrinthTok = data.get("modrinth-access-token");
        modrinthGlobalToken = modrinthTok != null ? modrinthTok.toString().trim() : "";

        // Check interval
        Object interval = data.get("check-interval-minutes");
        if (interval != null) {
            if (interval instanceof Number) {
                checkIntervalMinutes = Math.max(0, ((Number) interval).intValue());
            } else {
                try {
                    checkIntervalMinutes = Math.max(0, Integer.parseInt(interval.toString().trim()));
                } catch (NumberFormatException e) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Invalid check-interval-minutes — using default (30)."));
                }
            }
        }

        // Plugin list
        Object pluginsRaw = data.get("plugins");
        if (pluginsRaw == null) {
            return;
        }
        if (!(pluginsRaw instanceof List)) {
            logger.warning(CC.c("&c[PluginUpdater] &7'plugins' in config.yml must be a YAML list — no plugins will be tracked."));
            return;
        }
        List<?> list = (List<?>) pluginsRaw;
        if (list.isEmpty()) {
            return;
        }

        for (Object raw : list) {
            if (!(raw instanceof Map)) {
                logger.warning(CC.c("&c[PluginUpdater] &7Skipping malformed plugin entry (expected a YAML mapping)."));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) raw;
            String name    = str(entry, "name");
            String repo    = str(entry, "repo");
            String pattern = str(entry, "asset-pattern");

            if (name == null || repo == null || pattern == null) {
                logger.warning(CC.c("&c[PluginUpdater] &7Skipping entry — missing &ename&7, &erepo&7, or &easset-pattern&7."));
                continue;
            }

            // Determine source (defaults to GITHUB if not specified)
            String sourceStr = str(entry, "source");
            PluginEntry.Source source;
            if ("modrinth".equalsIgnoreCase(sourceStr)) {
                source = PluginEntry.Source.MODRINTH;
            } else {
                source = PluginEntry.Source.GITHUB;
            }

            // Per-plugin token overrides the appropriate global token
            String perPlugin = str(entry, "access-token");
            String resolved;
            if (perPlugin != null) {
                resolved = perPlugin;
            } else if (source == PluginEntry.Source.MODRINTH) {
                resolved = !modrinthGlobalToken.isEmpty() ? modrinthGlobalToken : null;
            } else {
                resolved = !globalToken.isEmpty() ? globalToken : null;
            }

            plugins.add(new PluginEntry(name, repo, pattern, resolved, source));
            // Tracking plugin, no log to avoid spam
        }
    }

    public List<PluginEntry> getPlugins()              { return Collections.unmodifiableList(plugins); }
    public String            getGlobalToken()           { return globalToken; }
    public String            getModrinthGlobalToken()   { return modrinthGlobalToken; }
    /** Returns the periodic check interval in minutes, or 0 if disabled. */
    public int               getCheckIntervalMinutes()  { return checkIntervalMinutes; }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Returns a trimmed string value from the map, or {@code null} if absent/blank. */
    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
