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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Queries the Modrinth REST API to check whether a newer version is available
 * for a given {@link PluginEntry} whose {@code source} is {@link PluginEntry.Source#MODRINTH}.
 *
 * <p>Only {@code java.net.HttpURLConnection} is used — no external HTTP libraries.
 *
 * <h2>Change detection</h2>
 * <p>The Modrinth version {@code id} is used as the stored key in {@code versions.yml}.
 * Because every new upload on Modrinth always creates a new version with a unique ID,
 * this correctly detects every update regardless of how version numbers are named.
 *
 * <h2>Configuration</h2>
 * <p>In {@code config.yml} set {@code source: modrinth} and use the project slug or
 * numeric ID as the {@code repo} field (e.g. {@code repo: "sodium"}).
 * The {@code asset-pattern} glob is matched against the filename of each file attached
 * to the latest Modrinth version (e.g. {@code "sodium-fabric-*.jar"}).
 */
public class ModrinthUpdateChecker {

    private static final String API_BASE = "https://api.modrinth.com/v2/project/";

    private final Logger logger;

    public ModrinthUpdateChecker(Logger logger) {
        this.logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Checks Modrinth for a newer version of the plugin described by {@code entry}.
     *
     * <ul>
     *   <li>{@code storedKey} is the Modrinth version {@code id} previously saved in
     *       {@code versions.yml}, or {@code null} on first run.
     *   <li>If the latest matching version's {@code id} equals {@code storedKey}
     *       the plugin is up to date and {@link Optional#empty()} is returned.
     *   <li>On any network or parse error a warning is logged and empty is returned.
     * </ul>
     *
     * @param entry     the plugin to check (must have {@code source == MODRINTH})
     * @param storedKey the previously persisted Modrinth version ID, or {@code null}
     */
    public Optional<UpdateInfo> checkForUpdate(PluginEntry entry, String storedKey) {
        if (!isValidModrinthProject(entry.getRepo())) {
            logger.warning(CC.c("&c[PluginUpdater] &7Invalid Modrinth project ID for &f" + entry.getName()
                + " &7— expected an alphanumeric slug (e.g. 'sodium'), got: &e"
                + entry.getRepo() + "&7. Check &erepo&7 in config.yml."));
            return Optional.empty();
        }

        // GET /project/{slug}/version — returns JSON array, newest first
        String apiUrl = API_BASE + entry.getRepo() + "/version";
        JsonArray versions;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(apiUrl, entry.getAccessToken());
            int status = conn.getResponseCode();

            if (status == 404) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth project not found: &e"
                    + entry.getRepo() + " &7— check the &erepo&7 field in config.yml."));
                return Optional.empty();
            }
            if (status == 401 || status == 403) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned HTTP &e" + status
                    + " &7for &f" + entry.getName()
                    + " &7— check your &eaccess-token&7 in config.yml."));
                return Optional.empty();
            }
            if (status == 429) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API rate-limited (HTTP 429) for &f"
                    + entry.getName() + " &7— try again later or add an access token."));
                return Optional.empty();
            }
            if (status != 200) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned HTTP &e" + status
                    + " &7for &f" + entry.getName() + "&7."));
                return Optional.empty();
            }

            versions = JsonParser.parseString(readBody(conn)).getAsJsonArray();
        } catch (Exception e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Modrinth update check failed for &f"
                + entry.getName() + "&7: " + e));
            return Optional.empty();
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (versions.size() == 0) {
            logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth versions found for &f"
                + entry.getName() + "&7."));
            return Optional.empty();
        }

        // Modrinth returns versions newest-first. Only the newest version is relevant:
        // every Modrinth upload creates a brand-new version id, so a changed id means an
        // update. Walking to older versions (the previous behaviour) could match an OLDER
        // build whose filename happened to fit the pattern — silently downgrading the
        // plugin and re-downloading it on every check when the newest version's filenames
        // simply don't match the configured asset-pattern.
        JsonObject version = versions.get(0).getAsJsonObject();
        if (!version.has("id") || !version.has("version_number") || !version.has("files")
                || !version.get("files").isJsonArray()) {
            logger.warning(CC.c("&c[PluginUpdater] &7Malformed Modrinth version data for &f"
                + entry.getName() + "&7."));
            return Optional.empty();
        }

        String versionId     = version.get("id").getAsString();
        String versionNumber = version.get("version_number").getAsString();

        if (versionId.equals(storedKey)) {
            return Optional.empty(); // already on the latest Modrinth version
        }

        JsonArray files = version.get("files").getAsJsonArray();
        String singleFileFallbackUrl = null;
        for (int f = 0; f < files.size(); f++) {
            JsonObject file = files.get(f).getAsJsonObject();
            if (!file.has("filename") || !file.has("url")) continue;
            String filename = file.get("filename").getAsString();

            if (GlobMatcher.matches(entry.getAssetPattern(), filename)) {
                return Optional.of(new UpdateInfo(
                    entry.getName(), storedKey, versionNumber, versionId,
                    file.get("url").getAsString()));
            }
            // Fall back to the sole file only when the version has exactly one JAR.
            if (files.size() == 1 && filename.toLowerCase().endsWith(".jar"))
                singleFileFallbackUrl = file.get("url").getAsString();
        }

        if (singleFileFallbackUrl != null) {
            return Optional.of(new UpdateInfo(
                entry.getName(), storedKey, versionNumber, versionId, singleFileFallbackUrl));
        }

        logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth file matching &e'"
            + entry.getAssetPattern() + "'&7 in latest version of &f" + entry.getName()
            + "&7. Check &easset-pattern&7 in config.yml."));
        return Optional.empty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code projectId} is a safe Modrinth project slug or ID
     * that can be appended directly to the Modrinth API base URL.  Rejects characters
     * that would escape the URL path segment ({@code ?}, {@code #}, {@code /}, etc.).
     * Modrinth slugs are alphanumeric with hyphens; numeric IDs are base-62.
     */
    private static boolean isValidModrinthProject(String projectId) {
        return projectId != null && projectId.matches("[A-Za-z0-9_.\\-]+");
    }

    private static HttpURLConnection openConnection(String url, String token) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL: " + url, e);
        }
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "PluginUpdater/1.0 (github.com/gravijetnet/PluginUpdater)");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        // Modrinth token format: plain token, no "Bearer" prefix
        if (token != null) {
            conn.setRequestProperty("Authorization", token);
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
