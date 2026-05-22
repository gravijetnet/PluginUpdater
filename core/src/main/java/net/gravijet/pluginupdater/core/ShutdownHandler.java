package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.model.UpdateInfo;
import net.gravijet.pluginupdater.core.util.CC;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
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

    /** Maximum time to drain in-progress periodic downloads before the shutdown worker starts. */
    private static final long DRAIN_TIMEOUT_MS = 10_000L;

    private final ConfigManager          config;
    private final VersionStore           versions;
    private final GitHubUpdateChecker    githubChecker;
    private final ModrinthUpdateChecker  modrinthChecker;
    private final FileUpdater            fileUpdater;
    private final Path                   pluginsFolder;
    private final Logger                 logger;

    /**
     * Pre-registered JAR locations for plugins whose JAR path is known ahead of time
     * (e.g. the updater's own JAR, obtained from the class-loader / plugin API).
     * Key = plugin name as configured in config.yml (case-sensitive).
     */
    private final Map<String, Path> knownJarPaths = new ConcurrentHashMap<>();

    /** Ensures shutdown update logic runs at most once per JVM lifecycle. */
    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    /** Prevents registering the JVM shutdown hook more than once. */
    private final AtomicBoolean hookRegistered = new AtomicBoolean(false);

    /** Reference to the startup background thread so onDisable() can join it. */
    private volatile Thread startupThread;

    /** Prevents concurrent processing of the same plugin from overlapping threads. */
    private final Set<String> processingPlugins = ConcurrentHashMap.newKeySet();

    /** Runs periodic checks while the server is online. {@code null} if interval == 0. */
    private volatile ScheduledExecutorService scheduler;

    public ShutdownHandler(ConfigManager config, VersionStore versions,
                           GitHubUpdateChecker githubChecker,
                           ModrinthUpdateChecker modrinthChecker,
                           FileUpdater fileUpdater,
                           Path pluginsFolder, Logger logger) {
        this.config          = config;
        this.versions        = versions;
        this.githubChecker   = githubChecker;
        this.modrinthChecker = modrinthChecker;
        this.fileUpdater     = fileUpdater;
        this.pluginsFolder   = pluginsFolder;
        this.logger          = logger;
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
     * Runs an immediate background update check and starts the periodic scheduler.
     * Must be called once during plugin enable, after {@link #registerKnownJar}.
     */
    public void onEnable() {
        // Immediate check on startup
        runCheckAsync("startup");

        // Periodic checks while online
        int intervalMinutes = config.getCheckIntervalMinutes();
        if (intervalMinutes > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PluginUpdater-Scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        runCheckSync("periodic");
                    } catch (Exception e) {
                        logger.log(Level.WARNING,
                            CC.c("&c[PluginUpdater] &7Periodic check threw unexpected exception: " + e), e);
                    }
                },
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES
            );
            // Periodic checks enabled, no log to avoid spam
        }
    }

    /**
     * Registers the JVM shutdown hook.  Must be called once during plugin enable.
     * The hook is a safety net that fires even when the server is killed forcibly.
     */
    public void registerShutdownHook() {
        if (!hookRegistered.compareAndSet(false, true)) return; // already registered
        Thread hook = new Thread(this::runShutdownUpdates, "PluginUpdater-ShutdownHook");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * Called from the platform's {@code onDisable} / {@code ProxyShutdownEvent}.
     * Stops the scheduler and triggers a final update check on clean shutdowns.
     * The JVM shutdown hook handles forced kills.
     */
    public void onDisable() {
        // Track interruption and re-set the flag before runShutdownUpdates() so that an
        // inbound interrupt propagates to the shutdown worker's join, allowing the JVM to
        // exit promptly when the container signals shutdown.
        boolean interrupted = false;

        if (scheduler != null) {
            // shutdown() (not shutdownNow) lets the currently-running periodic task finish
            // rather than aborting an active download mid-stream.  We wait up to
            // DRAIN_TIMEOUT_MS for it to complete before proceeding to the shutdown worker,
            // so a new periodic firing cannot race with the shutdown worker writing the same
            // target JAR.
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        // Join the startup background thread so we don't race with an in-progress download.
        // Without this, the startup thread could be killed mid-download by the JVM exiting,
        // leaving a partial temp file and no stored version (forcing a redundant re-download).
        Thread startup = startupThread;
        if (startup != null) {
            // join() on a not-yet-started or already-terminated thread returns immediately.
            try {
                startup.join(TIMEOUT_MS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        startupThread = null;

        // Wait for any in-progress periodic downloads to finish before the shutdown worker
        // starts, so two worker threads never write to the same target JAR simultaneously.
        long drainDeadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (!processingPlugins.isEmpty() && System.currentTimeMillis() < drainDeadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { interrupted = true; break; }
        }

        if (interrupted) Thread.currentThread().interrupt();
        runShutdownUpdates();
    }

    // ── Core logic ─────────────────────────────────────────────────────────

    /**
     * Starts a background thread for update checks (non-blocking).
     * Used for startup and periodic checks so the calling thread is never blocked.
     */
    private void runCheckAsync(String label) {
        Thread worker = new Thread(
            () -> runCheckSync(label),
            "PluginUpdater-" + label + "-Worker");
        worker.setDaemon(true);
        if ("startup".equals(label)) {
            startupThread = worker; // publish reference before start so onDisable() cannot miss it
        }
        worker.start();
        // Yield briefly so the scheduler can transition the thread to RUNNABLE before
        // onDisable() could call join() on a still-NEW thread (which returns immediately,
        // leaving the startup download unjoinable).
        if ("startup".equals(label)) {
            while (worker.getState() == Thread.State.NEW) {
                Thread.yield();
            }
        }
    }

    /** Runs update checks synchronously on the current thread. */
    private void runCheckSync(String label) {
        List<PluginEntry> plugins = config.getPlugins();
        if (plugins.isEmpty()) {
            return;
        }

        for (PluginEntry entry : plugins) {
            try {
                processPlugin(entry);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                    CC.c("&c[PluginUpdater] &7Unexpected error for &f" + CC.safe(entry.getName()) + "&7: " + e), e);
            }
        }

        // Update check complete, no log to avoid spam
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
            () -> runCheckSync("shutdown"),
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
            worker.interrupt(); // stop the non-daemon worker so the JVM can exit
            Thread.currentThread().interrupt();
            logger.warning(CC.c("&c[PluginUpdater] &7Shutdown hook was interrupted."));
        }
    }

    /**
     * Checks and (if needed) updates a single plugin.
     *
     * <p>Decision matrix:
     * <ol>
     *   <li>Stored key present + JAR exists → compare asset timestamp; download if newer.
     *   <li>No stored key + JAR exists → no stored version: always download latest to sync.
     *   <li>No stored key + JAR missing → fresh install: download latest.
     *   <li>Stored key present + JAR missing → JAR deleted: force re-download.
     * </ol>
     */
    private void processPlugin(PluginEntry entry) {
        if (!processingPlugins.add(entry.getName())) {
            return; // startup worker or another check already processing this plugin
        }
        try {
            String storedKey      = versions.getVersion(entry.getName());
            Optional<Path> oldJar = findExistingJar(entry);
            boolean jarPresent    = oldJar.isPresent();

            if (!jarPresent) {
                // Cases 3 & 4: JAR missing — always download
                if (storedKey != null) {
                    logger.warning(CC.c("&c[PluginUpdater] &7JAR for &f" + CC.safe(entry.getName())
                        + " &7is missing — forcing re-download."));
                }
                Optional<UpdateInfo> opt = checkForUpdate(entry, null);
                opt.ifPresent(u -> downloadAndReplace(entry, u, Optional.empty()));
                return;
            }

            // Cases 1 & 2: JAR present — compare against stored key
            // storedKey == null means we have never tracked this plugin; always download to sync.
            Optional<UpdateInfo> opt = checkForUpdate(entry, storedKey);
            opt.ifPresent(u -> downloadAndReplace(entry, u, oldJar));
        } finally {
            processingPlugins.remove(entry.getName());
        }
    }

    /** Routes the update check to the correct checker based on the entry's source. */
    private Optional<UpdateInfo> checkForUpdate(PluginEntry entry, String storedKey) {
        if (entry.getSource() == PluginEntry.Source.MODRINTH) {
            return modrinthChecker.checkForUpdate(entry, storedKey);
        }
        return githubChecker.checkForUpdate(entry, storedKey);
    }

    /** Downloads the update and atomically replaces the target JAR on disk. */
    private void downloadAndReplace(PluginEntry entry, UpdateInfo update, Optional<Path> oldJar) {
        String rawVer   = update.getNewVersion();
        String safeVer  = sanitizeFilename(rawVer != null ? rawVer : "unknown");
        String tempName = sanitizeFilename(entry.getName()) + "-" + safeVer + ".jar";
        String downloadToken = (entry.getSource() == PluginEntry.Source.MODRINTH)
            ? null : entry.getAccessToken();
        Path tempFile = fileUpdater.downloadToTemp(
            update.getDownloadUrl(), pluginsFolder, tempName, downloadToken);

        if (tempFile == null) return; // download failed, already logged

        Path targetJar = resolveTargetJar(entry, safeVer);

        if (fileUpdater.atomicReplace(tempFile, targetJar)) {
            // Remove old JAR if it had a different name (e.g. old version in filename)
            oldJar.ifPresent(old -> {
                if (!old.equals(targetJar)) {
                    try {
                        Files.deleteIfExists(old);
                    } catch (IOException e) {
                        logger.warning(CC.c("&c[PluginUpdater] &7Could not remove old JAR &f"
                            + old.getFileName() + "&7: " + e));
                        old.toFile().deleteOnExit();
                    }
                }
            });

            versions.setVersion(entry.getName(), update.getStoreKey());

            logger.info(CC.c("&a[PluginUpdater] \u25cf &f" + CC.safe(entry.getName())
                + " &7updated to &a" + CC.safe(rawVer != null ? rawVer : "unknown")
                + " &7(active on next start)"));
        } else {
            logger.warning(CC.c("&c[PluginUpdater] &7Could not install update for &f"
                + CC.safe(entry.getName())
                + " &7\u2014 new JAR saved at: &f" + tempFile
                + " &7(copy it manually to the plugins folder)"));
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

        // 2. Scan plugins folder — match "<name>.jar" or "<name>-<version>.jar" exactly
        //    (a plain startsWith would match "ExamplePlugin" when looking for "Example")
        //    When multiple matches exist (e.g. old locked JAR + new version on Windows),
        //    return the most recently modified one to avoid using a stale copy.
        String nameLower = entry.getName().toLowerCase();
        String versionedPrefix = nameLower + "-";
        Path bestMatch = null;
        long bestMtime = -1;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsFolder)) {
            for (Path jar : stream) {
                String fname = jar.getFileName().toString().toLowerCase();
                if (!fname.endsWith(".jar")) continue; // manual filter — case-insensitive on all OSes
                boolean matched = false;
                if (fname.equals(nameLower + ".jar")) {
                    matched = true;
                } else if (fname.startsWith(versionedPrefix)
                        && fname.length() > versionedPrefix.length()) {
                    // Require the character after the dash to be a digit or 'v' so that
                    // "essentialsx-chatcolor-1.0.jar" is not mistaken for "essentialsx".
                    // The length guard prevents StringIndexOutOfBoundsException when the
                    // filename is exactly "<name>-.jar" (prefix fills the name portion).
                    char first = fname.charAt(versionedPrefix.length());
                    if (Character.isDigit(first) || first == 'v') {
                        matched = true;
                    }
                }
                if (matched) {
                    try {
                        long mtime = Files.getLastModifiedTime(jar).toMillis();
                        if (mtime > bestMtime) {
                            bestMtime = mtime;
                            bestMatch = jar;
                        }
                    } catch (IOException ignored) {
                        // Cannot read mtime; only use this file as a last resort (i.e. keep
                        // any previously found match that has a real mtime over this one).
                        if (bestMatch == null || bestMtime == -1) bestMatch = jar;
                    }
                }
            }
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Plugins folder scan failed: " + e));
        }
        return Optional.ofNullable(bestMatch);
    }

    /**
     * Resolves the desired target path for the updated JAR.
     *
     * <p>For pre-registered JARs (e.g. the updater itself) the known path is returned
     * unchanged.  For all other plugins the target is always
     * {@code <plugins>/<ConfigName>-<version>.jar} so that the filename stays in sync
     * with the configured name and current version.
     */
    private Path resolveTargetJar(PluginEntry entry, String safeVersion) {
        // Known path (e.g. the updater's own JAR) — keep as-is
        Path known = knownJarPaths.get(entry.getName());
        if (known != null) return known;

        // Always use <ConfigName>-<version>.jar so the filename matches the config
        return pluginsFolder.resolve(sanitizeFilename(entry.getName()) + "-" + safeVersion + ".jar");
    }

    /** Strips characters that are unsafe in filenames or could cause path traversal. */
    private static String sanitizeFilename(String s) {
        // Remove forbidden filename characters and null bytes.
        String safe = s.replaceAll("[\0/\\\\:*?\"<>|]", "_");
        // Collapse dot-only segments ("..") that survived slash removal to prevent
        // path-traversal-looking names like ".._.._evil".
        safe = safe.replaceAll("\\.\\.+", "_");
        // Cap at 64 characters so name + "-" + version + ".jar" stays under 255 bytes.
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return safe;
    }
}
