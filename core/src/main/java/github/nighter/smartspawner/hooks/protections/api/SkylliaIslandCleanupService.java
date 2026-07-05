package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.skyblock.Island;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.list.gui.adminstacker.AdminStackerHolder;
import github.nighter.smartspawner.commands.list.gui.management.SpawnerManagementHolder;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.utils.BlockPos;
import fr.euphyllia.skyllia.api.SkylliaAPI;
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
        Set<SpawnerData> matchedSpawners = new HashSet<>();

        int matchedCount = 0;
        int failedCount = 0;

        // 1. Build a stable matched-spawner snapshot.
        for (SpawnerData spawner : allSpawners) {
            Location location = spawner.getSpawnerLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }

            try {
                if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) {
                    continue;
                }

                if (island.isInside(location)) {
                    spawnersToRemove.add(spawner.getSpawnerId());
                    matchedSpawners.add(spawner);
                    matchedCount++;

                    // 2. Set each matched spawner stop flag.
                    spawner.getSpawnerStop().set(true);
                }
            } catch (Exception e) {
                failedCount++;
                plugin.getLogger().warning("Failed to match spawner " + spawner.getSpawnerId() + " during island deletion: " + e.getMessage());
            }
        }

        if (spawnersToRemove.isEmpty()) {
            return;
        }

        // 3. Atomically detach matched IDs from SpawnerManager indexes.
        spawnerManager.removeSpawnersDataOnly(spawnersToRemove);

        // 4. After detachment, schedule GUI, hologram and hopper cleanup.
        for (SpawnerData spawner : matchedSpawners) {
            Location location = spawner.getSpawnerLocation();

            if (plugin.getRangeChecker() != null) {
                plugin.getRangeChecker().deactivateSpawner(spawner);
            }

            plugin.getSpawnerLocationLockManager().removeLock(location);

            Scheduler.runLocationTask(location, spawner::removeHologram);

            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (location.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                Scheduler.runLocationTask(location, () -> {
                    if (plugin.getHopperService() != null) {
                        plugin.getHopperService().getTracker().removeBelowSpawner(location.getBlock()); // This requires block lookup
                    }
                });
            }

            closeRelatedInventories(spawner);
        }

        // 5. Mark all IDs deleted in storage.
        for (String id : spawnersToRemove) {
            spawnerManager.markSpawnerDeleted(id);
        }

        // 6. Call spawnerStorage.flushChanges() exactly once.
        plugin.getSpawnerStorage().flushChanges();

        plugin.getLogger().info(String.format("Skyllia island cleanup queued for %s: matched %d, detached %d, failed %d", island.getId(), matchedCount, spawnersToRemove.size(), failedCount));
    }

    private void closeRelatedInventories(SpawnerData spawner) {
        Scheduler.runTask(() -> {
            plugin.getSpawnerGuiViewManager().closeAllViewersInventory(spawner);

            String spawnerId = spawner.getSpawnerId();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scheduler.runEntityTask(player, () -> closeManagementInventory(player, spawnerId));
            }
        });
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
