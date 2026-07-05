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
    private final SkylliaIslandCleanupService cleanupService;
    private final Set<UUID> pendingDeletions = ConcurrentHashMap.newKeySet();

    public SkylliaIslandCleanupListener(SmartSpawner plugin) {
        this.plugin = plugin;
        this.cleanupService = new SkylliaIslandCleanupService(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandDelete(SkyblockDeleteEvent event) {
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
        final int MAX_RETRIES = 10;
        final long RETRY_DELAY_TICKS = 20L;

        scheduleConfirmationTask(island, islandId, retries, MAX_RETRIES, RETRY_DELAY_TICKS);
    }

    private void scheduleConfirmationTask(Island island, UUID islandId, AtomicInteger retries, int maxRetries, long delayTicks) {
        Scheduler.runTaskLaterAsync(() -> {
            // If plugin disables, gracefully abort
            if (!plugin.isEnabled()) {
                pendingDeletions.remove(islandId);
                return;
            }

            if (island.isDisable()) {
                // Confirmation successful, proceed with cleanup
                plugin.getLogger().info("Island " + islandId + " disable confirmed. Beginning spawner cleanup...");
                try {
                    cleanupService.cleanupIsland(island);
                } finally {
                    pendingDeletions.remove(islandId);
                }
            } else {
                int attempt = retries.incrementAndGet();
                if (attempt < maxRetries) {
                    scheduleConfirmationTask(island, islandId, retries, maxRetries, delayTicks);
                } else {
                    plugin.getLogger().warning("Aborted cleanup for island " + islandId + ". Deletion was never confirmed after " + maxRetries + " attempts.");
                    pendingDeletions.remove(islandId);
                }
            }
        }, delayTicks);
    }
}
