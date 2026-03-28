package net.gravijet.pluginupdater.core.model;

/**
 * Holds the result of a successful GitHub update check — a newer version is available.
 *
 * <p>{@code newVersion} is the human-readable tag name (e.g. {@code "latest"} or {@code "v1.2.3"})
 * used for display and temp-file naming.
 *
 * <p>{@code storeKey} is the value persisted in {@code versions.yml} for change detection.
 * For rolling tags like {@code latest}, this is the asset's {@code updated_at} timestamp so
 * that re-uploads under the same tag are still detected.
 */
public class UpdateInfo {

    private final String pluginName;
    private final String currentVersion; // may be null on first run
    private final String newVersion;     // tag_name — display & temp-file name
    private final String storeKey;       // asset updated_at — persisted in versions.yml
    private final String downloadUrl;    // browser_download_url for the matched asset

    public UpdateInfo(String pluginName, String currentVersion,
                      String newVersion, String storeKey, String downloadUrl) {
        this.pluginName     = pluginName;
        this.currentVersion = currentVersion;
        this.newVersion     = newVersion;
        this.storeKey       = storeKey;
        this.downloadUrl    = downloadUrl;
    }

    public String getPluginName()     { return pluginName; }
    public String getCurrentVersion() { return currentVersion; }
    public String getNewVersion()     { return newVersion; }
    public String getStoreKey()       { return storeKey; }
    public String getDownloadUrl()    { return downloadUrl; }
}
