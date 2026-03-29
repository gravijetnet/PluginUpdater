package net.gravijet.pluginupdater.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.pluginupdater.core.ConfigManager;
import net.gravijet.pluginupdater.core.FileUpdater;
import net.gravijet.pluginupdater.core.GitHubUpdateChecker;
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
    version     = "${project.version}-b${github.build_number}.${github.short_commit}",
    description = "Automatically updates plugins from GitHub releases on proxy shutdown.",
    authors     = {"gravijet"}
)
public class VelocityPlugin {

    private final ProxyServer     server;
    private final Logger          slf4j;
    private final Path            dataDirectory;
    private final PluginContainer pluginContainer;

    /** java.util.logging adapter handed to the core module. */
    private final java.util.logging.Logger coreLogger;

    private ShutdownHandler shutdownHandler;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger,
                          @DataDirectory Path dataDirectory, PluginContainer pluginContainer) {
        this.server        = server;
        this.slf4j         = logger;
        this.dataDirectory = dataDirectory;
        this.pluginContainer = pluginContainer;
        this.coreLogger    = buildJulBridge(logger);
    }

    // ── Velocity lifecycle ─────────────────────────────────────────────────

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String version = pluginContainer.getDescription().getVersion().orElse("Unknown");
        slf4j.info("[PluginUpdater] Starting version {}", version);

        // dataDirectory = <proxy-root>/plugins/pluginupdater/
        // pluginsDir    = <proxy-root>/plugins/
        Path pluginsDir = dataDirectory.getParent();

        ConfigManager       configManager = new ConfigManager(dataDirectory, coreLogger);
        VersionStore        versionStore  = new VersionStore(dataDirectory, coreLogger);
        GitHubUpdateChecker checker       = new GitHubUpdateChecker(coreLogger);
        FileUpdater         fileUpdater   = new FileUpdater(coreLogger);

        try {
            configManager.load();
            versionStore.load();
        } catch (IOException e) {
            slf4j.error("[PluginUpdater] Failed to load configuration: {}", e.getMessage());
            return; // do not register the hook if config is broken
        }

        shutdownHandler = new ShutdownHandler(
            configManager, versionStore, checker, fileUpdater, pluginsDir, coreLogger);

        // Attempt to register this plugin's own JAR for clean self-updates.
        try {
            Path selfJar = pluginContainer.getDescription().getSource()
                .orElseThrow(() -> new IllegalStateException("Could not get plugin JAR path"))
                .toAbsolutePath();
            shutdownHandler.registerKnownJar("PluginUpdater", selfJar);
            slf4j.info("[PluginUpdater] Self-JAR: {}", selfJar.getFileName());
        } catch (Exception e) {
            slf4j.warn("[PluginUpdater] Could not determine self-JAR path: {}", e.getMessage());
        }

        // Register command
        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("pluginupdater")
            .aliases("pu")
            .plugin(this)
            .build();
        commandManager.register(meta, new VelocityUpdateCommand(pluginContainer.getDescription()));

        shutdownHandler.registerShutdownHook();

        // Check and download updates immediately in the background.
        shutdownHandler.onEnable();

        slf4j.info("[PluginUpdater] Enabled \u00bb checking for updates in the background.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (shutdownHandler != null) {
            // Handles clean proxy shutdowns.
            // The JVM shutdown hook handles forced kills.
            shutdownHandler.onDisable();
        }
    }

    // ── JUL → SLF4J bridge ────────────────────────────────────────────────

    /**
     * Builds a {@link java.util.logging.Logger} that forwards records to the given
     * SLF4J logger, stripping Minecraft color codes in the process.
     */
    private static java.util.logging.Logger buildJulBridge(Logger slf4j) {
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("PluginUpdater");
        jul.setUseParentHandlers(false);

        jul.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || !isLoggable(record)) return;
                String msg = CC.strip(record.getMessage()); // strip §x color codes
                Level lvl  = record.getLevel();

                if (lvl.intValue() >= Level.SEVERE.intValue()) {
                    slf4j.error(msg);
                } else if (lvl.intValue() >= Level.WARNING.intValue()) {
                    slf4j.warn(msg);
                } else {
                    slf4j.info(msg);
                }
            }

            @Override public void flush() {}
            @Override public void close() {}
        });

        return jul;
    }
}
