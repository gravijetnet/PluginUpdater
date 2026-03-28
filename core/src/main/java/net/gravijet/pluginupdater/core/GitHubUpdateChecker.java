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
     *   <li>If {@code currentVersion} is {@code null} (first run) the latest release is
     *       returned so the caller can initialise the version store.
     *   <li>If the latest {@code tag_name} equals {@code currentVersion} the plugin is
     *       up to date and {@link Optional#empty()} is returned.
     *   <li>On any network or parse error a warning is logged and empty is returned —
     *       the server will never crash due to a failed update check.
     * </ul>
     */
    public Optional<UpdateInfo> checkForUpdate(PluginEntry entry, String currentVersion) {
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

            JsonObject release = JsonParser.parseString(readBody(conn)).getAsJsonObject();

            // GitHub returns {"message":"..."} for errors even on 200 (e.g. no releases)
            if (release.has("message")) {
                logger.warning(CC.c("&c[PluginUpdater] &7GitHub error for &f" + entry.getName()
                    + "&7: " + release.get("message").getAsString()));
                return Optional.empty();
            }

            String latestTag = release.get("tag_name").getAsString();

            // Already on this version — nothing to do
            if (latestTag.equals(currentVersion)) {
                logger.info(CC.c("&e[PluginUpdater] &f" + entry.getName()
                    + " &7is up to date &8(&f" + currentVersion + "&8)."));
                return Optional.empty();
            }

            // Find the release asset whose filename matches the configured glob
            JsonArray assets = release.getAsJsonArray("assets");
            for (int i = 0; i < assets.size(); i++) {
                JsonObject asset = assets.get(i).getAsJsonObject();
                String assetName = asset.get("name").getAsString();

                if (GlobMatcher.matches(entry.getAssetPattern(), assetName)) {
                    String downloadUrl = asset.get("browser_download_url").getAsString();

                    if (currentVersion != null) {
                        logger.info(CC.c("&a[PluginUpdater] &7Update available for &f" + entry.getName()
                            + "&7: &c" + currentVersion + " &7\u00bb &a" + latestTag));
                    } else {
                        logger.info(CC.c("&e[PluginUpdater] &7Initialising &f" + entry.getName()
                            + " &7to &e" + latestTag + "&7."));
                    }

                    return Optional.of(new UpdateInfo(entry.getName(), currentVersion, latestTag, downloadUrl));
                }
            }

            // No asset matched the configured glob
            logger.warning(CC.c("&c[PluginUpdater] &7No asset matching &e'" + entry.getAssetPattern()
                + "'&7 in release &e" + latestTag + " &7for &f" + entry.getName()
                + "&7. Check &easset-pattern&7 in config.yml."));
            return Optional.empty();

        } catch (Exception e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Update check failed for &f" + entry.getName()
                + "&7: " + e.getMessage()));
            return Optional.empty();
        }
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
