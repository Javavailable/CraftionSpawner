package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.skyblock.Island;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.list.gui.adminstacker.AdminStackerHolder;
import github.nighter.smartspawner.commands.list.gui.management.SpawnerManagementHolder;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.utils.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SkylliaIslandCleanupService {

    private final SmartSpawner plugin;

    public SkylliaIslandCleanupService(SmartSpawner plugin) {
        this.plugin = plugin;
    }

    public void cleanupIsland(Island island) {
        SpawnerManager spawnerManager = plugin.getSpawnerManager();
        List<SpawnerData> allSpawners = spawnerManager.getAllSpawners();
        Set<String> spawnersToRemove = new HashSet<>();

        int removedCount = 0;
        int failedCount = 0;

        for (SpawnerData spawner : allSpawners) {
            Location location = spawner.getSpawnerLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }

            try {
                // Determine if this spawner is exactly inside the logical island bounds
                if (island.isInside(location)) {
                    spawnersToRemove.add(spawner.getSpawnerId());
                    
                    // 1. Mark generation stopped
                    spawner.getSpawnerStop().set(true);

                    // 2. Prevent interactions and deactivate range checker
                    if (plugin.getRangeChecker() != null) {
                        plugin.getRangeChecker().deactivateSpawner(spawner);
                    }

                    // 3. Remove runtime references and locks
                    plugin.getSpawnerLocationLockManager().removeLock(location);

                    // 4. Remove holograms safely on location thread
                    Scheduler.runLocationTask(location, spawner::removeHologram);

                    // 5. Clean hopper tracking on location thread only if chunk loaded
                    int chunkX = location.getBlockX() >> 4;
                    int chunkZ = location.getBlockZ() >> 4;
                    if (location.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                        Scheduler.runLocationTask(location, () -> {
                            if (plugin.getHopperService() != null) {
                                plugin.getHopperService().getTracker().removeBelowSpawner(location.getBlock());
                            }
                        });
                    }

                    // 6. Close viewing inventories (async-safe scheduling)
                    closeRelatedInventories(spawner);

                    removedCount++;
                }
            } catch (Exception e) {
                failedCount++;
                plugin.getLogger().warning("Failed to clean up spawner " + spawner.getSpawnerId() + " during island deletion: " + e.getMessage());
            }
        }

        if (spawnersToRemove.isEmpty()) {
            return;
        }

        // 7. Remove completely from all SpawnerManager indexes (data-only batch)
        spawnerManager.removeSpawnersDataOnly(spawnersToRemove);

        // 8. Mark deleted in persistent storage
        for (String id : spawnersToRemove) {
            spawnerManager.markSpawnerDeleted(id);
        }

        plugin.getLogger().info(String.format("Skyllia island cleanup completed for %s. Removed: %d, Failed: %d", island.getId(), removedCount, failedCount));
    }

    private void closeRelatedInventories(SpawnerData spawner) {
        plugin.getSpawnerGuiViewManager().closeAllViewersInventory(spawner);

        String spawnerId = spawner.getSpawnerId();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scheduler.runEntityTask(player, () -> closeManagementInventory(player, spawnerId));
        }
    }

    private void closeManagementInventory(Player player, String spawnerId) {
        if (!player.isOnline()) {
            return;
        }

        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory == null) {
            return;
        }

        Object holder = inventory.getHolder(false);
        if (holder instanceof SpawnerManagementHolder managementHolder &&
                spawnerId.equals(managementHolder.getSpawnerId())) {
            player.closeInventory();
            return;
        }

        if (holder instanceof AdminStackerHolder adminStackerHolder &&
                adminStackerHolder.getSpawnerData() != null &&
                spawnerId.equals(adminStackerHolder.getSpawnerData().getSpawnerId())) {
            player.closeInventory();
        }
    }
}
