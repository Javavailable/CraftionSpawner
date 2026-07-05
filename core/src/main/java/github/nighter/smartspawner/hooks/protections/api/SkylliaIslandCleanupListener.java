package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SkylliaIslandCleanupListener implements Listener {

    private final SmartSpawner plugin;
    private final SkylliaHook hook;
    private final SkylliaIslandCleanupService cleanupService;
    private final Set<UUID> pendingDeletions = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown = false;

    public SkylliaIslandCleanupListener(SmartSpawner plugin, SkylliaHook hook) {
        this.plugin = plugin;
        this.hook = hook;
        this.cleanupService = new SkylliaIslandCleanupService(plugin, hook);
    }

    public void shutdown() {
        shuttingDown = true;
        // Cancel any deferred spawner retries still scheduled inside the cleanup service.
        cleanupService.shutdown();
        pendingDeletions.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandDelete(SkyblockDeleteEvent event) {
        if (shuttingDown) {
            return;
        }

        Island island = event.getIsland();
        if (island == null) {
            return;
        }

        UUID islandId = island.getId();
        if (!pendingDeletions.add(islandId)) {
            // Already tracking this deletion job
            return;
        }

        // SkyblockDeleteEvent is pre-delete and asynchronous. We must wait until Skyllia
        // successfully marks it disabled in its database and memory.
        plugin.getLogger().info("Received SkyblockDeleteEvent for island " + islandId + ". Scheduling disable confirmation...");

        AtomicInteger retries = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        final int MAX_RETRIES = 10;
        final long RETRY_DELAY_TICKS = 20L;

        scheduleConfirmationTask(island, islandId, retries, exceptionCount, MAX_RETRIES, RETRY_DELAY_TICKS);
    }

    private void scheduleConfirmationTask(Island island, UUID islandId, AtomicInteger retries, AtomicInteger exceptionCount, int maxRetries, long delayTicks) {
        Scheduler.runTaskLaterAsync(() -> {
            // If plugin disables or shutting down, gracefully abort
            if (shuttingDown || !plugin.isEnabled() || !hook.isEnabled() || hook.getSkylliaPlugin() == null || !hook.getSkylliaPlugin().isEnabled()) {
                pendingDeletions.remove(islandId);
                return;
            }

            boolean isDisable = false;
            try {
                isDisable = island.isDisable();
            } catch (Exception e) {
                if (exceptionCount.getAndIncrement() == 0) {
                    plugin.getLogger().warning("Exception while checking Skyllia island disable state for " + islandId + " (will suppress further logs): " + e.getMessage());
                }
            }

            if (isDisable) {
                // Confirmation successful, proceed with cleanup.
                plugin.getLogger().info("Island " + islandId + " disable confirmed. Beginning spawner cleanup...");
                // Keep the island-level dedup entry until the ENTIRE cleanup (including every
                // deferred retry) has completed, been exhausted, or aborted. This prevents a
                // duplicate delete event from starting a second overlapping cleanup while the
                // first retry chain is still running.
                try {
                    cleanupService.cleanupIsland(island)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    plugin.getLogger().warning("Skyllia island cleanup failed for " + islandId + ": " + error.getMessage());
                                }
                                pendingDeletions.remove(islandId);
                            });
                } catch (Exception e) {
                    plugin.getLogger().warning("Skyllia island cleanup could not start for " + islandId + ": " + e.getMessage());
                    pendingDeletions.remove(islandId);
                }
            } else {
                int attempt = retries.incrementAndGet();
                if (attempt < maxRetries) {
                    scheduleConfirmationTask(island, islandId, retries, exceptionCount, maxRetries, delayTicks);
                } else {
                    plugin.getLogger().warning("Aborted cleanup for island " + islandId + ". Deletion was never confirmed after " + maxRetries + " attempts.");
                    pendingDeletions.remove(islandId);
                }
            }
        }, delayTicks);
    }
}
