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
            commands.register(mainCommand.buildAliasCommand(), "CraftionSpawner command alias");
            commands.register(mainCommand.buildAliasCommand2(), "CraftionSpawner command alias 2");
            commands.register(mainCommand.buildAliasCommand3(), "SmartSpawner legacy command alias");
            commands.register(mainCommand.buildAliasCommand4(), "CraftionSpawner short alias");
        });
    }
}
    