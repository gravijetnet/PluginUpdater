package net.gravijet.pluginupdater.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Simple glob-to-regex matcher used to identify release asset filenames.
 *
 * <p>Supported wildcards:
 * <ul>
 *   <li>{@code *} — matches any sequence of characters (including none)
 *   <li>{@code ?} — matches exactly one character
 * </ul>
 *
 * <p>Matching is case-insensitive to be lenient about JAR naming conventions.
 */
public final class GlobMatcher {

    /** Cache of compiled patterns — globs are repeated across many asset names per check cycle. */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private GlobMatcher() {}

    /**
     * Returns {@code true} if {@code filename} matches the given {@code glob} pattern.
     *
     * @param glob     pattern such as {@code "EssentialsX-*.jar"}
     * @param filename asset filename to test, e.g. {@code "EssentialsX-2.20.1.jar"}
     */
    public static boolean matches(String glob, String filename) {
        return PATTERN_CACHE.computeIfAbsent(glob, GlobMatcher::toPattern)
                            .matcher(filename)
                            .matches();
    }

    private static Pattern toPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*'  -> regex.append(".*");
                case '?'  -> regex.append(".");
                case '.'  -> regex.append("\\.");
                // Escape regex special characters that are not glob wildcards
                case '\\', '^', '$', '+', '{', '}', '[', ']', '|', '(', ')' ->
                    regex.append('\\').append(c);
                default   -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
