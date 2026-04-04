package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.util.CC;
import org.yaml.snakeyaml.Yaml;

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
        "# PluginUpdater — auto-update plugins from GitHub releases\n" +
        "#\n" +
        "# global token: used for all repos unless overridden per plugin.\n" +
        "# Leave empty (\"\") for public repos (no Authorization header sent).\n" +
        "github-access-token: \"\"\n" +
        "\n" +
        "# How often (in minutes) to check for updates while the server is running.\n" +
        "# Set to 0 to disable periodic checks (updates only on startup/shutdown).\n" +
        "check-interval-minutes: 30\n" +
        "\n" +
        "plugins:\n" +
        "  # Each entry needs: name, repo (owner/repo), asset-pattern (glob).\n" +
        "  # Optionally add access-token to override the global token for that repo.\n" +
        "  #\n" +
        "  # - name: \"EssentialsX\"\n" +
        "  #   repo: \"EssentialsX/Essentials\"\n" +
        "  #   asset-pattern: \"EssentialsX-*.jar\"\n" +
        "  #\n" +
        "  # - name: \"LuckPerms\"\n" +
        "  #   repo: \"LuckPerms/LuckPerms\"\n" +
        "  #   asset-pattern: \"LuckPerms-Bukkit-*.jar\"\n" +
        "  #\n" +
        "  # - name: \"PluginUpdater\"\n" +
        "  #   repo: \"yourname/PluginUpdater\"\n" +
        "  #   asset-pattern: \"PluginUpdater-*.jar\"\n";

    private final Path dataFolder;
    private final Logger logger;

    private String globalToken = "";
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

        if (!Files.exists(configFile)) {
            Files.createDirectories(dataFolder);
            Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            // Created default config.yml, no log to avoid spam
        }

        Yaml yaml = new Yaml();
        Map<String, Object> data;
        try (InputStream is = Files.newInputStream(configFile)) {
            data = yaml.load(is);
        }

        if (data == null) {
            logger.warning(CC.c("&c[PluginUpdater] &7config.yml appears empty — no plugins will be tracked."));
            return;
        }

        // Global token
        Object tok = data.get("github-access-token");
        globalToken = tok != null ? tok.toString().trim() : "";

        // Check interval
        Object interval = data.get("check-interval-minutes");
        if (interval != null) {
            try {
                checkIntervalMinutes = Math.max(0, Integer.parseInt(interval.toString().trim()));
            } catch (NumberFormatException e) {
                logger.warning(CC.c("&c[PluginUpdater] &7Invalid check-interval-minutes — using default (30)."));
            }
        }

        // Plugin list
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("plugins");
        if (list == null || list.isEmpty()) {
            // No plugins listed in config, no log to avoid spam
            return;
        }

        for (Map<String, Object> entry : list) {
            String name    = str(entry, "name");
            String repo    = str(entry, "repo");
            String pattern = str(entry, "asset-pattern");

            if (name == null || repo == null || pattern == null) {
                logger.warning(CC.c("&c[PluginUpdater] &7Skipping entry — missing &ename&7, &erepo&7, or &easset-pattern&7."));
                continue;
            }

            // Per-plugin token overrides global; null means "no token" (public repo)
            String perPlugin = str(entry, "access-token");
            String resolved  = (perPlugin != null) ? perPlugin
                             : (!globalToken.isEmpty() ? globalToken : null);

            plugins.add(new PluginEntry(name, repo, pattern, resolved));
            // Tracking plugin, no log to avoid spam
        }
    }

    public List<PluginEntry> getPlugins()           { return Collections.unmodifiableList(plugins); }
    public String            getGlobalToken()        { return globalToken; }
    /** Returns the periodic check interval in minutes, or 0 if disabled. */
    public int               getCheckIntervalMinutes() { return checkIntervalMinutes; }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Returns a trimmed string value from the map, or {@code null} if absent/blank. */
    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
