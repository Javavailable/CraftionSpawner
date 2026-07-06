package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class SpawnerRangeChecker {
    private static final long CHECK_INTERVAL = 20L;
    private final SmartSpawner plugin;
    private final SpawnerManager spawnerManager;
    private final ExecutorService executor;

    public SpawnerRangeChecker(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerManager = plugin.getSpawnerManager();
        this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "SmartSpawner-RangeCheck"));
        initializeRangeCheckTask();
    }

    private void initializeRangeCheckTask() {
        Scheduler.runTaskTimer(this::scheduleRegionSpecificCheck, CHECK_INTERVAL, CHECK_INTERVAL);
    }

    private void scheduleRegionSpecificCheck() {
        PlayerRangeWrapper[] rangePlayers = getRangePlayers();
        if (executor.isShutdown()) {
            return;
        }

        try {
            executor.execute(() -> {
                final List<SpawnerData> allSpawners = spawnerManager.getAllSpawners();
                final RangeMath rangeCheck = new RangeMath(rangePlayers, allSpawners);
                final boolean[] spawnersPlayerFound = rangeCheck.getActiveSpawners();

                for (int i = 0; i < spawnersPlayerFound.length; i++) {
                    final boolean expectedStop = !spawnersPlayerFound[i];
                    final SpawnerData sd = allSpawners.get(i);
                    if (sd.getSpawnerStop().compareAndSet(!expectedStop, expectedStop)) {
                        Scheduler.runLocationTask(sd.getSpawnerLocation(), () -> {
                            if (!isSpawnerValid(sd)) {
                                cleanupRemovedSpawner(sd);
                                return;
                            }
                            if (sd.getSpawnerStop().get() == expectedStop) {
                                handleSpawnerStateChange(sd, expectedStop);
                            }
                        });
                    } else if (sd.getSpawnerActive() && !sd.getSpawnerStop().get()) {
                        checkAndSpawnLoot(sd);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown raced the periodic scheduler callback; no work remains to process.
        }
    }

    private PlayerRangeWrapper[] getRangePlayers() {
        final Player[] onlinePlayers = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        final PlayerRangeWrapper[] rangePlayers = new PlayerRangeWrapper[onlinePlayers.length];
        int i = 0;
        for (Player p : onlinePlayers) {
            boolean conditions = p.isConnected() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR;
            rangePlayers[i++] = new PlayerRangeWrapper(
                    p.getWorld().getUID(), p.getX(), p.getY(), p.getZ(), conditions);
        }
        return rangePlayers;
    }

    private boolean isSpawnerValid(SpawnerData spawner) {
        SpawnerData current = spawnerManager.getSpawnerById(spawner.getSpawnerId());
        if (current == null || current != spawner) {
            return false;
        }
        Location loc = spawner.getSpawnerLocation();
        return loc != null && loc.getWorld() != null;
    }

    private void cleanupRemovedSpawner(SpawnerData staleInstance) {
        if (staleInstance != null) {
            // Reset only the exact stale snapshot. A replacement registered under the same ID must
            // never have its generated-output lifecycle invalidated by an old range-check task.
            staleInstance.resetGeneratedLootState();
        }
    }

    private void handleSpawnerStateChange(SpawnerData spawner, boolean shouldStop) {
        if (!shouldStop) {
            activateSpawner(spawner);
        } else {
            deactivateSpawner(spawner);
        }
        if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
            plugin.getSpawnerGuiViewManager().forceStateChangeUpdate(spawner);
        }
    }

    public void activateSpawner(SpawnerData spawner) {
        deactivateSpawner(spawner);
        if (!spawner.getSpawnerActive()) {
            return;
        }
        spawner.setLastSpawnTime(System.currentTimeMillis());
        if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
            plugin.getSpawnerGuiViewManager().updateSpawnerMenuViewers(spawner);
        }
    }

    public void deactivateSpawner(SpawnerData spawner) {
        spawner.resetGeneratedLootState();
    }

    private void checkAndSpawnLoot(SpawnerData spawner) {
        long cachedDelay = spawner.getCachedSpawnDelay();
        if (cachedDelay == 0) {
            cachedDelay = (spawner.getSpawnDelay() + 20L) * 50L;
            spawner.setCachedSpawnDelay(cachedDelay);
        }
        final long finalCachedDelay = cachedDelay;

        long currentTime = System.currentTimeMillis();
        long lastSpawnTime = spawner.getLastSpawnTime();
        long timeElapsed = currentTime - lastSpawnTime;
        if (timeElapsed < cachedDelay) {
            return;
        }

        try {
            if (!spawner.getDataLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return;
            }
            try {
                currentTime = System.currentTimeMillis();
                lastSpawnTime = spawner.getLastSpawnTime();
                timeElapsed = currentTime - lastSpawnTime;
                if (timeElapsed < cachedDelay || !spawner.getSpawnerActive() || spawner.getSpawnerStop().get()) {
                    return;
                }

                Location spawnerLocation = spawner.getSpawnerLocation();
                if (spawnerLocation == null) {
                    return;
                }
                Scheduler.runLocationTask(spawnerLocation, () -> {
                    if (!spawner.getSpawnerActive() || spawner.getSpawnerStop().get()) {
                        spawner.resetGeneratedLootState();
                        return;
                    }

                    long timeSinceLastSpawn = System.currentTimeMillis() - spawner.getLastSpawnTime();
                    if (timeSinceLastSpawn < finalCachedDelay - 100) {
                        if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
                            plugin.getSpawnerGuiViewManager().updateSpawnerMenuViewers(spawner);
                        }
                        return;
                    }

                    if (spawner.hasPreGeneratedLoot()) {
                        SpawnerData.PreGeneratedLootBatch batch = spawner.claimPreGeneratedLootBatch();
                        if (batch != null) {
                            plugin.getSpawnerLootGenerator().addPreGeneratedLoot(
                                    spawner,
                                    batch.getItems(),
                                    batch.getExperience(),
                                    System.currentTimeMillis(),
                                    batch.getGenerationEpoch());
                        }
                    } else {
                        plugin.getSpawnerLootGenerator().spawnLootToSpawner(spawner);
                    }

                    if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
                        plugin.getSpawnerGuiViewManager().updateSpawnerMenuViewers(spawner);
                    }
                });
            } finally {
                spawner.getDataLock().unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void cleanup() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
