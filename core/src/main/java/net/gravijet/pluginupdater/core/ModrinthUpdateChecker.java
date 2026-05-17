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
        // GET /project/{slug}/version — returns JSON array, newest first
        String apiUrl = API_BASE + entry.getRepo() + "/version";
        JsonArray versions;
        try {
            HttpURLConnection conn = openConnection(apiUrl, entry.getAccessToken());
            int status = conn.getResponseCode();

            if (status == 404) {
                conn.disconnect();
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth project not found: &e"
                    + entry.getRepo() + " &7— check the &erepo&7 field in config.yml."));
                return Optional.empty();
            }
            if (status != 200) {
                conn.disconnect();
                logger.warning(CC.c("&c[PluginUpdater] &7Modrinth API returned HTTP &e" + status
                    + " &7for &f" + entry.getName() + "&7."));
                return Optional.empty();
            }

            @SuppressWarnings("deprecation")
            JsonArray parsed = new JsonParser().parse(readBody(conn)).getAsJsonArray();
            versions = parsed;
        } catch (Exception e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Modrinth update check failed for &f"
                + entry.getName() + "&7: " + e.getMessage()));
            return Optional.empty();
        }

        if (versions.size() == 0) {
            logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth versions found for &f"
                + entry.getName() + "&7."));
            return Optional.empty();
        }

        // Walk versions newest-first; pick the first file that matches the asset pattern.
        for (int v = 0; v < versions.size(); v++) {
            JsonObject version = versions.get(v).getAsJsonObject();
            String versionId     = version.get("id").getAsString();
            String versionNumber = version.get("version_number").getAsString();
            JsonArray files      = version.getAsJsonArray("files");

            for (int f = 0; f < files.size(); f++) {
                JsonObject file    = files.get(f).getAsJsonObject();
                String filename    = file.get("filename").getAsString();
                boolean isPrimary  = file.has("primary") && file.get("primary").getAsBoolean();

                // Match asset-pattern glob; also accept the primary file as fallback
                boolean patternMatch = GlobMatcher.matches(entry.getAssetPattern(), filename);
                if (!patternMatch && !isPrimary) continue;
                if (!patternMatch) {
                    // Primary file exists but pattern didn't match — skip unless it's the only file
                    if (files.size() > 1) continue;
                }

                String downloadUrl = file.get("url").getAsString();

                if (versionId.equals(storedKey)) {
                    return Optional.empty(); // already up to date
                }

                return Optional.of(new UpdateInfo(
                    entry.getName(), storedKey, versionNumber, versionId, downloadUrl));
            }
        }

        logger.warning(CC.c("&c[PluginUpdater] &7No Modrinth file matching &e'"
            + entry.getAssetPattern() + "'&7 in latest version of &f" + entry.getName()
            + "&7. Check &easset-pattern&7 in config.yml."));
        return Optional.empty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static HttpURLConnection openConnection(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
