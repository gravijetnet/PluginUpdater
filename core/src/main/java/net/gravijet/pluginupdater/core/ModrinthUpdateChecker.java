package net.gravijet.pluginupdater.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gravijet.pluginupdater.core.model.PluginEntry;
import net.gravijet.pluginupdater.core.model.UpdateInfo;
import net.gravijet.pluginupdater.core.util.CC;
import net.gravijet.pluginupdater.core.util.GlobMatcher;

import java.io.IOException;
import java.io.InputStream;
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

    /** Guard against a runaway or malicious API response exhausting heap memory. */
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

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
            logger.warning(CC.c("&c[PluginUpdater] &7Invalid Modrinth project ID for &f"
                + CC.safe(entry.getName())
                + " &7— expected a slug like 'sodium' (letters, numbers, hyphens), got: &e"
                + CC.safe(entry.getRepo()) + "&7. Check &erepo&7 in config.yml."));
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
                    + CC.safe(entry.getRepo()) + " &7— check the &erepo&7 field in config.yml."));
                return Optional.empty();
            }
            if (status == 401 || status == 403) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned HTTP &e" + status
                    + " &7for &f" + CC.safe(entry.getName())
                    + " &7— check your &eaccess-token&7 in config.yml."));
                return Optional.empty();
            }
            if (status == 429) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API rate-limited (HTTP 429) for &f"
                    + CC.safe(entry.getName()) + " &7— try again later or add an access token."));
                return Optional.empty();
            }
            if (status != 200) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned HTTP &e" + status
                    + " &7for &f" + CC.safe(entry.getName()) + "&7."));
                return Optional.empty();
            }

            JsonElement parsed = JsonParser.parseString(readBody(conn));
            if (!parsed.isJsonArray()) {
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned unexpected response shape for &f"
                    + CC.safe(entry.getName()) + "&7: " + parsed));
                return Optional.empty();
            }
            versions = parsed.getAsJsonArray();
        } catch (Exception e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Modrinth update check failed for &f"
                + CC.safe(entry.getName()) + "&7: " + e));
            return Optional.empty();
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (versions.isEmpty()) {
            logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth versions found for &f"
                + CC.safe(entry.getName()) + "&7."));
            return Optional.empty();
        }

        // Modrinth returns versions newest-first. Only the newest version is relevant:
        // every Modrinth upload creates a brand-new version id, so a changed id means an
        // update. Walking to older versions could silently downgrade a plugin whose newest
        // version's filenames don't match the configured asset-pattern.
        JsonElement versionEl = versions.get(0);
        if (!versionEl.isJsonObject()) {
            logger.warning(CC.c("&c[PluginUpdater] &7Malformed Modrinth version data for &f"
                + CC.safe(entry.getName()) + "&7."));
            return Optional.empty();
        }
        JsonObject version = versionEl.getAsJsonObject();
        JsonElement idEl     = version.get("id");
        JsonElement verNumEl = version.get("version_number");
        if (idEl == null || idEl.isJsonNull()
                || verNumEl == null || verNumEl.isJsonNull()
                || !idEl.isJsonPrimitive() || !verNumEl.isJsonPrimitive()
                || !version.has("files") || !version.get("files").isJsonArray()) {
            logger.warning(CC.c("&c[PluginUpdater] &7Malformed Modrinth version data for &f"
                + CC.safe(entry.getName()) + "&7."));
            return Optional.empty();
        }

        String versionId     = idEl.getAsString();
        String versionNumber = verNumEl.getAsString();

        if (versionId.equals(storedKey)) {
            return Optional.empty(); // already on the latest Modrinth version
        }

        JsonArray files = version.get("files").getAsJsonArray();

        // Primary pass: find a file whose name matches the configured glob.
        String singleFileFallbackUrl = null;
        String singleFileFallbackName = null;
        for (int f = 0; f < files.size(); f++) {
            JsonElement fileEl = files.get(f);
            if (!fileEl.isJsonObject()) continue;
            JsonObject file = fileEl.getAsJsonObject();
            JsonElement filenameEl = file.get("filename");
            JsonElement urlEl      = file.get("url");
            if (filenameEl == null || filenameEl.isJsonNull()
                    || urlEl == null || urlEl.isJsonNull()) continue;
            String filename = filenameEl.getAsString();
            String url      = urlEl.getAsString();

            if (GlobMatcher.matches(entry.getAssetPattern(), filename)) {
                return Optional.of(new UpdateInfo(
                    entry.getName(), storedKey, versionNumber, versionId, url));
            }

            // Capture fallback: if there is exactly one JAR file and the glob didn't match,
            // use it anyway — single-file releases don't need a precise pattern.
            if (files.size() == 1 && filename.toLowerCase().endsWith(".jar")) {
                singleFileFallbackUrl  = url;
                singleFileFallbackName = filename;
            }
        }

        if (singleFileFallbackUrl != null) {
            logger.warning(CC.c("&e[PluginUpdater] &7Asset pattern &e'"
                + CC.safe(entry.getAssetPattern())
                + "'&7 did not match &e'" + CC.safe(singleFileFallbackName)
                + "'&7 for &f" + CC.safe(entry.getName())
                + " &7— using it anyway (only one JAR in release). Update asset-pattern in config.yml to silence this."));
            return Optional.of(new UpdateInfo(
                entry.getName(), storedKey, versionNumber, versionId, singleFileFallbackUrl));
        }

        logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth file matching &e'"
            + CC.safe(entry.getAssetPattern()) + "'&7 in latest version of &f"
            + CC.safe(entry.getName())
            + "&7. Check &easset-pattern&7 in config.yml."));
        return Optional.empty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code projectId} is a safe Modrinth project slug or ID.
     * Modrinth slugs are lowercase alphanumeric with hyphens; base-62 IDs are alphanumeric.
     * Rejects characters that would escape the URL path segment.
     */
    private static boolean isValidModrinthProject(String projectId) {
        // Modrinth slugs: letters, numbers, hyphens, underscores (e.g. "fabric_api", "sodium")
        // Modrinth base-62 IDs: alphanumeric only — both subsets are covered by this pattern.
        // Dots are intentionally excluded: they are not valid in Modrinth slugs/IDs and
        // would be the most likely typo to cause a confusing 404.
        return projectId != null && projectId.matches("[A-Za-z0-9_-]+");
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
        conn.setInstanceFollowRedirects(false); // prevent JVM from auto-following redirects without SSRF validation
        // Modrinth API v2 expects the token directly, without a "Bearer" prefix.
        // Strip it if the user accidentally copied a Bearer-prefixed value from another tool.
        if (token != null) {
            String normalized = token.startsWith("Bearer ") ? token.substring(7) : token;
            conn.setRequestProperty("Authorization", normalized);
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        try (InputStream raw = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            long totalBytes = 0;
            java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream(8192);
            while ((n = raw.read(buf)) != -1) {
                totalBytes += n;
                if (totalBytes > MAX_BODY_BYTES) {
                    throw new IOException("API response body exceeded "
                        + (MAX_BODY_BYTES / 1024 / 1024) + " MB safety limit.");
                }
                chunk.write(buf, 0, n);
            }
            return chunk.toString(StandardCharsets.UTF_8);
        }
    }
}
