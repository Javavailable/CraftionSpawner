package github.nighter.smartspawner.spawner.gui.synchronization.utils;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Location;

import java.util.concurrent.TimeUnit;

/** Handles asynchronous loot pre-generation and early commit near the timer boundary. */
public final class LootPreGenerationHelper {

    private static final long PRE_GENERATION_THRESHOLD = 2000L;
    private static final long EARLY_SPAWN_THRESHOLD = 1000L;

    private final SmartSpawner plugin;

    public LootPreGenerationHelper(SmartSpawner plugin) {
        this.plugin = plugin;
    }

    public boolean shouldPreGenerateLoot(long timeUntilNextSpawn) {
        return timeUntilNextSpawn > 0 && timeUntilNextSpawn <= PRE_GENERATION_THRESHOLD;
    }

    public boolean shouldAddLootEarly(long timeUntilNextSpawn) {
        return timeUntilNextSpawn > 0 && timeUntilNextSpawn <= EARLY_SPAWN_THRESHOLD;
    }

    public void preGenerateLoot(SpawnerData spawner) {
        if (!spawner.getSpawnerActive() || spawner.getSpawnerStop().get()) {
            return;
        }

        long generationEpoch = spawner.tryBeginPreGeneration();
        if (generationEpoch < 0L) {
            return;
        }

        Location spawnerLocation = spawner.getSpawnerLocation();
        if (spawnerLocation == null) {
            spawner.finishPreGeneration(generationEpoch);
            return;
        }

        Scheduler.runLocationTask(spawnerLocation, () -> {
            if (!spawner.isGeneratedOutputEpoch(generationEpoch)
                    || !spawner.getSpawnerActive()
                    || spawner.getSpawnerStop().get()) {
                spawner.finishPreGeneration(generationEpoch);
                return;
            }

            plugin.getSpawnerLootGenerator().preGenerateLoot(spawner, (items, experience) -> {
                try {
                    if (spawner.getSpawnerActive() && !spawner.getSpawnerStop().get()) {
                        spawner.storePreGeneratedLoot(items, experience, generationEpoch);
                    }
                } finally {
                    spawner.finishPreGeneration(generationEpoch);
                }
            });
        });
    }

    /** Adds a pre-generated batch early while preserving its originating lifecycle epoch. */
    public void addPreGeneratedLootEarly(SpawnerData spawner, long cachedDelay) {
        if (!spawner.hasPreGeneratedLoot()) {
            return;
        }

        boolean resetAfterUnlock = false;
        try {
            if (!spawner.getDataLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                return;
            }
            try {
                long currentTime = System.currentTimeMillis();
                long lastSpawnTime = spawner.getLastSpawnTime();
                long timeElapsed = currentTime - lastSpawnTime;
                long remainingTime = cachedDelay - timeElapsed;

                if (remainingTime <= 0 || remainingTime > EARLY_SPAWN_THRESHOLD) {
                    return;
                }
                if (!spawner.getSpawnerActive() || spawner.getSpawnerStop().get()) {
                    // Do not acquire generatedOutputLock while dataLock is held. The commit path uses
                    // generatedOutputLock -> dataLock, so reset after releasing dataLock.
                    resetAfterUnlock = true;
                    return;
                }

                Location spawnerLocation = spawner.getSpawnerLocation();
                if (spawnerLocation == null) {
                    return;
                }
                final long scheduledSpawnTime = lastSpawnTime + cachedDelay;

                Scheduler.runLocationTask(spawnerLocation, () -> {
                    if (!spawner.getSpawnerActive() || spawner.getSpawnerStop().get()) {
                        spawner.resetGeneratedLootState();
                        return;
                    }

                    SpawnerData.PreGeneratedLootBatch batch = spawner.claimPreGeneratedLootBatch();
                    if (batch == null) {
                        return;
                    }
                    plugin.getSpawnerLootGenerator().addPreGeneratedLoot(
                            spawner,
                            batch.getItems(),
                            batch.getExperience(),
                            scheduledSpawnTime,
                            batch.getGenerationEpoch());
                });
            } finally {
                spawner.getDataLock().unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (resetAfterUnlock) {
                spawner.resetGeneratedLootState();
            }
        }
    }
}
