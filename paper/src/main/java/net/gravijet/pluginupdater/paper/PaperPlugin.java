package net.gravijet.pluginupdater.paper;

import net.gravijet.pluginupdater.core.ConfigManager;
import net.gravijet.pluginupdater.core.FileUpdater;
import net.gravijet.pluginupdater.core.GitHubUpdateChecker;
import net.gravijet.pluginupdater.core.PluginActivator;
import net.gravijet.pluginupdater.core.ShutdownHandler;
import net.gravijet.pluginupdater.core.VersionStore;
import net.gravijet.pluginupdater.core.util.CC;
import org.bukkit.plugin.Plugin;
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
        String version = getDescription().getVersion();
        log.info(CC.c("&e[PluginUpdater] &7Starting version &f" + version));

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

        // Best-effort: load and enable a freshly downloaded plugin at runtime.
        // Only called for brand-new plugins (JAR was absent before download).
        shutdownHandler.setPluginActivator(buildActivator(log));

        shutdownHandler.registerShutdownHook();

        // Register command
        getCommand("pluginupdater").setExecutor(new PaperUpdateCommand(getDescription()));

        // Check and download updates immediately in the background.
        shutdownHandler.onEnable();

        log.info(CC.c("&a[PluginUpdater] &7Enabled &8\u00bb &7checking for updates in the background."));
    }

    /**
     * Returns a {@link PluginActivator} that loads / reloads a JAR at runtime.
     *
     * <ul>
     *   <li>Fresh install ({@code isNewInstall=true}): load and enable the new JAR.
     *   <li>Update ({@code isNewInstall=false}): disable the old plugin, then load and
     *       enable the new JAR so the update takes effect without a restart.
     * </ul>
     *
     * All Bukkit plugin-manager calls are scheduled on the main thread via the server
     * scheduler so they never run off-thread.
     */
    private PluginActivator buildActivator(java.util.logging.Logger log) {
        return (pluginName, jarPath, isNewInstall) ->
            // Schedule on the main thread — Bukkit plugin management must not run off-thread.
            getServer().getScheduler().runTask(this, () -> {
                try {
                    if (!isNewInstall) {
                        // Disable the currently running version before loading the new JAR.
                        Plugin existing = getServer().getPluginManager().getPlugin(pluginName);
                        if (existing != null) {
                            getServer().getPluginManager().disablePlugin(existing);
                            log.info(CC.c("&e[PluginUpdater] &7Disabled old &f" + pluginName
                                + " &7for hot-reload."));
                        }
                    }

                    Plugin loaded = getServer().getPluginManager().loadPlugin(jarPath.toFile());
                    if (loaded == null) {
                        log.warning(CC.c("&c[PluginUpdater] &7Could not load &f" + pluginName
                            + " &7— will be active on next start."));
                        return;
                    }
                    getServer().getPluginManager().enablePlugin(loaded);
                    log.info(CC.c("&a[PluginUpdater] &7" + (isNewInstall ? "Activated" : "Reloaded")
                        + " &f" + pluginName + "&7."));
                } catch (Exception e) {
                    log.warning(CC.c("&c[PluginUpdater] &7Could not "
                        + (isNewInstall ? "activate" : "reload") + " &f" + pluginName
                        + "&7: " + e.getMessage() + " &8(&7will be active on next start&8)"));
                }
            });
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
