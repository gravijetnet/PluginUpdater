package net.gravijet.pluginupdater.paper;

import net.gravijet.pluginupdater.core.ConfigManager;
import net.gravijet.pluginupdater.core.FileUpdater;
import net.gravijet.pluginupdater.core.GitHubUpdateChecker;
import net.gravijet.pluginupdater.core.ShutdownHandler;
import net.gravijet.pluginupdater.core.VersionStore;
import net.gravijet.pluginupdater.core.util.CC;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Paper 1.8.8 entry point for PluginUpdater.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Instantiate and wire the core components.
 *   <li>Register the self-JAR path so the updater can replace itself cleanly.
 *   <li>Register the JVM shutdown hook (covers SIGKILL / forced kills).
 *   <li>Call {@link ShutdownHandler#onDisable()} on clean shutdowns.
 * </ul>
 */
public class PaperPlugin extends JavaPlugin {

    private ShutdownHandler shutdownHandler;

    @Override
    public void onEnable() {
        Logger log = getLogger();

        // Data folder: plugins/PluginUpdater/
        Path dataDir    = getDataFolder().toPath();
        // Plugins folder: plugins/
        Path pluginsDir = dataDir.getParent();

        ConfigManager       configManager = new ConfigManager(dataDir, log);
        VersionStore        versionStore  = new VersionStore(dataDir, log);
        GitHubUpdateChecker checker       = new GitHubUpdateChecker(log);
        FileUpdater         fileUpdater   = new FileUpdater(log);

        try {
            configManager.load();
            versionStore.load();
        } catch (IOException e) {
            log.severe(CC.c("&c[PluginUpdater] &7Failed to load configuration: " + e.getMessage()));
            log.severe(CC.c("&c[PluginUpdater] &7Disabling plugin."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        shutdownHandler = new ShutdownHandler(
            configManager, versionStore, checker, fileUpdater, pluginsDir, log);

        // Register this plugin's own JAR so self-updates replace the correct file.
        // JavaPlugin#getFile() is a protected method accessible from within this class.
        try {
            Path selfJar = getFile().toPath().toAbsolutePath();
            shutdownHandler.registerKnownJar("PluginUpdater", selfJar);
            log.info(CC.c("&e[PluginUpdater] &7Self-JAR: &f" + selfJar.getFileName()));
        } catch (Exception e) {
            log.warning(CC.c("&c[PluginUpdater] &7Could not determine self-JAR path: " + e.getMessage()));
        }

        shutdownHandler.registerShutdownHook();

        log.info(CC.c("&a[PluginUpdater] &7Enabled &8\u00bb &7updates will apply on next shutdown."));
    }

    @Override
    public void onDisable() {
        if (shutdownHandler != null) {
            // Handles clean /stop shutdowns.
            // The JVM shutdown hook handles SIGKILL / forced kills.
            shutdownHandler.onDisable();
        }
    }
}
