package net.gravijet.pluginupdater.paper;

import net.gravijet.pluginupdater.core.util.CC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;

public class PaperUpdateCommand implements CommandExecutor {

    private final PluginDescriptionFile description;

    public PaperUpdateCommand(PluginDescriptionFile description) {
        this.description = description;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // The full version string is like "1.0.0-b123.abcdefg"
        String fullVersion = description.getVersion();
        
        String[] parts = fullVersion.split("-b");
        String version = parts[0];
        String buildInfo = (parts.length > 1) ? parts[1] : "local";
        
        String buildNumber = "local";
        String commit = "dev";

        if (!buildInfo.equals("local")) {
            String[] buildParts = buildInfo.split("\\.");
            buildNumber = buildParts[0];
            commit = (buildParts.length > 1) ? buildParts[1] : "unknown";
        }

        sender.sendMessage(CC.c("&c&lPluginUpdater &7» &fVersion Information"));
        sender.sendMessage(CC.c("&cVersion&8: &f" + version));
        sender.sendMessage(CC.c("&cBuild&8:   &f#" + buildNumber));
        sender.sendMessage(CC.c("&cCommit&8:  &f" + commit));
        return true;
    }
}
