package net.gravijet.pluginupdater.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginDescription;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class VelocityUpdateCommand implements SimpleCommand {

    private final PluginDescription description;

    public VelocityUpdateCommand(PluginDescription description) {
        this.description = description;
    }

    @Override
    public void execute(Invocation invocation) {
        String version = description.getVersion().orElse("Unknown");
        String name = description.getName().orElse("PluginUpdater");

        invocation.source().sendMessage(
            Component.text(name + " » ", NamedTextColor.RED)
                .append(Component.text(name + " " + version, NamedTextColor.WHITE))
        );
        invocation.source().sendMessage(
            Component.text("Version: ", NamedTextColor.RED)
                .append(Component.text(version, NamedTextColor.WHITE))
        );
    }
}
