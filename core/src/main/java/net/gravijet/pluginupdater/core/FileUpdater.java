package net.gravijet.pluginupdater.core;

import net.gravijet.pluginupdater.core.util.CC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Handles the download and atomic file replacement for plugin updates.
 *
 * <p>Downloads use only {@link HttpURLConnection} — no third-party HTTP libraries.
 * GitHub release assets are accessed via the {@code browser_download_url} which
 * redirects through AWS S3; redirects are handled manually so the auth header is
 * only sent to {@code api.github.com} and not leaked to S3.
 */
public class FileUpdater {

    private final Logger logger;

    public FileUpdater(Logger logger) {
        this.logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Downloads the JAR at {@code downloadUrl} to a temporary file.
     *
     * @param downloadUrl the {@code browser_download_url} from the GitHub release asset
     * @param displayName human-readable name used in log messages (e.g. {@code "EssentialsX-2.20.jar"})
     * @param accessToken GitHub token, or {@code null} for public assets
     * @return path to the temp file on success, or {@code null} if the download failed
     */
    public Path downloadToTemp(String downloadUrl, String displayName, String accessToken) {
        HttpURLConnection conn = null;
        try {
            // GitHub redirects asset API endpoint → S3.
            // We follow redirects manually so the auth header is not leaked to S3.
            conn = followRedirects(openGet(downloadUrl, accessToken), null);

            int status = conn.getResponseCode();
            if (status != 200) {
                logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                    + " &7failed with HTTP &e" + status
                    + " &8(&7url: &f" + conn.getURL() + "&8)&7."));
                return null;
            }

            long contentLength = conn.getContentLengthLong(); // -1 if server omits header

            Path temp = Files.createTempFile("pluginupdater-", "-" + displayName);
            boolean success = false;
            try {
                long bytesWritten = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buf = new byte[16_384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        bytesWritten += n;
                    }
                }
                // Validate download completeness when the server declares a Content-Length.
                // A mismatch means the connection was dropped before all bytes arrived.
                if (contentLength >= 0 && bytesWritten != contentLength) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                        + " &7was truncated: expected &e" + contentLength
                        + " &7bytes but received &e" + bytesWritten + "&7."));
                    return null; // success stays false → finally deletes the partial temp file
                }
                success = true;
                return temp;
            } finally {
                if (!success) {
                    try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                }
            }

        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Download error for &e" + displayName
                + "&7: " + e.getMessage()));
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Replaces {@code targetPath} with the contents of {@code sourceTempFile}.
     *
     * <p>Strategy (in order of preference):
     * <ol>
     *   <li>Atomic move — guarantees no partial writes are visible.
     *   <li>Regular {@code REPLACE_EXISTING} move — used when cross-filesystem or
     *       the platform doesn't support atomic moves.
     *   <li>{@link java.io.File#deleteOnExit()} fallback — used when the target is
     *       file-locked (common on Windows for loaded JARs); the JVM will attempt
     *       deletion at exit. The temp file is left in place so the operator can
     *       manually complete the swap if needed.
     * </ol>
     *
     * @return {@code true} if the file was successfully replaced
     */
    public boolean atomicReplace(Path sourceTempFile, Path targetPath) {
        // 1. Try atomic move
        try {
            Files.move(sourceTempFile, targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            // Replaced (atomic), no log to avoid spam
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            // Fall through to regular move
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Atomic move failed for &e"
                + targetPath.getFileName() + "&7: " + e.getMessage() + " — trying regular move."));
        }

        // 2. Regular replace
        try {
            Files.move(sourceTempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            // Replaced, no log to avoid spam
            return true;
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Could not replace &e"
                + targetPath.getFileName()
                + " &7(file may be locked — common on Windows): " + e.getMessage()));
            // 3. Best-effort: schedule deletion so the JVM removes the old file on exit.
            //    The new JAR stays in its temp location; the operator must handle it manually.
            targetPath.toFile().deleteOnExit();
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static HttpURLConnection openGet(String url, String token) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL: " + url, e);
        }
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "PluginUpdater/1.0");
        // Tell GitHub to return the raw binary rather than JSON asset metadata
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(false); // we handle redirects ourselves
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    /**
     * Follows HTTP 3xx redirects up to 10 hops.
     * Pass {@code null} as {@code tokenForRedirects} to strip auth (GitHub asset → S3 flow).
     */
    private static HttpURLConnection followRedirects(HttpURLConnection conn, String tokenForRedirects)
        throws IOException {
        try {
            int hops = 0;
            while (hops < 10) {
                int status = conn.getResponseCode();
                if (status < 300 || status >= 400) break;
                String location = conn.getHeaderField("Location");
                if (location == null) break;
                conn.disconnect();
                conn = openGet(location, tokenForRedirects);
                hops++;
            }
            return conn;
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
    }
}
