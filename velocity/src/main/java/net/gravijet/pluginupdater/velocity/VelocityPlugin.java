package net.gravijet.pluginupdater.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.pluginupdater.core.ConfigManager;
import net.gravijet.pluginupdater.core.FileUpdater;
import net.gravijet.pluginupdater.core.GitHubUpdateChecker;
import net.gravijet.pluginupdater.core.ModrinthUpdateChecker;
import net.gravijet.pluginupdater.core.ShutdownHandler;
import net.gravijet.pluginupdater.core.VersionStore;
import net.gravijet.pluginupdater.core.util.CC;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Velocity entry point for PluginUpdater.
 *
 * <p>Velocity uses SLF4J for logging while the core module uses {@link java.util.logging}.
 * A lightweight JUL → SLF4J bridge is created inline so the core can log through
 * Velocity's console without pulling in the full slf4j-jul bridge artifact.
 *
 * <p>Color codes ({@code §x}) are stripped from log messages before they reach SLF4J
 * because Velocity's console does not render Minecraft color codes.
 */
@Plugin(
    id          = "pluginupdater",
    name        = "PluginUpdater",
    version     = "1.0.0",
    description = "Automatically updates plugins from GitHub releases on proxy shutdown.",
    authors     = {"gravijet"}
)
public class VelocityPlugin {

    private final ProxyServer server;
    private final Logger      slf4j;
    private final Path        dataDirectory;

    /** java.util.logging adapter handed to the core module. */
    private final java.util.logging.Logger coreLogger;

    private volatile ShutdownHandler shutdownHandler;
    /** True only after onEnable() has been called on the handler (i.e. fully initialised). */
    private volatile boolean fullyInitialised = false;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger,
                          @DataDirectory Path dataDirectory) {
        this.server        = server;
        this.slf4j         = logger;
        this.dataDirectory = dataDirectory;
        this.coreLogger    = buildJulBridge(logger);
    }

    // ── Velocity lifecycle ─────────────────────────────────────────────────

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // dataDirectory = <proxy-root>/plugins/pluginupdater/
        // pluginsDir    = <proxy-root>/plugins/
        Path pluginsDir = dataDirectory.getParent();
        if (pluginsDir == null) {
            slf4j.error("[PluginUpdater] Could not determine plugins folder from data directory: {}", dataDirectory);
            return;
        }

        ConfigManager         configManager    = new ConfigManager(dataDirectory, coreLogger);
        VersionStore          versionStore     = new VersionStore(dataDirectory, coreLogger);
        GitHubUpdateChecker   githubChecker    = new GitHubUpdateChecker(coreLogger);
        ModrinthUpdateChecker modrinthChecker  = new ModrinthUpdateChecker(coreLogger);
        FileUpdater           fileUpdater      = new FileUpdater(coreLogger);

        try {
            configManager.load();
            versionStore.load();
        } catch (IOException e) {
            slf4j.error("[PluginUpdater] Failed to load configuration: {}", e.toString());
            return; // do not register the hook if config is broken
        }

        shutdownHandler = new ShutdownHandler(
            configManager, versionStore, githubChecker, modrinthChecker, fileUpdater, pluginsDir, coreLogger);

        // Attempt to register this plugin's own JAR for clean self-updates.
        try {
            java.security.ProtectionDomain pd = VelocityPlugin.class.getProtectionDomain();
            java.security.CodeSource cs = (pd != null) ? pd.getCodeSource() : null;
            java.net.URL loc = (cs != null) ? cs.getLocation() : null;
            if (loc == null) throw new IllegalStateException("code source location unavailable");
            // toURI() throws URISyntaxException for URLs with characters illegal in URIs;
            // convert it to an IOException so the catch block can give a clearer message.
            java.net.URI uri;
            try {
                uri = loc.toURI();
            } catch (java.net.URISyntaxException ex) {
                throw new java.io.IOException("JAR code-source URL cannot be converted to a path: " + loc, ex);
            }
            Path selfJar = Path.of(uri).toAbsolutePath();
            shutdownHandler.registerKnownJar("PluginUpdater", selfJar);
            // Self-JAR registered, no log to avoid spam
        } catch (Exception e) {
            slf4j.warn("[PluginUpdater] Could not determine self-JAR path (self-updates may place JAR under a generated name): {}", String.valueOf(e));
        }

        shutdownHandler.registerShutdownHook();

        // Check and download updates immediately in the background.
        shutdownHandler.onEnable();
        fullyInitialised = true;

        // Plugin enabled, no log to avoid spam
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (fullyInitialised && shutdownHandler != null) {
            // Handles clean proxy shutdowns.
            // The JVM shutdown hook handles forced kills.
            shutdownHandler.onDisable();
        }
    }

    // ── JUL → SLF4J bridge ────────────────────────────────────────────────

    /**
     * Builds a {@link java.util.logging.Logger} that forwards records to the given
     * SLF4J logger, stripping ANSI color codes in the process.
     */
    private static java.util.logging.Logger buildJulBridge(Logger slf4j) {
        // Use the fully-qualified package name to avoid colliding with any other plugin
        // that happens to use a logger named "PluginUpdater".
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("net.gravijet.pluginupdater");
        jul.setUseParentHandlers(false);
        // Remove any previously registered handlers so that re-initialisation (e.g. during
        // integration tests or a future proxy reload) does not cause duplicate log output.
        for (java.util.logging.Handler h : jul.getHandlers()) jul.removeHandler(h);

        jul.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || !isLoggable(record)) return;
                // JUL supports MessageFormat-style parameterised messages ({0}, {1}, …).
                // Substitute them before forwarding so SLF4J sees the fully expanded text.
                String raw = record.getMessage();
                Object[] params = record.getParameters();
                if (params != null && params.length > 0) {
                    try {
                        raw = java.text.MessageFormat.format(raw, params);
                    } catch (IllegalArgumentException e) {
                        raw = raw + " [format error: " + e.getMessage() + "]";
                    }
                }
                String    msg    = CC.strip(raw);
                Level     lvl    = record.getLevel();
                Throwable thrown = record.getThrown();

                if (lvl.intValue() >= Level.SEVERE.intValue()) {
                    if (thrown != null) slf4j.error(msg, thrown); else slf4j.error(msg);
                } else if (lvl.intValue() >= Level.WARNING.intValue()) {
                    if (thrown != null) slf4j.warn(msg, thrown); else slf4j.warn(msg);
                } else {
                    if (thrown != null) slf4j.info(msg, thrown); else slf4j.info(msg);
                }
            }

            @Override public void flush() {}
            @Override public void close() {}
        });

        return jul;
    }
}
