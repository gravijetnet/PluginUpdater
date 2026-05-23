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

    /** Guard against a runaway or malicious API response exhausting heap memory. */
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

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
        if (!isValidGitHubRepo(entry.getRepo())) {
            logger.warning(CC.c("&c[PluginUpdater] &7Invalid GitHub repo for &f"
                + CC.safe(entry.getName())
                + " &7— expected 'owner/repo' with safe characters, got: &e"
                + CC.safe(entry.getRepo()) + "&7. Check &erepo&7 in config.yml."));
            return Optional.empty();
        }

        JsonObject release = null;

        // First attempt: fetch release with tag "latest"
        String apiUrl = API_BASE + entry.getRepo() + "/releases/tags/latest";
        HttpURLConnection firstConn = null;
        try {
            firstConn = openConnection(apiUrl, entry.getAccessToken());
            int status = firstConn.getResponseCode();
            if (status == 200) {
                JsonElement parsed = JsonParser.parseString(readBody(firstConn));
                if (parsed.isJsonObject()) {
                    JsonObject potential = parsed.getAsJsonObject();
                    if (!potential.has("message")) {
                        release = potential;
                    }
                }
            } else if (status == 401 || status == 403) {
                logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                    + " &7for &f" + CC.safe(entry.getName())
                    + " &7— check your &eaccess-token&7 in config.yml."));
                return Optional.empty();
            } else if (status == 429) {
                logger.warning(CC.c("&c[PluginUpdater] &7GitHub API rate-limited (HTTP 429) for &f"
                    + CC.safe(entry.getName()) + " &7— try again later."));
                return Optional.empty();
            } else if (status == 404) {
                // 404 means the "latest" tag doesn't exist — fall through to list-based lookup
            } else {
                // Unexpected status (e.g. 5xx) — do not attempt a redundant second request.
                logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                    + " &7for tag 'latest' of &f" + CC.safe(entry.getName()) + "&7."));
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Failed to fetch tag 'latest' for &f"
                + CC.safe(entry.getName())
                + "&7: " + e + " — falling back to latest release."));
        } finally {
            if (firstConn != null) firstConn.disconnect();
        }

        // If we haven't obtained a release yet, fetch the list and pick the newest.
        // The tag endpoint above already covers the "latest" tag case; the list fallback
        // simply grabs the most-recently-created release (releases[0] per GitHub ordering).
        if (release == null) {
            String listUrl = API_BASE + entry.getRepo() + "/releases?per_page=1&page=1";
            HttpURLConnection listConn = null;
            try {
                listConn = openConnection(listUrl, entry.getAccessToken());
                int status = listConn.getResponseCode();

                if (status == 404) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Repository not found: &e"
                        + CC.safe(entry.getRepo())
                        + " &7— check the &erepo&7 field in config.yml."));
                    return Optional.empty();
                }
                if (status == 401 || status == 403) {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                        + " &7for &f" + CC.safe(entry.getName())
                        + " &7— check your &eaccess-token&7 in config.yml."));
                    return Optional.empty();
                }
                if (status == 429) {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub API rate-limited (HTTP 429) for &f"
                        + CC.safe(entry.getName()) + " &7— try again later."));
                    return Optional.empty();
                }
                if (status != 200) {
                    logger.warning(CC.c("&c[PluginUpdater] &7GitHub API returned HTTP &e" + status
                        + " &7for &f" + CC.safe(entry.getName()) + "&7."));
                    return Optional.empty();
                }

                JsonElement parsedList = JsonParser.parseString(readBody(listConn));
                if (!parsedList.isJsonArray()) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Malformed GitHub releases list for &f"
                        + CC.safe(entry.getName()) + "&7."));
                    return Optional.empty();
                }
                JsonArray releases = parsedList.getAsJsonArray();

                if (releases.isEmpty()) {
                    logger.warning(CC.c("&c[PluginUpdater] &7No releases found for &f"
                        + CC.safe(entry.getName()) + "&7."));
                    return Optional.empty();
                }

                // Take the first entry — GitHub orders by created_at descending so this is
                // the newest release. The per_page=1 request avoids fetching 100 entries
                // when we only need one, and avoids pagination edge cases.
                JsonElement firstEl = releases.get(0);
                if (!firstEl.isJsonObject()) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Malformed GitHub releases list for &f"
                        + CC.safe(entry.getName()) + "&7."));
                    return Optional.empty();
                }
                release = firstEl.getAsJsonObject();

            } catch (Exception e) {
                logger.warning(CC.c("&c[PluginUpdater] &7Update check failed for &f"
                    + CC.safe(entry.getName()) + "&7: " + e));
                return Optional.empty();
            } finally {
                if (listConn != null) listConn.disconnect();
            }
        }

        // At this point, release is guaranteed non-null
        JsonElement tagNameEl = release.get("tag_name");
        if (tagNameEl == null || tagNameEl.isJsonNull()) {
            logger.warning(CC.c("&c[PluginUpdater] &7Malformed GitHub release for &f"
                + CC.safe(entry.getName()) + " &7(missing tag_name)."));
            return Optional.empty();
        }
        String latestTag = tagNameEl.getAsString();
        if (latestTag.isEmpty()) {
            logger.warning(CC.c("&c[PluginUpdater] &7Malformed GitHub release for &f"
                + CC.safe(entry.getName()) + " &7(empty tag_name)."));
            return Optional.empty();
        }

        JsonElement assetsEl = release.get("assets");
        if (assetsEl == null || !assetsEl.isJsonArray()) return Optional.empty();
        JsonArray assets = assetsEl.getAsJsonArray();

        // Primary pass: find the asset whose filename matches the configured glob.
        for (int i = 0; i < assets.size(); i++) {
            Optional<UpdateInfo> hit = parseAsset(assets.get(i), entry, storedKey, latestTag);
            if (hit != SKIP) return hit; // SKIP = no glob match; empty = up-to-date; present = update found
        }

        // Fallback: if pattern contains "latest", accept any JAR that starts with the plugin
        // name or repo slug.  Require the asset to START with the name (not merely contain it)
        // so "EssentialsX-Chat-*.jar" is not mistaken for "EssentialsX".
        if (entry.getAssetPattern().toLowerCase().contains("latest")) {
            String repoSuffix = entry.getRepo().toLowerCase().replace("/", "-");
            String nameLower  = entry.getName().toLowerCase();
            for (int i = 0; i < assets.size(); i++) {
                JsonElement el = assets.get(i);
                if (!el.isJsonObject()) continue;
                JsonObject asset = el.getAsJsonObject();
                JsonElement nameEl = asset.get("name");
                if (nameEl == null || nameEl.isJsonNull()) continue;
                String assetLower = nameEl.getAsString().toLowerCase();
                if (assetLower.endsWith(".jar") &&
                    (assetLower.startsWith(nameLower + "-") || assetLower.equals(nameLower + ".jar") ||
                     assetLower.startsWith(repoSuffix + "-") || assetLower.equals(repoSuffix + ".jar"))) {
                    // Use the name-prefix match already verified above; skip glob check.
                    Optional<UpdateInfo> hit = parseAssetNoGlob(el, entry, storedKey, latestTag);
                    if (hit != SKIP) return hit;
                }
            }
            logger.warning(CC.c("&c[PluginUpdater] &7No fallback asset found for &f"
                + CC.safe(entry.getName())
                + "&7. Please check asset-pattern in config.yml."));
        } else {
            logger.warning(CC.c("&c[PluginUpdater] &7No asset matching &e'"
                + CC.safe(entry.getAssetPattern())
                + "'&7 in release &e" + CC.safe(latestTag) + " &7for &f"
                + CC.safe(entry.getName())
                + "&7. Check &easset-pattern&7 in config.yml."));
        }
        return Optional.empty();
    }

    /**
     * Sentinel returned by {@link #parseAsset} / {@link #parseAssetNoGlob} to signal
     * "this asset should be skipped" — distinct from {@link Optional#empty()} which means
     * "asset matched but is already up to date". Using a sentinel avoids returning a
     * literal {@code null} from an {@code Optional}-typed method, which would violate the
     * implicit contract that {@code Optional} is never itself {@code null}.
     */
    private static final Optional<UpdateInfo> SKIP = Optional.empty();
    /** Marker that distinguishes "skip this asset" from "asset matched, already up to date". */
    private static final Optional<UpdateInfo> UP_TO_DATE = Optional.empty();

    /**
     * Parses a single asset JSON element and returns:
     * <ul>
     *   <li>{@link #SKIP} (via {@code == SKIP} identity check) — asset should be skipped
     *   <li>{@link #UP_TO_DATE}                                — asset matches, already current
     *   <li>{@code Optional.of(...)}                           — asset matches and is newer
     * </ul>
     * Callers distinguish SKIP from UP_TO_DATE by reference identity ({@code result == SKIP}).
     */
    private Optional<UpdateInfo> parseAsset(JsonElement el, PluginEntry entry,
                                            String storedKey, String latestTag) {
        if (!el.isJsonObject()) return SKIP;
        JsonObject asset = el.getAsJsonObject();
        JsonElement nameEl      = asset.get("name");
        JsonElement idEl        = asset.get("id");
        JsonElement updatedAtEl = asset.get("updated_at");
        if (nameEl == null || nameEl.isJsonNull()
                || idEl == null || idEl.isJsonNull()
                || updatedAtEl == null || updatedAtEl.isJsonNull()) return SKIP;

        // Guard against non-numeric id values (e.g. future API returning a string id).
        if (!idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isNumber()) return SKIP;
        if (!nameEl.isJsonPrimitive() || !updatedAtEl.isJsonPrimitive()) return SKIP;

        String assetName = nameEl.getAsString();
        if (!GlobMatcher.matches(entry.getAssetPattern(), assetName)) return SKIP;

        long   assetId      = idEl.getAsLong();
        // Use the API asset endpoint — more reliable than browser_download_url for both
        // public and private repos; auth header is forwarded only on the initial request.
        String downloadUrl  = API_BASE + entry.getRepo() + "/releases/assets/" + assetId;
        // updated_at changes whenever the asset is re-uploaded, even under the same tag.
        String assetUpdatedAt = updatedAtEl.getAsString();

        if (assetUpdatedAt.equals(storedKey)) return UP_TO_DATE;
        return Optional.of(new UpdateInfo(entry.getName(), storedKey, latestTag, assetUpdatedAt, downloadUrl));
    }

    /** Like {@link #parseAsset} but skips the glob check — used by the name-prefix fallback loop. */
    private Optional<UpdateInfo> parseAssetNoGlob(JsonElement el, PluginEntry entry,
                                                   String storedKey, String latestTag) {
        if (!el.isJsonObject()) return SKIP;
        JsonObject asset = el.getAsJsonObject();
        JsonElement idEl        = asset.get("id");
        JsonElement updatedAtEl = asset.get("updated_at");
        if (idEl == null || idEl.isJsonNull()
                || updatedAtEl == null || updatedAtEl.isJsonNull()) return SKIP;
        if (!idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isNumber()) return SKIP;
        if (!updatedAtEl.isJsonPrimitive()) return SKIP;

        long   assetId      = idEl.getAsLong();
        String downloadUrl  = API_BASE + entry.getRepo() + "/releases/assets/" + assetId;
        String assetUpdatedAt = updatedAtEl.getAsString();

        if (assetUpdatedAt.equals(storedKey)) return UP_TO_DATE;
        return Optional.of(new UpdateInfo(entry.getName(), storedKey, latestTag, assetUpdatedAt, downloadUrl));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code repo} is a safe "owner/repo" string that can be
     * appended directly to the GitHub API base URL.  Rejects characters that would escape
     * the URL path segment and corrupt the request ({@code ?}, {@code #}, {@code &}, etc.).
     * GitHub owner/repo names consist of alphanumerics, hyphens, underscores, and dots.
     */
    private static boolean isValidGitHubRepo(String repo) {
        // GitHub owner names: alphanumerics and hyphens only (no dots, no underscores).
        // GitHub repo names: alphanumerics, hyphens, underscores, and dots are allowed,
        // but dots at the start/end or consecutive dots (..) are not valid. Reject any
        // sequence with ".." to prevent path-traversal-style URL manipulation.
        if (repo == null) return false;
        String[] parts = repo.split("/", -1);
        if (parts.length != 2) return false;
        String owner = parts[0], repoName = parts[1];
        if (!owner.matches("[A-Za-z0-9][A-Za-z0-9-]*")) return false;
        if (!repoName.matches("[A-Za-z0-9][A-Za-z0-9_.\\-]*") || repoName.contains("..")) return false;
        return true;
    }

    private static HttpURLConnection openConnection(String url, String token) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL: " + url, e);
        }
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "PluginUpdater/1.0");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(false); // prevent JVM from auto-following redirects without SSRF validation
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        try (InputStream raw = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int totalBytes = 0;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
            while ((n = raw.read(buf)) != -1) {
                totalBytes += n;
                if (totalBytes > MAX_BODY_BYTES) {
                    throw new IOException("API response body exceeded "
                        + (MAX_BODY_BYTES / 1024 / 1024) + " MB safety limit.");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
