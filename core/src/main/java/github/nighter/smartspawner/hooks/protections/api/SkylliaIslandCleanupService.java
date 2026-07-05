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
        Set<SpawnerData> matchedSpawners = new HashSet<>();

        int matchedCount = 0;
        int matchFailedCount = 0;

        // A. Match candidates
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
                    matchedSpawners.add(spawner);
                    matchedCount++;
                }
            } catch (Exception e) {
                matchFailedCount++;
                plugin.getLogger().warning("Failed to match spawner " + spawner.getSpawnerId() + " during island deletion: " + e.getMessage());
            }
        }

        if (matchedSpawners.isEmpty()) {
            return;
        }

        processSpawners(island.getId(), matchedSpawners, 0, matchedCount, 0, 0);
    }

    private void processSpawners(java.util.UUID islandId, Set<SpawnerData> candidates, int attempt, int totalMatched, int totalDetached, int totalSideEffectFailures) {
        SpawnerManager spawnerManager = plugin.getSpawnerManager();
        Set<SpawnerData> deferred = new HashSet<>();
        Set<SpawnerData> detachedSpawners = new HashSet<>();
        Set<String> detachedIds = new HashSet<>();

        // B. Safely claim/detach candidates
        for (SpawnerData spawner : candidates) {
            Location loc = spawner.getSpawnerLocation();

            java.util.concurrent.locks.ReentrantLock lock = plugin.getSpawnerLocationLockManager().getLock(loc);
            boolean acquired = lock.tryLock();

            if (!acquired) {
                deferred.add(spawner);
                continue;
            }

            try {
                // Verify still present
                SpawnerData byId = spawnerManager.getSpawnerById(spawner.getSpawnerId());
                SpawnerData byLoc = spawnerManager.getSpawnerByLocation(loc);

                if (byId != spawner || byLoc != spawner) {
                    continue; // Disappeared or replaced
                }

                if (spawner.isSelling()) {
                    deferred.add(spawner);
                    continue;
                }

                // Safe to detach
                spawner.getSpawnerStop().set(true);

                Set<String> removed = spawnerManager.removeSpawnersDataOnly(Set.of(spawner.getSpawnerId()));
                if (removed.contains(spawner.getSpawnerId())) {
                    detachedSpawners.add(spawner);
                    detachedIds.add(spawner.getSpawnerId());
                }
            } finally {
                lock.unlock();
                plugin.getSpawnerLocationLockManager().removeLock(loc);
            }
        }

        // C. Mark successfully detached IDs deleted
        for (String id : detachedIds) {
            spawnerManager.markSpawnerDeleted(id);
        }

        // D. Flush deletion queue once
        if (!detachedIds.isEmpty()) {
            plugin.getSpawnerStorage().flushChanges();
        }

        // E. Schedule best-effort GUI/hologram/hopper cleanup
        int currentSideEffectFailures = 0;
        for (SpawnerData spawner : detachedSpawners) {
            try {
                Location location = spawner.getSpawnerLocation();

                if (plugin.getRangeChecker() != null) {
                    try {
                        plugin.getRangeChecker().deactivateSpawner(spawner);
                    } catch (Exception ex) {
                        currentSideEffectFailures++;
                        plugin.getLogger().warning("Failed range cleanup for spawner " + spawner.getSpawnerId() + ": " + ex.getMessage());
                    }
                }

                Scheduler.runLocationTask(location, () -> {
                    try {
                        int chunkX = location.getBlockX() >> 4;
                        int chunkZ = location.getBlockZ() >> 4;
                        if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                            return;
                        }

                        spawner.removeHologram();

                        if (plugin.getHopperService() != null) {
                            plugin.getHopperService().getTracker().removeBelowSpawner(location.getBlock());
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed location-thread cleanup for spawner " + spawner.getSpawnerId() + ": " + ex.getMessage());
                    }
                });

                closeRelatedInventories(spawner);
            } catch (Exception e) {
                currentSideEffectFailures++;
                plugin.getLogger().warning("Failed side-effect cleanup for spawner " + spawner.getSpawnerId() + ": " + e.getMessage());
            }
        }

        int newTotalDetached = totalDetached + detachedSpawners.size();
        int newTotalSideEffectFailures = totalSideEffectFailures + currentSideEffectFailures;

        if (!deferred.isEmpty() && attempt < 10) {
            Scheduler.runTaskLaterAsync(() -> {
                processSpawners(islandId, deferred, attempt + 1, totalMatched, newTotalDetached, newTotalSideEffectFailures);
            }, 20L);
        } else {
            if (!deferred.isEmpty()) {
                for (SpawnerData s : deferred) {
                    plugin.getLogger().warning("Failed to detach spawner " + s.getSpawnerId() + " after retries (locked or selling).");
                }
            }
            // Final log
            plugin.getLogger().info(String.format("Skyllia island cleanup queued for %s: matched %d, detached %d, deferred %d, side-effect failures %d",
                islandId, totalMatched, newTotalDetached, deferred.size(), newTotalSideEffectFailures));
        }
    }

    private void closeRelatedInventories(SpawnerData spawner) {
        Scheduler.runTask(() -> {
            try {
                plugin.getSpawnerGuiViewManager().closeAllViewersInventory(spawner);

                String spawnerId = spawner.getSpawnerId();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Scheduler.runEntityTask(player, () -> {
                        try {
                            closeManagementInventory(player, spawnerId);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed closing inventory for player " + player.getName() + ": " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed closing related inventories for " + spawner.getSpawnerId() + ": " + e.getMessage());
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
