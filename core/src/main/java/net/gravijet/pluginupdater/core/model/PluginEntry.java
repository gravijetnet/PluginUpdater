package net.gravijet.pluginupdater.core.model;

/**
 * Represents a single plugin entry from {@code config.yml}.
 *
 * <p>{@code accessToken} is the resolved token for this entry:
 * the per-plugin {@code access-token} field if set, otherwise the global token,
 * or {@code null} if no token is configured (public repos only).
 */
public class PluginEntry {

    private final String name;
    private final String repo;        // "owner/repo"
    private final String assetPattern; // glob, e.g. "EssentialsX-*.jar"
    private final String accessToken;  // may be null

    public PluginEntry(String name, String repo, String assetPattern, String accessToken) {
        this.name = name;
        this.repo = repo;
        this.assetPattern = assetPattern;
        this.accessToken = accessToken;
    }

    public String getName()         { return name; }
    public String getRepo()         { return repo; }
    public String getAssetPattern() { return assetPattern; }
    /** Returns the resolved GitHub token for this plugin, or {@code null} if none. */
    public String getAccessToken()  { return accessToken; }
}
