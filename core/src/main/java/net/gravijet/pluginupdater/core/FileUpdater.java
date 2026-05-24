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
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Handles the download and atomic file replacement for plugin updates.
 *
 * <p>Downloads use only {@link HttpURLConnection} — no third-party HTTP libraries.
 * GitHub release assets are accessed via the API asset endpoint
 * ({@code /releases/assets/{id}}) which redirects through AWS S3; redirects are
 * handled manually so the auth header is only sent to {@code api.github.com}
 * and not leaked to S3.
 */
public class FileUpdater {

    /** Safety cap — prevents runaway downloads from exhausting disk space. */
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024; // 256 MB

    private final Logger logger;

    public FileUpdater(Logger logger) {
        this.logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Downloads the JAR at {@code downloadUrl} to a temporary file.
     *
     * @param downloadUrl   the download URL from the release API
     * @param destinationDir directory the temp file is created in — must be on the same
     *                       filesystem as the final target so {@link #atomicReplace} can
     *                       perform a true atomic move (the system temp dir is typically
     *                       on a different volume, which silently degrades the move to a
     *                       non-atomic copy and orphans partial files)
     * @param displayName   human-readable name used in log messages (e.g. {@code "EssentialsX-2.20.jar"})
     * @param accessToken   GitHub token, or {@code null} for public assets
     * @return path to the temp file on success, or {@code null} if the download failed
     */
    public Path downloadToTemp(String downloadUrl, Path destinationDir,
                               String displayName, String accessToken) {
        HttpURLConnection conn = null;
        try {
            // Validate before connecting: rejects null/empty URLs, non-HTTPS schemes,
            // and private/loopback IP targets (SSRF prevention).
            requireSafeHttps(downloadUrl);

            // GitHub redirects asset API endpoint → S3.
            // We follow redirects manually so the auth header is not leaked to S3.
            conn = followRedirects(openGet(downloadUrl, accessToken), null);

            int status = conn.getResponseCode();
            if (status != 200) {
                // Do not log the full URL — after redirect-following it may be an S3
                // pre-signed URL whose query parameters contain time-limited credentials.
                logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                    + " &7failed with HTTP &e" + status + "&7."));
                return null;
            }
            // Reject responses that are clearly not a JAR binary. GitHub returns
            // JSON metadata (Content-Type: application/json) when the auth token is wrong
            // or the Accept header is not honoured. Writing JSON into a .jar file on disk
            // produces a corrupt plugin that the server will fail to load.
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
                logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                    + " &7rejected: server returned JSON instead of a binary file."
                    + " Check your access-token and asset-pattern in config.yml."));
                return null;
            }

            long contentLength = conn.getContentLengthLong(); // -1 if server omits header
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                    + " &7refused: Content-Length &e" + contentLength
                    + " &7bytes exceeds the &e" + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + " MB&7 safety limit."));
                return null;
            }

            // Create the temp file in the destination directory (same filesystem as the
            // final target) so atomicReplace() can do a real atomic move. The ".tmp"
            // suffix keeps partial/in-flight files out of the server's "*.jar" plugin
            // scan and out of ShutdownHandler#findExistingJar.
            Path temp = Files.createTempFile(destinationDir, "pluginupdater-", "-" + displayName + ".tmp");
            boolean success = false;
            try {
                long bytesWritten = 0;
                // The OutputStream is closed in its own try-with-resources block that
                // completes *before* the size/truncation checks so the file handle is
                // always released before the finally block attempts deletion.  This is
                // critical on Windows where an open handle prevents Files.deleteIfExists.
                boolean limitExceeded = false;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buf = new byte[16_384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        bytesWritten += n;
                        if (bytesWritten > MAX_DOWNLOAD_BYTES) {
                            limitExceeded = true;
                            break; // exit loop; TWR closes the stream before finally runs
                        }
                    }
                }
                if (limitExceeded) {
                    logger.warning(CC.c("&c[PluginUpdater] &7Download of &e" + displayName
                        + " &7aborted: exceeded the &e"
                        + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + " MB&7 safety limit."));
                    return null; // success stays false → finally deletes the partial temp file
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
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            // Fall through to regular move
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Atomic move failed for &e"
                + targetPath.getFileName() + "&7: " + e + " — trying regular move."));
        }

        // 2. Regular replace
        try {
            Files.move(sourceTempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            logger.warning(CC.c("&c[PluginUpdater] &7Could not replace &e"
                + targetPath.getFileName()
                + " &7(file may be locked — common on Windows): " + e));
            // 3. Best-effort: schedule the old (locked) file for OS-level deletion on JVM exit.
            //    The new JAR stays in its temp location so the operator can copy it manually.
            targetPath.toFile().deleteOnExit();
            return false;
        }
    }

    // ── Security helpers ───────────────────────────────────────────────────

    /**
     * Validates that {@code url} is a non-null HTTPS URL with a public (non-private) host.
     * Throws {@link IOException} for null/empty URLs, non-HTTPS schemes, and
     * private/loopback IP ranges to prevent server-side request forgery (SSRF).
     */
    private static void requireSafeHttps(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("Download URL is null or empty.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL: " + url, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Rejected non-HTTPS URL: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("URL has no host: " + url);
        }
        if (isPrivateHost(host)) {
            throw new IOException("Rejected redirect to private/loopback host: " + host);
        }
    }

    /**
     * Returns {@code true} if {@code host} resolves to a private or loopback address space.
     * Checks well-known hostnames and IPv4/IPv6 private ranges without performing DNS lookups.
     */
    private static boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        // Well-known local hostnames
        if (h.equals("localhost") || h.endsWith(".localhost")
                || h.endsWith(".local") || h.endsWith(".internal")
                || h.equals("0.0.0.0")) {
            return true;
        }
        // IPv4 loopback / private / link-local ranges
        if (h.startsWith("127.")      // 127.0.0.0/8 loopback
                || h.startsWith("10.")    // 192.0.2.1/8 RFC-1918
                || h.startsWith("192.168.")  // 192.0.2.1/16 RFC-1918
                || h.startsWith("169.254.")  // 192.0.2.1/16 link-local
                || h.startsWith("0.")) {     // 0.0.0.0/8
            return true;
        }
        // 192.0.2.1/10 — RFC 6598 CGNAT shared address space (used by Kubernetes, ISPs)
        if (h.startsWith("100.")) {
            int second = parseOctet(h, 4);
            // second == -1 means malformed/leading-zero octet; block conservatively since
            // the OS may interpret it as a value in the private range.
            if (second == -1 || (second >= 64 && second <= 127)) return true;
        }
        // 192.0.2.1/12 (172.16–172.31).
        // Use parseOctet() rather than Integer.parseInt() so leading-zero octets like
        // "172.016.x.x" (which the OS resolves as 172.16.x.x) are not silently passed.
        if (h.startsWith("172.")) {
            int second = parseOctet(h, 4);
            // second == -1 means malformed/leading-zero octet; block conservatively.
            if (second == -1 || (second >= 16 && second <= 31)) return true;
        }
        // IPv6 in brackets: [::1], [fe80::1%eth0].
        // RFC 3986 allows exactly one bracket layer; unwrap and check the inner address.
        // Malformed bracket form (no closing ']') is treated as unsafe.
        if (h.startsWith("[")) {
            if (!h.endsWith("]")) return true; // malformed — treat as unsafe
            h = h.substring(1, h.length() - 1).toLowerCase(Locale.ROOT);
        }
        // Strip zone ID (e.g. fe80::1%eth0 → fe80::1) before comparison.
        int zoneIdx = h.indexOf('%');
        if (zoneIdx >= 0) h = h.substring(0, zoneIdx);
        // Expand common compressed loopback forms before comparison.
        // Full-form ::1 equivalents: "0:0:0:0:0:0:0:1" and "::1".
        if (h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        // IPv4-mapped addresses (::ffff:x.x.x.x) — check the embedded IPv4 part.
        if (h.startsWith("::ffff:")) {
            String embedded = h.substring(7);
            if (isPrivateHost(embedded)) return true;
        }
        // Link-local (fe80::/10) and ULA (fc00::/7 covers fc and fd prefixes)
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true;
        return false;
    }

    /**
     * Parses the decimal octet that starts at {@code offset} in {@code host} (dot-delimited).
     * Returns -1 on any parse error (non-digit, leading zeros, empty, overflow) so callers
     * can treat the result as "not in any known safe range" and block it conservatively.
     * Leading zeros are rejected because the OS may interpret them as octal (e.g. "016" → 14).
     */
    private static int parseOctet(String host, int offset) {
        int dot = host.indexOf('.', offset);
        String raw = (dot == -1) ? host.substring(offset) : host.substring(offset, dot);
        if (raw.isEmpty()) return -1;
        if (raw.length() > 1 && raw.charAt(0) == '0') return -1; // leading zero — treat as unsafe
        int val = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return -1;
            val = val * 10 + (c - '0');
            if (val > 255) return -1;
        }
        return val;
    }

    // ── Network helpers ────────────────────────────────────────────────────

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
     * Each redirect target is validated with {@link #requireSafeHttps} to prevent SSRF.
     * Pass {@code null} as {@code tokenForRedirects} to strip auth (GitHub asset → S3 flow).
     */
    private static HttpURLConnection followRedirects(HttpURLConnection conn, String tokenForRedirects)
        throws IOException {
        try {
            int hops = 0;
            while (true) {
                int status = conn.getResponseCode();
                if (status < 300 || status >= 400) break;
                String location = conn.getHeaderField("Location");
                if (location == null) break;
                if (++hops > 10) {
                    throw new IOException("Too many redirects (> 10 hops) — possible redirect loop.");
                }
                conn.disconnect();
                requireSafeHttps(location); // SSRF: validate every redirect target
                conn = openGet(location, tokenForRedirects);
            }
            return conn;
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
    }
}
