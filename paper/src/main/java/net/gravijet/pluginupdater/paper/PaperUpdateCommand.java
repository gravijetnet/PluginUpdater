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
        String version = description.getVersion();
        String fullName = description.getFullName();

        sender.sendMessage(CC.c("&c&lPluginUpdater &7» &f" + fullName));
        sender.sendMessage(CC.c("&cVersion&8: &f" + version));
        return true;
    }
}
