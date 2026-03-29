package net.gravijet.pluginupdater.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginDescription;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class VelocityUpdateCommand implements SimpleCommand {

    private final PluginDescription description;

    public VelocityUpdateCommand(PluginDescription description) {
        this.description = description;
    }

    @Override
    public void execute(Invocation invocation) {
        String fullVersion = description.getVersion().orElse("1.0.0-blocal.dev");

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

        invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c&lPluginUpdater &7» &fVersion Information"));
        invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cVersion&8: &f" + version));
        invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cBuild&8:   &f#" + buildNumber));
        invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cCommit&8:  &f" + commit));
    }
}
