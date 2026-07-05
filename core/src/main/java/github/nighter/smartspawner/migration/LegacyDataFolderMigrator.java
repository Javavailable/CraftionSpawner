package github.nighter.smartspawner.migration;

import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.stream.Stream;

public class LegacyDataFolderMigrator {

    public static void migrateIfNeeded(Plugin plugin) {
        File oldFolder = new File(plugin.getDataFolder().getParentFile(), "SmartSpawner");
        File newFolder = plugin.getDataFolder(); // plugins/CraftionSpawner

        // Old folder absent -> do nothing
        if (!oldFolder.exists() || !oldFolder.isDirectory()) {
            return;
        }

        try {
            // Old folder exists, new folder absent -> move
            if (!newFolder.exists()) {
                plugin.getLogger().info("Migrating legacy SmartSpawner data folder...");
                Files.move(oldFolder.toPath(), newFolder.toPath());
                plugin.getLogger().info("Legacy data folder migrated successfully.");
                return;
            }

            // Old folder exists, new folder exists
            if (newFolder.isDirectory()) {
                boolean newFolderEmpty;
                try (Stream<Path> entries = Files.list(newFolder.toPath())) {
                    newFolderEmpty = entries.findFirst().isEmpty();
                }

                if (newFolderEmpty) {
                    // New folder exists but is truly empty -> remove new, move old
                    plugin.getLogger().info("Migrating legacy SmartSpawner data folder into empty new folder...");
                    Files.delete(newFolder.toPath());
                    Files.move(oldFolder.toPath(), newFolder.toPath());
                    plugin.getLogger().info("Legacy data folder migrated successfully.");
                } else {
                    // Both folders contain data
                    plugin.getLogger().warning("Both legacy SmartSpawner and new CraftionSpawner data folders exist and contain data!");
                    plugin.getLogger().warning("Migration aborted to prevent data loss. Continuing with CraftionSpawner folder.");
                }
            }
        } catch (IOException e) {
            // Migration failure
            plugin.getLogger().log(Level.SEVERE, "Failed to migrate legacy SmartSpawner data folder. Exception: " + e.getMessage(), e);
        }
    }
}
