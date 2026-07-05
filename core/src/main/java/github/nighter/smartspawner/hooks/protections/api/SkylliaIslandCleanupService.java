package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.list.gui.adminstacker.AdminStackerHolder;
import github.nighter.smartspawner.commands.list.gui.management.SpawnerManagementHolder;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Data-only cleanup of CraftionSpawner spawners after a confirmed Skyllia island deletion.
 *
 * <p>This service never modifies physical blocks, drops items, gives items/XP, sells contents,
 * pays money, or force-loads chunks. It only detaches spawners from runtime indexes and queues
 * their persistence deletion.
 */
public class SkylliaIslandCleanupService {

    // Exactly 10 total processing attempts; the first pass is attempt 1.
    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_DELAY_TICKS = 20L;

    private final SmartSpawner plugin;
    private final SkylliaHook hook;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public SkylliaIslandCleanupService(SmartSpawner plugin, SkylliaHook hook) {
        this.plugin = plugin;
        this.hook = hook;
    }

    /** Signals that no further deferred spawner retries should be scheduled. */
    public void shutdown() {
        shuttingDown.set(true);
    }

    /**
     * Immutable, truthful summary of the synchronous scheduling/runtime outcome of a cleanup.
     * Location-thread and entity-thread task exceptions are logged separately and are NOT
     * aggregated here, because this result is produced before those tasks run.
     */
    public static final class CleanupResult {
        public final int matched;
        public final int matchFailures;
        public final int detached;
        public final int exhausted;
        public final int persistenceDeletionsQueued;
        public final int locationTasksScheduled;
        public final int guiTasksScheduled;
        public final boolean aborted;

        CleanupResult(int matched, int matchFailures, int detached, int exhausted,
                      int persistenceDeletionsQueued, int locationTasksScheduled,
                      int guiTasksScheduled, boolean aborted) {
            this.matched = matched;
            this.matchFailures = matchFailures;
            this.detached = detached;
            this.exhausted = exhausted;
            this.persistenceDeletionsQueued = persistenceDeletionsQueued;
            this.locationTasksScheduled = locationTasksScheduled;
            this.guiTasksScheduled = guiTasksScheduled;
            this.aborted = aborted;
        }
    }

    /** Mutable accumulator shared across the retry chain for a single island cleanup. */
    private static final class Stats {
        int matched;
        int matchFailures;
        int detached;
        int persistenceDeletionsQueued;
        int locationTasksScheduled;
        int guiTasksScheduled;
    }

    /**
     * Begins cleanup for a confirmed-deleted island. The returned future completes ONLY after
     * all matched candidates have been processed and every deferred retry has finished, been
     * exhausted, or been aborted. This method never blocks and never calls join().
     */
    public CompletableFuture<CleanupResult> cleanupIsland(Island island) {
        CompletableFuture<CleanupResult> future = new CompletableFuture<>();
        try {
            SpawnerManager spawnerManager = plugin.getSpawnerManager();
            List<SpawnerData> allSpawners = spawnerManager.getAllSpawners();
            Set<SpawnerData> matchedSpawners = new HashSet<>();

            int matchedCount = 0;
            int matchFailedCount = 0;

            // A. Match candidates strictly inside the exact island boundary in a Skyllia world.
            for (SpawnerData spawner : allSpawners) {
                Location location = spawner.getSpawnerLocation();
                if (location == null || location.getWorld() == null) {
                    continue;
                }

                try {
                    if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) {
                        continue; // non-Skyllia world -> retain spawner
                    }
                    if (island.isInside(location)) {
                        matchedSpawners.add(spawner);
                        matchedCount++;
                    }
                    // Skyllia world outside island bounds -> retain spawner
                } catch (Exception e) {
                    matchFailedCount++;
                    plugin.getLogger().warning("Failed to match spawner " + spawner.getSpawnerId() + " during island deletion: " + e.getMessage());
                }
            }

            Stats stats = new Stats();
            stats.matched = matchedCount;
            stats.matchFailures = matchFailedCount;

            if (matchedSpawners.isEmpty()) {
                logQueued(island.getId(), stats, 0);
                future.complete(new CleanupResult(stats.matched, stats.matchFailures, 0, 0, 0, 0, 0, false));
                return future;
            }

            // First processing pass is attempt 1.
            processSpawners(island.getId(), matchedSpawners, 1, stats, future);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error starting Skyllia island cleanup", e);
            if (!future.isDone()) {
                future.completeExceptionally(e);
            }
        }
        return future;
    }

    private void processSpawners(UUID islandId, Set<SpawnerData> candidates, int attempt,
                                 Stats stats, CompletableFuture<CleanupResult> future) {
        try {
            // Before every pass (including each deferred retry) verify we may still run.
            if (isAborted()) {
                completeAborted(islandId, candidates, stats, future);
                return;
            }

            SpawnerManager spawnerManager = plugin.getSpawnerManager();
            Set<SpawnerData> deferred = new HashSet<>();
            Set<SpawnerData> detachedSpawners = new HashSet<>();
            Set<String> detachedIds = new HashSet<>();

            // B. Safely claim & detach candidates.
            for (SpawnerData spawner : candidates) {
                Location loc = spawner.getSpawnerLocation();

                ReentrantLock lock = plugin.getSpawnerLocationLockManager().getLock(loc);
                if (!lock.tryLock()) {
                    // Never block; retry on a later attempt.
                    deferred.add(spawner);
                    continue;
                }

                boolean removalClaimed = false;
                try {
                    // Atomic removal claim; fails if a sale is currently active.
                    if (!spawner.tryBeginRemoval()) {
                        deferred.add(spawner);
                        continue;
                    }
                    removalClaimed = true;

                    // Verify the EXACT expected instance is still indexed by id and location.
                    SpawnerData byId = spawnerManager.getSpawnerById(spawner.getSpawnerId());
                    SpawnerData byLoc = spawnerManager.getSpawnerByLocation(loc);
                    if (byId != spawner || byLoc != spawner) {
                        // Replaced or already gone - never touch a replacement instance.
                        continue;
                    }

                    spawner.getSpawnerStop().set(true);

                    boolean removed = spawnerManager.removeSpawnerDataOnly(spawner.getSpawnerId(), spawner);
                    if (removed) {
                        detachedSpawners.add(spawner);
                        detachedIds.add(spawner.getSpawnerId());
                        // Keep removalPending TRUE after a successful detach so stale references
                        // cannot start a sale or convert this deletion back into a modification.
                        removalClaimed = false;
                    }
                    // If not removed, the finally block cancels the claim and retains the spawner.
                } finally {
                    if (removalClaimed) {
                        // Deferred or detach did not happen - release the claim, retain the spawner.
                        spawner.cancelRemoval();
                    }
                    lock.unlock();
                    // Intentionally NOT calling removeLock(loc): another thread may still hold a
                    // reference to this ReentrantLock. Removing it would let a second lock object
                    // be created for the same location and break mutual exclusion. The periodic
                    // lock-cleanup task reclaims unused entries safely.
                }
            }

            // C. Mark successfully detached IDs deleted (deletion tombstones).
            for (String id : detachedIds) {
                spawnerManager.markSpawnerDeleted(id);
            }
            stats.persistenceDeletionsQueued += detachedIds.size();

            // D. One persistence flush per batch, only when at least one spawner was detached.
            if (!detachedIds.isEmpty()) {
                plugin.getSpawnerStorage().flushChanges();
            }

            // E. Best-effort side effects (holograms, hoppers, GUIs). Failures are logged
            //    separately with the spawner id and are NOT folded into the final result.
            for (SpawnerData spawner : detachedSpawners) {
                Location location = spawner.getSpawnerLocation();

                if (plugin.getRangeChecker() != null) {
                    try {
                        plugin.getRangeChecker().deactivateSpawner(spawner);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed range cleanup for spawner " + spawner.getSpawnerId() + ": " + ex.getMessage());
                    }
                }

                final String spawnerIdForLog = spawner.getSpawnerId();
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
                        plugin.getLogger().warning("Failed location-thread cleanup for spawner " + spawnerIdForLog + ": " + ex.getMessage());
                    }
                });
                stats.locationTasksScheduled++;

                closeRelatedInventories(spawner);
                stats.guiTasksScheduled++;
            }

            stats.detached += detachedSpawners.size();

            // Retry decision: exactly MAX_ATTEMPTS total passes, 20 ticks apart, no sleeps/blocking.
            if (!deferred.isEmpty() && attempt < MAX_ATTEMPTS) {
                if (isAborted()) {
                    completeAborted(islandId, deferred, stats, future);
                    return;
                }
                Scheduler.runTaskLaterAsync(() ->
                        processSpawners(islandId, deferred, attempt + 1, stats, future), RETRY_DELAY_TICKS);
            } else {
                int exhausted = deferred.size();
                if (exhausted > 0) {
                    for (SpawnerData s : deferred) {
                        plugin.getLogger().warning("Failed to detach spawner " + s.getSpawnerId()
                                + " after " + MAX_ATTEMPTS + " attempts (locked or selling).");
                    }
                }
                logQueued(islandId, stats, exhausted);
                future.complete(new CleanupResult(stats.matched, stats.matchFailures, stats.detached, exhausted,
                        stats.persistenceDeletionsQueued, stats.locationTasksScheduled, stats.guiTasksScheduled, false));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error during Skyllia island cleanup for " + islandId, e);
            if (!future.isDone()) {
                future.completeExceptionally(e);
            }
        }
    }

    /**
     * @return true if cleanup must stop: service shutting down, plugin disabled, or Skyllia
     * hook/plugin no longer available/enabled.
     */
    private boolean isAborted() {
        if (shuttingDown.get() || !plugin.isEnabled()) {
            return true;
        }
        if (hook == null || !hook.isEnabled()) {
            return true;
        }
        Plugin skyllia = hook.getSkylliaPlugin();
        return skyllia == null || !skyllia.isEnabled();
    }

    private void completeAborted(UUID islandId, Set<SpawnerData> remaining, Stats stats,
                                CompletableFuture<CleanupResult> future) {
        // Do not detach remaining spawners; their persistence records are retained.
        int remainingCount = (remaining != null) ? remaining.size() : 0;
        plugin.getLogger().info(String.format(
                "Skyllia island cleanup aborted for %s: detached %d, remaining %d",
                islandId, stats.detached, remainingCount));
        if (!future.isDone()) {
            future.complete(new CleanupResult(stats.matched, stats.matchFailures, stats.detached, remainingCount,
                    stats.persistenceDeletionsQueued, stats.locationTasksScheduled, stats.guiTasksScheduled, true));
        }
    }

    private void logQueued(UUID islandId, Stats stats, int exhausted) {
        plugin.getLogger().info(String.format(
                "Skyllia island cleanup queued for %s: matched %d, detached %d, exhausted %d, "
                        + "persistence deletions queued %d, location tasks %d, GUI tasks %d, match failures %d",
                islandId, stats.matched, stats.detached, exhausted,
                stats.persistenceDeletionsQueued, stats.locationTasksScheduled, stats.guiTasksScheduled, stats.matchFailures));
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
