package net.gravijet.pluginupdater.core.model;

/**
 * Represents a single plugin entry from {@code config.yml}.
 *
 * <p>{@code accessToken} is the resolved token for this entry:
 * the per-plugin {@code access-token} field if set, otherwise the global token,
 * or {@code null} if no token is configured (public repos only).
 *
 * <p>For GitHub entries {@code repo} is {@code "owner/repo"}.
 * For Modrinth entries {@code repo} is the project slug or ID (e.g. {@code "sodium"}).
 */
public class PluginEntry {

    /** Where update metadata is fetched from. */
    public enum Source { GITHUB, MODRINTH }

    private final String name;
    private final String repo;         // GitHub: "owner/repo" — Modrinth: project slug/ID
    private final String assetPattern; // glob, e.g. "EssentialsX-*.jar"
    private final String accessToken;  // may be null
    private final Source source;

    public PluginEntry(String name, String repo, String assetPattern,
                       String accessToken, Source source) {
        this.name         = name;
        this.repo         = repo;
        this.assetPattern = assetPattern;
        this.accessToken  = accessToken;
        this.source       = source;
    }

    /** Convenience constructor — defaults to {@link Source#GITHUB}. */
    public PluginEntry(String name, String repo, String assetPattern, String accessToken) {
        this(name, repo, assetPattern, accessToken, Source.GITHUB);
    }

    public String getName()         { return name; }
    public String getRepo()         { return repo; }
    public String getAssetPattern() { return assetPattern; }
    /** Returns the resolved access token for this plugin, or {@code null} if none. */
    public String getAccessToken()  { return accessToken; }
    /** Returns whether updates are fetched from GitHub or Modrinth. */
    public Source getSource()       { return source; }
}
