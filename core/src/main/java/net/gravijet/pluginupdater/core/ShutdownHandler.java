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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Orchestrates update checks and JAR replacements.
 *
 * <h2>Update schedule</h2>
 * <ul>
 *   <li><b>On enable</b> — immediate background check; downloads any pending updates.
 *   <li><b>Periodic</b> — repeating check every {@code check-interval-minutes} minutes
 *       (configurable; set to 0 to disable). Downloads and optionally hot-reloads.
 *   <li><b>On shutdown</b> — final check so anything released between the last periodic
 *       check and shutdown is still caught.
 * </ul>
 *
 * <h2>Version detection</h2>
 * <p>GitHub asset {@code updated_at} timestamps are used as the change key so that
 * re-uploads under the same tag name are also detected.  The stored key is written
 * to {@code versions.yml} after every successful download.
 *
 * <h2>JAR existence check</h2>
 * <p>Before comparing versions the updater verifies that the plugin JAR exists in the
 * plugins folder.  If it is gone a fresh download is triggered regardless of the stored key.
 *
 * <h2>Self-update</h2>
 * <p>Register the plugin's own JAR path with {@link #registerKnownJar} during
 * {@code onEnable}.  On Linux an atomic inode-swap works even for a loaded JAR.
 * On Windows the file may be locked; {@link java.io.File#deleteOnExit()} is used
 * as a fallback so the OS cleans up on next exit.
 */
public class ShutdownHandler {

    /** Maximum time to wait for the shutdown update worker before giving up. */
    private static final long TIMEOUT_MS = 30_000L;

    private final ConfigManager         config;
    private final VersionStore          versions;
    private final GitHubUpdateChecker   checker;
    private final FileUpdater           fileUpdater;
    private final Path                  pluginsFolder;
    private final Logger                logger;

    /**
     * Pre-registered JAR locations for plugins whose JAR path is known ahead of time
     * (e.g. the updater's own JAR, obtained from the class-loader / plugin API).
     * Key = plugin name as configured in config.yml (case-sensitive).
     */
    private final Map<String, Path> knownJarPaths = new HashMap<>();

    /** Ensures shutdown update logic runs at most once per JVM lifecycle. */
    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    /**
     * Optional platform callback invoked after a JAR has been downloaded.
     * Called for both fresh installs and updates so the platform can hot-reload.
     * {@code null} means no activation / reload.
     */
    private PluginActivator pluginActivator;

    /** Runs periodic checks while the server is online. {@code null} if interval == 0. */
    private ScheduledExecutorService scheduler;

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
     * Sets a platform-specific callback invoked after any JAR download (fresh install
     * or update).  The callback may attempt to load / reload the plugin at runtime.
     * Pass {@code null} to disable.
     */
    public void setPluginActivator(PluginActivator activator) {
        this.pluginActivator = activator;
    }

    /**
     * Runs an immediate background update check and starts the periodic scheduler.
     * Must be called once during plugin enable, after {@link #registerKnownJar}.
     */
    public void onEnable() {
        // Immediate check on startup
        runCheckAsync("startup", true);

        // Periodic checks while online
        int intervalMinutes = config.getCheckIntervalMinutes();
        if (intervalMinutes > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PluginUpdater-Scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                () -> runCheckSync("periodic", true),
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES
            );
            logger.info(CC.c("&e[PluginUpdater] &7Periodic checks every &f"
                + intervalMinutes + " &7min."));
        }
    }

    /**
     * Registers the JVM shutdown hook.  Must be called once during plugin enable.
     * The hook is a safety net that fires even when the server is killed forcibly.
     */
    public void registerShutdownHook() {
        Thread hook = new Thread(this::runShutdownUpdates, "PluginUpdater-ShutdownHook");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
        logger.info(CC.c("&e[PluginUpdater] &7Shutdown hook registered."));
    }

    /**
     * Called from the platform's {@code onDisable} / {@code ProxyShutdownEvent}.
     * Stops the scheduler and triggers a final update check on clean shutdowns.
     * The JVM shutdown hook handles forced kills.
     */
    public void onDisable() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        runShutdownUpdates();
    }

    // ── Core logic ─────────────────────────────────────────────────────────

    /**
     * Starts a background thread for update checks (non-blocking).
     * Used for startup and periodic checks so the calling thread is never blocked.
     */
    private void runCheckAsync(String label, boolean allowActivation) {
        Thread worker = new Thread(
            () -> runCheckSync(label, allowActivation),
            "PluginUpdater-" + label + "Worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs update checks synchronously on the current thread. */
    private void runCheckSync(String label, boolean allowActivation) {
        logger.info(CC.c("&e[PluginUpdater] &7Checking for updates (&f" + label + "&7)..."));

        List<PluginEntry> plugins = config.getPlugins();
        if (plugins.isEmpty()) {
            logger.info(CC.c("&e[PluginUpdater] &7No plugins configured — nothing to check."));
            return;
        }

        for (PluginEntry entry : plugins) {
            try {
                processPlugin(entry, allowActivation);
            } catch (Exception e) {
                logger.warning(CC.c("&c[PluginUpdater] &7Unexpected error for &f"
                    + entry.getName() + "&7: " + e.getMessage()));
            }
        }

        logger.info(CC.c("&a[PluginUpdater] &7Update check complete (&f" + label + "&7)."));
    }

    /**
     * Final shutdown check — runs in a background thread and waits up to 30 s.
     * The {@link AtomicBoolean} guard ensures this runs at most once
     * (either from {@link #onDisable} or from the JVM shutdown hook, not both).
     */
    private void runShutdownUpdates() {
        if (!hasRun.compareAndSet(false, true)) {
            return; // already ran
        }

        Thread worker = new Thread(
            () -> runCheckSync("shutdown", false), // no hot-reload during shutdown
            "PluginUpdater-ShutdownWorker");
        worker.setDaemon(false);
        worker.start();

        try {
            worker.join(TIMEOUT_MS);
            if (worker.isAlive()) {
                logger.warning(CC.c("&c[PluginUpdater] &7Shutdown worker timed out after "
                    + (TIMEOUT_MS / 1000) + " s — interrupting."));
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning(CC.c("&c[PluginUpdater] &7Shutdown hook was interrupted."));
        }
    }

    /**
     * Checks and (if needed) updates a single plugin.
     *
     * <p>Decision matrix:
     * <ol>
     *   <li>Stored key present + JAR exists → compare GitHub asset timestamp; download if newer.
     *   <li>No stored key + JAR exists → no stored version: always download latest to get current.
     *   <li>No stored key + JAR missing → fresh install: download latest.
     *   <li>Stored key present + JAR missing → JAR deleted: force re-download.
     * </ol>
     *
     * @param allowActivation if {@code true} the {@link PluginActivator} (if set) is called
     *                        after a successful download so the platform can hot-reload.
     */
    private void processPlugin(PluginEntry entry, boolean allowActivation) {
        String storedKey   = versions.getVersion(entry.getName());
        boolean jarPresent = findExistingJar(entry).isPresent();

        if (!jarPresent) {
            // Cases 3 & 4: JAR missing — always download
            boolean isFreshInstall = (storedKey == null);
            if (!isFreshInstall) {
                logger.warning(CC.c("&c[PluginUpdater] &7JAR for &f" + entry.getName()
                    + " &7is missing — forcing re-download."));
            }
            Optional<UpdateInfo> opt = checker.checkForUpdate(entry, null);
            opt.ifPresent(u -> downloadAndReplace(entry, u, allowActivation, isFreshInstall));
            return;
        }

        // Cases 1 & 2: JAR present — compare against latest GitHub asset timestamp
        // storedKey == null means we have never tracked this plugin; always download to sync.
        Optional<UpdateInfo> opt = checker.checkForUpdate(entry, storedKey);
        opt.ifPresent(u -> downloadAndReplace(entry, u, allowActivation, false));
    }

    /** Downloads the update, atomically replaces the target JAR, and optionally activates. */
    private void downloadAndReplace(PluginEntry entry, UpdateInfo update,
                                    boolean allowActivation, boolean isNewInstall) {
        String tempName = entry.getName() + "-" + update.getNewVersion() + ".jar";
        Path tempFile = fileUpdater.downloadToTemp(
            update.getDownloadUrl(), tempName, entry.getAccessToken());

        if (tempFile == null) return; // download failed, already logged

        Optional<Path> oldJar = findExistingJar(entry);
        Path targetJar = resolveTargetJar(entry, update.getNewVersion());

        if (fileUpdater.atomicReplace(tempFile, targetJar)) {
            // Remove old JAR if it had a different name (e.g. old version in filename)
            oldJar.ifPresent(old -> {
                if (!old.equals(targetJar)) {
                    try {
                        Files.deleteIfExists(old);
                    } catch (IOException e) {
                        logger.warning(CC.c("&c[PluginUpdater] &7Could not remove old JAR &f"
                            + old.getFileName() + "&7: " + e.getMessage()));
                        old.toFile().deleteOnExit();
                    }
                }
            });

            versions.setVersion(entry.getName(), update.getStoreKey());

            if (allowActivation && pluginActivator != null) {
                String action = isNewInstall ? "installed" : "updated to tag";
                logger.info(CC.c("&a[PluginUpdater] \u25cf &f" + entry.getName()
                    + " &7" + action + " &a" + update.getNewVersion()
                    + " &7— applying at runtime..."));
                try {
                    pluginActivator.apply(entry.getName(), targetJar, isNewInstall);
                } catch (Exception e) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Runtime apply failed for &f"
                        + entry.getName() + "&7: " + e.getMessage()
                        + " &8(&7will be fully active on next start&8)"));
                }
            } else {
                logger.info(CC.c("&a[PluginUpdater] \u25cf &f" + entry.getName()
                    + " &7updated to tag &a" + update.getNewVersion()
                    + " &7(active on next start)"));
            }
        }
    }

    /**
     * Finds the currently installed JAR for a plugin without needing the version string.
     * Returns empty if the JAR cannot be found.
     */
    private Optional<Path> findExistingJar(PluginEntry entry) {
        // 1. Known path (e.g. self-JAR registered at startup)
        Path known = knownJarPaths.get(entry.getName());
        if (known != null && Files.exists(known)) return Optional.of(known);

        // 2. Scan plugins folder (case-insensitive prefix match)
        String nameLower = entry.getName().toLowerCase();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsFolder, "*.jar")) {
            for (Path jar : stream) {
                if (jar.getFileName().toString().toLowerCase().startsWith(nameLower)) {
                    return Optional.of(jar);
                }
            }
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Plugins folder scan failed: " + e.getMessage()));
        }
        return Optional.empty();
    }

    /**
     * Resolves the desired target path for the updated JAR.
     *
     * <p>For pre-registered JARs (e.g. the updater itself) the known path is returned
     * unchanged.  For all other plugins the target is always
     * {@code <plugins>/<ConfigName>-<version>.jar} so that the filename stays in sync
     * with the configured name and current version.
     */
    private Path resolveTargetJar(PluginEntry entry, String newVersion) {
        // Known path (e.g. the updater's own JAR) — keep as-is
        Path known = knownJarPaths.get(entry.getName());
        if (known != null) return known;

        // Always use <ConfigName>-<version>.jar so the filename matches the config
        return pluginsFolder.resolve(entry.getName() + "-" + newVersion + ".jar");
    }
}
