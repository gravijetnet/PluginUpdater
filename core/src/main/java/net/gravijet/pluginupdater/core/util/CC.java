package net.gravijet.pluginupdater.core.util;

/**
 * Color code utility — converts Minecraft-style {@code &} codes to ANSI escape sequences.
 *
 * <p>Usage: {@code CC.c("&a[PluginUpdater] &7Ready.")}
 *
 * <p>On Paper the JUL console forwards ANSI codes directly to the terminal.
 * On Velocity, {@link #strip(String)} removes ANSI codes before passing to SLF4J.
 */
public final class CC {

    private static final String ESC = "\u001B[";

    private CC() {}

    /**
     * Translates {@code &} color codes to ANSI escape sequences.
     * Appends a reset ({@code \u001B[0m}) at the end if any code was translated.
     */
    public static String c(String msg) {
        if (msg == null) return null;
        char[] chars = msg.toCharArray();
        StringBuilder sb = new StringBuilder(msg.length() + 16);
        boolean hasColor = false;
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '&' && i + 1 < chars.length) {
                String ansi = ansiFor(chars[i + 1]);
                if (ansi != null) {
                    sb.append(ansi);
                    hasColor = true;
                    i++; // skip the code char
                    continue;
                }
            }
            sb.append(chars[i]);
        }
        if (hasColor) sb.append(ESC).append("0m");
        return sb.toString();
    }

    /** Strips ANSI escape sequences from a string (e.g. for plain log output on Velocity). */
    public static String strip(String msg) {
        if (msg == null) return null;
        return msg.replaceAll("\u001B\\[[^m]*m", "");
    }

    private static String ansiFor(char code) {
        return switch (code) {
            case '0'       -> ESC + "30m";  // black
            case '1'       -> ESC + "34m";  // dark blue
            case '2'       -> ESC + "32m";  // dark green
            case '3'       -> ESC + "36m";  // dark aqua
            case '4'       -> ESC + "31m";  // dark red
            case '5'       -> ESC + "35m";  // dark purple
            case '6'       -> ESC + "33m";  // gold
            case '7'       -> ESC + "37m";  // gray
            case '8'       -> ESC + "90m";  // dark gray
            case '9'       -> ESC + "94m";  // blue
            case 'a', 'A'  -> ESC + "92m";  // green
            case 'b', 'B'  -> ESC + "96m";  // aqua
            case 'c', 'C'  -> ESC + "91m";  // red
            case 'd', 'D'  -> ESC + "95m";  // light purple
            case 'e', 'E'  -> ESC + "93m";  // yellow
            case 'f', 'F'  -> ESC + "97m";  // white
            case 'k', 'K'  -> "";           // obfuscated — not supported in terminal
            case 'l', 'L'  -> ESC + "1m";   // bold
            case 'm', 'M'  -> ESC + "9m";   // strikethrough
            case 'n', 'N'  -> ESC + "4m";   // underline
            case 'o', 'O'  -> ESC + "3m";   // italic
            case 'r', 'R'  -> ESC + "0m";   // reset
            default        -> null;         // not a color code — keep literal
        };
    }
}
