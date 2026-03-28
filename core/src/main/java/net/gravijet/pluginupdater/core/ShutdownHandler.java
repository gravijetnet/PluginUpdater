package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.model.UpdateInfo;
import net.gravijet.pluginupdater.core.util.CC;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Orchestrates update checks and JAR replacements at server shutdown.
 *
 * <h2>Shutdown hook design</h2>
 * <p>A JVM shutdown hook is registered via {@link Runtime#addShutdownHook(Thread)}.
 * This fires on:
 * <ul>
 *   <li>Clean shutdowns ({@code /stop}, {@code end})
 *   <li>SIGTERM (most process managers)
 *   <li>SIGKILL on Linux/macOS (the OS delivers it; the JVM still runs finalizers)
 * </ul>
 * <p>The hook spawns a worker thread and {@link Thread#join(long) joins} it with a
 * 30-second timeout so downloads can complete before the JVM exits.
 *
 * <h2>First-run behaviour</h2>
 * <p>If a plugin has no stored version (first run), the latest GitHub release tag is
 * recorded in {@code versions.yml} <em>without</em> downloading — the assumption is
 * that the currently installed JAR is "current".  On the next shutdown, a real
 * version comparison is possible and an actual update can be applied.
 *
 * <h2>Self-update</h2>
 * <p>Register the plugin's own JAR path with {@link #registerKnownJar} during
 * {@code onEnable}. The new JAR is then placed at that exact path, atomically
 * replacing the old one.  On Linux this works even for a loaded JAR (the inode is
 * unlinked but the JVM keeps reading from its already-open file descriptor).
 * On Windows the file may be locked; {@link java.io.File#deleteOnExit()} is used
 * as a fallback so the OS cleans up on next exit.
 */
public class ShutdownHandler {

    /** Maximum time to wait for the update worker before giving up. */
    private static final long TIMEOUT_MS = 30_000L;

    private final ConfigManager    config;
    private final VersionStore     versions;
    private final GitHubUpdateChecker checker;
    private final FileUpdater      fileUpdater;
    private final Path             pluginsFolder;
    private final Logger           logger;

    /**
     * Pre-registered JAR locations for plugins whose JAR path is known ahead of time
     * (e.g. the updater's own JAR, obtained from the class-loader / plugin API).
     * Key = plugin name as configured in config.yml (case-sensitive).
     */
    private final Map<String, Path> knownJarPaths = new HashMap<>();

    /** Ensures update logic runs at most once per JVM lifecycle. */
    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    public ShutdownHandler(ConfigManager config, VersionStore versions,
                           GitHubUpdateChecker checker, FileUpdater fileUpdater,
                           Path pluginsFolder, Logger logger) {
        this.config        = config;
        this.versions      = versions;
        this.checker       = checker;
        this.fileUpdater   = fileUpdater;
        this.pluginsFolder = pluginsFolder;
        this.logger        = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Pre-registers the JAR file for a plugin so the updater can replace it exactly.
     * Call this during {@code onEnable} for the updater's own JAR at minimum.
     */
    public void registerKnownJar(String pluginName, Path jarPath) {
        knownJarPaths.put(pluginName, jarPath);
    }

    /**
     * Registers the JVM shutdown hook.  Must be called once during plugin enable.
     * The hook is a safety net that fires even when the server is killed forcibly.
     */
    public void registerShutdownHook() {
        Thread hook = new Thread(this::runUpdates, "PluginUpdater-ShutdownHook");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
        logger.info(CC.c("&e[PluginUpdater] &7Shutdown hook registered."));
    }

    /**
     * Called from the platform's {@code onDisable} / {@code ProxyShutdownEvent}.
     * Triggers updates on clean shutdowns; the hook handles forced kills.
     * The {@link AtomicBoolean} guard ensures we never run twice.
     */
    public void onDisable() {
        runUpdates();
    }

    // ── Core logic ─────────────────────────────────────────────────────────

    /** Entry point — runs update checks in a background thread, then waits up to 30 s. */
    private void runUpdates() {
        if (!hasRun.compareAndSet(false, true)) {
            return; // already ran (onDisable fired before the hook, or vice-versa)
        }

        Thread worker = new Thread(() -> {
            logger.info(CC.c("&e[PluginUpdater] &7Checking for updates on shutdown..."));

            List<PluginEntry> plugins = config.getPlugins();
            if (plugins.isEmpty()) {
                logger.info(CC.c("&e[PluginUpdater] &7No plugins configured — nothing to check."));
                return;
            }

            for (PluginEntry entry : plugins) {
                try {
                    processPlugin(entry);
                } catch (Exception e) {
                    // Never let one bad plugin interrupt the others
                    logger.warning(CC.c("&c[PluginUpdater] &7Unexpected error for &f"
                        + entry.getName() + "&7: " + e.getMessage()));
                }
            }

            logger.info(CC.c("&a[PluginUpdater] &7Update check complete."));
        }, "PluginUpdater-Worker");

        worker.setDaemon(false);
        worker.start();

        try {
            worker.join(TIMEOUT_MS);
            if (worker.isAlive()) {
                logger.warning(CC.c("&c[PluginUpdater] &7Worker timed out after "
                    + (TIMEOUT_MS / 1000) + " s — interrupting."));
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning(CC.c("&c[PluginUpdater] &7Shutdown hook was interrupted."));
        }
    }

    /** Checks and (if needed) updates a single plugin. */
    private void processPlugin(PluginEntry entry) {
        String currentVersion = versions.getVersion(entry.getName());

        Optional<UpdateInfo> opt = checker.checkForUpdate(entry, currentVersion);
        if (opt.isEmpty()) return; // up to date, or error already logged

        UpdateInfo update = opt.get();

        // ── First run: just initialise the version store ───────────────
        if (currentVersion == null) {
            versions.setVersion(entry.getName(), update.getNewVersion());
            logger.info(CC.c("&e[PluginUpdater] &7Recorded version &f" + update.getNewVersion()
                + " &7for &f" + entry.getName()
                + "&7. Updates will apply from the next shutdown onward."));
            return;
        }

        // ── Subsequent runs: download and replace ──────────────────────
        String tempName = entry.getName() + "-" + update.getNewVersion() + ".jar";
        Path tempFile = fileUpdater.downloadToTemp(
            update.getDownloadUrl(), tempName, entry.getAccessToken());

        if (tempFile == null) return; // download failed, already logged

        Path targetJar = resolveTargetJar(entry, update.getNewVersion());

        if (fileUpdater.atomicReplace(tempFile, targetJar)) {
            versions.setVersion(entry.getName(), update.getNewVersion());
            logger.info(CC.c("&a[PluginUpdater] \u25cf &f" + entry.getName()
                + " &7updated &8\u00bb &a" + update.getNewVersion()
                + " &7(active on next start)"));
        }
    }

    /**
     * Resolves the path of the JAR file to replace.
     *
     * <p>Priority:
     * <ol>
     *   <li>Pre-registered known path (e.g. the updater's own JAR).
     *   <li>First JAR in the plugins folder whose filename starts with the plugin name.
     *   <li>Fallback: {@code <plugins>/<Name>-<version>.jar}
     * </ol>
     */
    private Path resolveTargetJar(PluginEntry entry, String newVersion) {
        // 1. Known path
        Path known = knownJarPaths.get(entry.getName());
        if (known != null && Files.exists(known)) return known;

        // 2. Scan plugins folder (case-insensitive prefix match)
        String nameLower = entry.getName().toLowerCase();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsFolder, "*.jar")) {
            for (Path jar : stream) {
                if (jar.getFileName().toString().toLowerCase().startsWith(nameLower)) {
                    return jar;
                }
            }
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Plugins folder scan failed: " + e.getMessage()));
        }

        // 3. Fallback — new file with version in name
        return pluginsFolder.resolve(entry.getName() + "-" + newVersion + ".jar");
    }
}
