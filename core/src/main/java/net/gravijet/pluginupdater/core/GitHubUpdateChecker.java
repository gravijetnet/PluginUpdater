package net.gravijet.pluginupdater.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.model.UpdateInfo;
import net.gravijet.pluginupdater.core.util.CC;
import net.gravijet.pluginupdater.core.util.GlobMatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Queries the GitHub REST API to check whether a newer release is available
 * for a given {@link PluginEntry}.
 *
 * <p>Only {@code java.net.HttpURLConnection} is used — no external HTTP libraries.
 *
 * <h2>Change detection</h2>
 * <p>Instead of comparing tag names (which stays {@code "latest"} for rolling-tag repos),
 * the asset's {@code updated_at} timestamp is compared against the value stored in
 * {@code versions.yml}.  This correctly detects re-uploads under the same tag.
 */
public class GitHubUpdateChecker {

    private static final String API_BASE = "https://api.github.com/repos/";

    private final Logger logger;

    public GitHubUpdateChecker(Logger logger) {
        this.logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Checks GitHub for a newer release of the plugin described by {@code entry}.
     *
     * <ul>
     *   <li>{@code storedKey} is the asset's {@code updated_at} timestamp previously saved
     *       in {@code versions.yml}, or {@code null} on first run.
     *   <li>If the latest matching asset's {@code updated_at} equals {@code storedKey}
     *       the plugin is up to date and {@link Optional#empty()} is returned.
     *   <li>On any network or parse error a warning is logged and empty is returned —
     *       the server will never crash due to a failed update check.
     * </ul>
     */
    public Optional<UpdateInfo> checkForUpdate(PluginEntry entry, String storedKey) {
        // Determine which release to fetch: if asset pattern contains "latest", try the "latest" tag first
        boolean tryLatestTag = entry.getAssetPattern().toLowerCase().contains("latest");
        JsonObject release = null;
        String usedTag = null;

        if (tryLatestTag) {
            // First attempt: fetch release with tag "latest"
            String apiUrl = API_BASE + entry.getRepo() + "/releases/tags/latest";
            try {
                HttpURLConnection conn = openConnection(apiUrl, entry.getAccessToken());
                int status = conn.getResponseCode();
                if (status == 200) {
                    @SuppressWarnings("deprecation")
                    JsonObject potential = new JsonParser().parse(readBody(conn)).getAsJsonObject();
                    if (!potential.has("message")) {
                        release = potential;
                        usedTag = "latest";
                    }
                } else if (status == 404) {
                    // Tag "latest" not found, fall back to latest release
                    logger.info(CC.c("&e[PluginUpdater] &7Tag 'latest' not found for &f" + entry.getName()
                        + "&7, falling back to latest release."));
                } else {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                        + " &7for tag 'latest' of &f" + entry.getName() + "&7."));
                }
            } catch (Exception e) {
                logger.warning(CC.c("&c[PluginUpdater] &7Failed to fetch tag 'latest' for &f" + entry.getName()
                    + "&7: " + e.getMessage() + " — falling back to latest release."));
            }
        }

        // If we haven't obtained a release yet, fetch the latest release
        if (release == null) {
            String apiUrl = API_BASE + entry.getRepo() + "/releases/latest";
            try {
                HttpURLConnection conn = openConnection(apiUrl, entry.getAccessToken());
                int status = conn.getResponseCode();

                if (status == 404) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Repository not found: &e" + entry.getRepo()
                        + " &7— check the &erepo&7 field in config.yml."));
                    return Optional.empty();
                }
                if (status != 200) {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                        + " &7for &f" + entry.getName() + "&7."));
                    return Optional.empty();
                }

                @SuppressWarnings("deprecation")
                JsonObject potential = new JsonParser().parse(readBody(conn)).getAsJsonObject();

                // GitHub returns {"message":"..."} for errors even on 200 (e.g. no releases)
                if (potential.has("message")) {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub error for &f" + entry.getName()
                        + "&7: " + potential.get("message").getAsString()));
                    return Optional.empty();
                }
                release = potential;
                usedTag = release.get("tag_name").getAsString();
            } catch (Exception e) {
                logger.warning(CC.c("&c[PluginUpdater] &7Update check failed for &f" + entry.getName()
                    + "&7: " + e.getMessage()));
                return Optional.empty();
            }
        }

        // At this point, release is guaranteed non-null
        String latestTag = release.get("tag_name").getAsString();

            // Find the release asset whose filename matches the configured glob
            JsonArray assets = release.getAsJsonArray("assets");
            for (int i = 0; i < assets.size(); i++) {
                JsonObject asset = assets.get(i).getAsJsonObject();
                String assetName = asset.get("name").getAsString();

                if (!GlobMatcher.matches(entry.getAssetPattern(), assetName)) continue;

                long   assetId       = asset.get("id").getAsLong();
                // Use the API asset endpoint — more reliable than browser_download_url for both
                // public and private repos; auth header is forwarded only on the initial request.
                String downloadUrl  = API_BASE + entry.getRepo() + "/releases/assets/" + assetId;
                // updated_at changes whenever the asset is re-uploaded, even under the same tag.
                String assetUpdatedAt = asset.get("updated_at").getAsString();

                if (assetUpdatedAt.equals(storedKey)) {
                    logger.info(CC.c("&e[PluginUpdater] &f" + entry.getName()
                        + " &7is up to date &8(&ftag: " + latestTag + "&8)."));
                    return Optional.empty();
                }

                if (storedKey != null) {
                    logger.info(CC.c("&a[PluginUpdater] &7Update available for &f" + entry.getName()
                        + "&7: tag &f" + latestTag + " &7(asset updated &f" + assetUpdatedAt + "&7)"));
                } else {
                    logger.info(CC.c("&e[PluginUpdater] &7Initialising &f" + entry.getName()
                        + " &7at tag &e" + latestTag + "&7."));
                }

                return Optional.of(new UpdateInfo(
                    entry.getName(), storedKey, latestTag, assetUpdatedAt, downloadUrl));
            }

            // No asset matched the configured glob
            // Try fallback: if pattern contains "latest", look for any jar asset containing plugin name
            boolean patternContainsLatest = entry.getAssetPattern().toLowerCase().contains("latest");
            if (patternContainsLatest) {
                logger.info(CC.c("&e[PluginUpdater] &7Trying fallback asset detection for &f" + entry.getName() + "&7..."));
                for (int i = 0; i < assets.size(); i++) {
                    JsonObject asset = assets.get(i).getAsJsonObject();
                    String assetName = asset.get("name").getAsString();

                    // Fallback pattern: asset ends with .jar and contains entry name (case-insensitive)
                    if (assetName.toLowerCase().endsWith(".jar") &&
                        (assetName.toLowerCase().contains(entry.getName().toLowerCase()) ||
                         assetName.toLowerCase().contains(entry.getRepo().toLowerCase().replace("/", "-")))) {

                        logger.info(CC.c("&e[PluginUpdater] &7Found fallback asset &f" + assetName + " &7for &f" + entry.getName()));

                        long   assetId       = asset.get("id").getAsLong();
                        String downloadUrl  = API_BASE + entry.getRepo() + "/releases/assets/" + assetId;
                        String assetUpdatedAt = asset.get("updated_at").getAsString();

                        if (assetUpdatedAt.equals(storedKey)) {
                            logger.info(CC.c("&e[PluginUpdater] &f" + entry.getName()
                                + " &7is up to date &8(&ftag: " + latestTag + "&8)."));
                            return Optional.empty();
                        }

                        if (storedKey != null) {
                            logger.info(CC.c("&a[PluginUpdater] &7Update available for &f" + entry.getName()
                                + "&7: tag &f" + latestTag + " &7(asset updated &f" + assetUpdatedAt + "&7)"));
                        } else {
                            logger.info(CC.c("&e[PluginUpdater] &7Initialising &f" + entry.getName()
                                + " &7at tag &e" + latestTag + "&7."));
                        }

                        return Optional.of(new UpdateInfo(
                            entry.getName(), storedKey, latestTag, assetUpdatedAt, downloadUrl));
                    }
                }
                logger.warning(CC.c("&c[PluginUpdater] &7No fallback asset found for &f" + entry.getName()
                    + "&7. Please check asset-pattern in config.yml."));
            } else {
                logger.warning(CC.c("&c[PluginUpdater] &7No asset matching &e'" + entry.getAssetPattern()
                    + "'&7 in release &e" + latestTag + " &7for &f" + entry.getName()
                    + "&7. Check &easset-pattern&7 in config.yml."));
            }
            return Optional.empty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static HttpURLConnection openConnection(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "PluginUpdater/1.0");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
