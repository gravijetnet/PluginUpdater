package net.gravijet.pluginupdater.core;

import java.nio.file.Path;

/**
 * Platform-specific callback invoked after a plugin JAR has been downloaded and
 * placed on disk, either as a fresh install or an update to an existing plugin.
 *
 * <p>Implementations may attempt to load / reload the plugin at runtime.
 * The callback is invoked from a background worker thread — implementations that
 * require the main server thread must schedule accordingly.
 */
@FunctionalInterface
public interface PluginActivator {

    /**
     * Called after {@code jarPath} has been written.  Implementations should be
     * best-effort: any exception thrown is caught and logged by the caller.
     *
     * @param pluginName   the configured plugin name
     * @param jarPath      the freshly written JAR file
     * @param isNewInstall {@code true} if the JAR did not exist before (fresh install);
     *                     {@code false} if an existing plugin JAR was replaced (update)
     */
    void apply(String pluginName, Path jarPath, boolean isNewInstall);
}
