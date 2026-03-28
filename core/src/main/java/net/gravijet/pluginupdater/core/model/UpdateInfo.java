package net.gravijet.pluginupdater.core.model;

/**
 * Holds the result of a successful GitHub update check — a newer version is available.
 */
public class UpdateInfo {

    private final String pluginName;
    private final String currentVersion; // may be null on first run
    private final String newVersion;
    private final String downloadUrl;    // browser_download_url for the matched asset

    public UpdateInfo(String pluginName, String currentVersion, String newVersion, String downloadUrl) {
        this.pluginName = pluginName;
        this.currentVersion = currentVersion;
        this.newVersion = newVersion;
        this.downloadUrl = downloadUrl;
    }

    public String getPluginName()     { return pluginName; }
    public String getCurrentVersion() { return currentVersion; }
    public String getNewVersion()     { return newVersion; }
    public String getDownloadUrl()    { return downloadUrl; }
}
