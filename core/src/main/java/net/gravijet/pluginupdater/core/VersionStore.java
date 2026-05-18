package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.util.CC;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Persists the last-known version tag for each tracked plugin to {@code versions.yml}.
 *
 * <p>The store is used to detect when a newer GitHub release is available:
 * if the stored tag differs from the latest release tag, an update is needed.
 *
 * <p>All write operations are synchronised and flush to disk immediately so that
 * even a sudden JVM exit preserves progress.
 */
public class VersionStore {

    private final Path dataFolder;
    private final Logger logger;
    private final ConcurrentHashMap<String, String> versions = new ConcurrentHashMap<>();

    public VersionStore(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Loads (or creates) {@code versions.yml} from the data folder. */
    @SuppressWarnings("unchecked")
    public void load() throws IOException {
        Path file = dataFolder.resolve("versions.yml");

        if (!Files.exists(file)) {
            Files.createDirectories(dataFolder);
            Files.writeString(file,
                "# PluginUpdater version store — managed automatically, do not edit.\n",
                StandardCharsets.UTF_8);
            return;
        }

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream is = Files.newInputStream(file)) {
            Map<String, Object> data = yaml.load(is);
            if (data != null) {
                data.forEach((k, v) -> {
                    if (k != null && v != null) versions.put(k, v.toString());
                });
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns the stored version tag for {@code pluginName},
     * or {@code null} if this plugin has not been seen before.
     */
    public String getVersion(String pluginName) {
        return versions.get(pluginName);
    }

    /**
     * Records {@code version} as the current installed version of {@code pluginName}
     * and immediately flushes the store to disk.
     */
    public synchronized void setVersion(String pluginName, String version) {
        versions.put(pluginName, version);
        persist();
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void persist() {
        Path file = dataFolder.resolve("versions.yml");
        Path tmp  = file.resolveSibling("versions.yml.tmp");

        StringBuilder sb = new StringBuilder(
            "# PluginUpdater version store — managed automatically, do not edit.\n");
        // Quote both key and value to prevent YAML injection from special characters
        versions.forEach((name, ver) ->
            sb.append(quoteYaml(name)).append(": ").append(quoteYaml(ver)).append('\n')
        );

        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Failed to save versions.yml: " + e.getMessage()));
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /** Wraps {@code s} in YAML double-quoted style, escaping all characters that would
     *  produce invalid YAML if left literal inside a double-quoted scalar. */
    private static String quoteYaml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\0");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
