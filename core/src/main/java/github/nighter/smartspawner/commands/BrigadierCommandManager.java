package github.nighter.smartspawner.commands;

import github.nighter.smartspawner.SmartSpawner;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrigadierCommandManager {
    private final SmartSpawner plugin;
    private final MainCommand mainCommand;

    public BrigadierCommandManager(SmartSpawner plugin) {
        this.plugin = plugin;
        this.mainCommand = new MainCommand(plugin);
    }

    /**
     * Register all Brigadier commands with Paper's command system
     */
    public void registerCommands() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();

        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            // Register main command
            commands.register(mainCommand.buildCommand(), "Main CraftionSpawner command");

            // Register aliases
            mainCommand.buildAliases().forEach(node -> {
                commands.register(node, "CraftionSpawner command alias");
            });
        });
    }
}
    