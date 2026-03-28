package net.gravijet.pluginupdater.core.util;

/**
 * Color code utility — converts Minecraft-style {@code &} codes to section-sign {@code §} codes.
 *
 * <p>Usage: {@code CC.c("&a[PluginUpdater] &7Ready.")}
 *
 * <p>On Paper, the console renderer converts {@code §} codes to ANSI colors.
 * On Velocity, the JUL bridge strips them before passing to SLF4J.
 */
public final class CC {

    private CC() {}

    /** Translates {@code &} color codes to {@code §} equivalents. */
    public static String c(String msg) {
        if (msg == null) return null;
        return msg.replace('&', '\u00A7'); // § = section sign
    }

    /** Strips all {@code §} color codes from a string (e.g. for plain log output). */
    public static String strip(String msg) {
        if (msg == null) return null;
        return msg.replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "");
    }
}
