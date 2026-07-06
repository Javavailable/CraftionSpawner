package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.config.Config;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.spawner.interactions.destroy.SpawnerRemovalService;
import github.nighter.smartspawner.spawner.lootgen.loot.LootItem;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.spawner.utils.SpawnerLocationLockManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpawnerLootGenerator {
    private final SmartSpawner plugin;
    private final SpawnerGuiViewManager spawnerGuiViewManager;
    private final SpawnerManager spawnerManager;
    private final SpawnerOutputRoutingService outputRoutingService;

    private static final int PREGEN_MAX_COMMIT_ATTEMPTS = 5;
    private static final long PREGEN_RETRY_DELAY_TICKS = 2L;
    private static final long PREGEN_HANDOFF_DRAIN_PERIOD_TICKS = 20L;
    private static final long PREGEN_WARN_INTERVAL_MS = 60_000L;

    private final ConcurrentHashMap<String, Long> lastPreGenWarnById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HandoffKey, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Set<Scheduler.Task> pendingRetryTasks = ConcurrentHashMap.newKeySet();
    private final Object pendingHandoffLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Scheduler.Task pendingHandoffDrainTask;

    private enum CommitStatus {
        COMMITTED,
        NOOP,
        LOCK_UNAVAILABLE,
        ABORTED_STALE
    }

    public SpawnerLootGenerator(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerGuiViewManager = plugin.getSpawnerGuiViewManager();
        this.spawnerManager = plugin.getSpawnerManager();
        this.outputRoutingService = plugin.getSpawnerOutputRoutingService();
        this.pendingHandoffDrainTask = Scheduler.runTaskTimer(
                this::drainPendingHandoffs,
                PREGEN_HANDOFF_DRAIN_PERIOD_TICKS,
                PREGEN_HANDOFF_DRAIN_PERIOD_TICKS);
    }

    public void spawnLootToSpawner(SpawnerData spawner) {
        if (spawner == null || shuttingDown.get() || spawner.isSelling()
                || !Boolean.TRUE.equals(spawner.getSpawnerActive()) || spawner.getSpawnerStop().get()) {
            return;
        }

        final long generationEpoch = spawner.getGeneratedOutputEpoch();
        if (!isLifecycleCurrent(spawner, generationEpoch)) {
            return;
        }

        if (!spawner.getLootGenerationLock().tryLock()) {
            return;
        }

        try {
            if (shuttingDown.get() || !isLifecycleCurrent(spawner, generationEpoch)) {
                return;
            }
            try {
                if (!spawner.getDataLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            final long spawnTime = System.currentTimeMillis();
            final int minMobs;
            final int maxMobs;
            try {
                if (!isLifecycleCurrent(spawner, generationEpoch)) {
                    return;
                }
                boolean routersActive = outputRoutingService != null && outputRoutingService.hasActiveRouters();
                int usedSlots = spawner.getVirtualInventory().getUsedSlots();
                int maxSlots = spawner.getMaxSpawnerLootSlots();
                if (!routersActive && usedSlots >= maxSlots
                        && spawner.getSpawnerExp() >= spawner.getMaxStoredExp()) {
                    if (!spawner.getIsAtCapacity()) {
                        spawner.setIsAtCapacity(true);
                    }
                    return;
                }
                minMobs = spawner.getMinMobs();
                maxMobs = spawner.getMaxMobs();
            } finally {
                spawner.getDataLock().unlock();
            }

            Scheduler.runTaskAsync(() -> {
                if (!isLifecycleCurrent(spawner, generationEpoch)) {
                    return;
                }
                LootResult loot = generateLoot(minMobs, maxMobs, spawner);
                if (!isLifecycleCurrent(spawner, generationEpoch)
                        || (loot.items().isEmpty() && loot.experience() == 0L)) {
                    return;
                }

                Location location = spawner.getSpawnerLocation();
                if (location == null) {
                    return;
                }
                Scheduler.runLocationTask(location, () -> {
                    if (!isLifecycleCurrent(spawner, generationEpoch)
                            || !spawner.getLootGenerationLock().tryLock()) {
                        return;
                    }
                    try {
                        commitGeneratedLoot(
                                spawner,
                                loot.items(),
                                loot.experience(),
                                spawnTime,
                                false,
                                generationEpoch);
                    } finally {
                        spawner.getLootGenerationLock().unlock();
                    }
                });
            });
        } finally {
            spawner.getLootGenerationLock().unlock();
        }
    }

    public LootResult generateLoot(int minMobs, int maxMobs, SpawnerData spawner) {
        int mobCount = ThreadLocalRandom.current().nextInt(maxMobs - minMobs + 1) + minMobs;
        long totalExperience = (long) spawner.getEntityExperienceValue() * mobCount;
        List<LootItem> validItems = spawner.getValidLootItems();
        if (validItems.isEmpty()) {
            return new LootResult(Collections.emptyList(), totalExperience);
        }

        Map<ItemStack, Integer> consolidatedLoot = new HashMap<>();
        for (LootItem lootItem : validItems) {
            int totalAmount = Config.get().isApproximateLoot() && shouldApproximate(lootItem.chance(), mobCount)
                    ? generateApproximatedLoot(lootItem, mobCount)
                    : generateExactLoot(lootItem, mobCount);
            if (totalAmount > 0) {
                ItemStack prototype = lootItem.createItemStack();
                if (prototype != null) {
                    consolidatedLoot.merge(prototype, totalAmount, Integer::sum);
                }
            }
        }

        List<ItemStack> finalLoot = new ArrayList<>(consolidatedLoot.size());
        for (Map.Entry<ItemStack, Integer> entry : consolidatedLoot.entrySet()) {
            ItemStack item = entry.getKey().clone();
            item.setAmount(Math.min(entry.getValue(), item.getMaxStackSize()));
            finalLoot.add(item);
            int remaining = entry.getValue() - item.getMaxStackSize();
            while (remaining > 0) {
                ItemStack extraStack = item.clone();
                extraStack.setAmount(Math.min(remaining, item.getMaxStackSize()));
                finalLoot.add(extraStack);
                remaining -= extraStack.getAmount();
            }
        }
        return new LootResult(finalLoot, totalExperience);
    }

    private boolean shouldApproximate(double chance, int mobCount) {
        if (chance <= 0D) return false;
        return mobCount > (97.5D / chance) * Config.get().getApproximationThreshold();
    }

    private int generateExactLoot(LootItem lootItem, int mobCount) {
        int successfulDrops = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double probability = lootItem.chance() / 100.0;
        for (int i = 0; i < mobCount; i++) {
            if (random.nextDouble() < probability) successfulDrops++;
        }
        int totalAmount = 0;
        for (int i = 0; i < successfulDrops; i++) {
            totalAmount += lootItem.generateAmount(random);
        }
        return totalAmount;
    }

    private int generateApproximatedLoot(LootItem lootItem, int mobCount) {
        double probability = lootItem.chance() / 100.0;
        double expectedDrops = mobCount * probability;
        double jitter = probability != 1.0
                ? 0.95 + ThreadLocalRandom.current().nextDouble() * 0.10
                : 1.0;
        return (int) Math.round(expectedDrops * lootItem.getAverageAmount() * jitter);
    }

    private List<ItemStack> limitItemsToAvailableSlots(List<ItemStack> items, SpawnerData spawner) {
        VirtualInventory currentInventory = spawner.getVirtualInventory();
        int maxSlots = spawner.getMaxSpawnerLootSlots();
        if (currentInventory.getUsedSlots() >= maxSlots) {
            return Collections.emptyList();
        }

        Map<ItemSignature, Long> simulatedInventory = new HashMap<>(currentInventory.getConsolidatedItems());
        List<ItemStack> acceptedItems = new ArrayList<>();
        items.sort(Comparator.comparing(item -> item.getType().name()));

        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            Map<ItemSignature, Long> tempSimulation = new HashMap<>(simulatedInventory);
            ItemSignature signature = VirtualInventory.getSignature(item);
            tempSimulation.merge(signature, (long) item.getAmount(), Long::sum);
            if (calculateSlots(tempSimulation) <= maxSlots) {
                acceptedItems.add(item);
                simulatedInventory = tempSimulation;
                continue;
            }

            int maxStackSize = item.getMaxStackSize();
            long currentAmount = simulatedInventory.getOrDefault(signature, 0L);
            int remainingSlots = maxSlots - calculateSlots(simulatedInventory);
            if (remainingSlots > 0) {
                long maxAddAmount = (long) remainingSlots * maxStackSize - (currentAmount % maxStackSize);
                if (maxAddAmount > 0) {
                    ItemStack partialItem = item.clone();
                    partialItem.setAmount((int) Math.min(maxAddAmount, item.getAmount()));
                    acceptedItems.add(partialItem);
                }
            }
            break;
        }
        return acceptedItems;
    }

    private int calculateSlots(Map<ItemSignature, Long> items) {
        return items.entrySet().stream()
                .mapToInt(entry -> {
                    long amount = entry.getValue();
                    int maxStackSize = entry.getKey().getMaxStackSize();
                    return (int) ((amount + maxStackSize - 1) / maxStackSize);
                })
                .sum();
    }

    private int calculateRequiredSlots(List<ItemStack> items, VirtualInventory inventory) {
        Map<ItemSignature, Long> simulatedItems = new HashMap<>();
        if (inventory != null) {
            simulatedItems.putAll(inventory.getConsolidatedItems());
        }
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            ItemSignature signature = VirtualInventory.getSignature(item);
            simulatedItems.merge(signature, (long) item.getAmount(), Long::sum);
        }
        return calculateSlots(simulatedItems);
    }

    private boolean isStaleOrRemoving(SpawnerData spawner) {
        if (spawner == null || spawner.isRemovalPending()) return true;
        SpawnerRemovalService removalService = plugin.getSpawnerRemovalService();
        if (removalService != null && removalService.isRemovalPending(spawner)) return true;
        if (spawnerManager.getSpawnerById(spawner.getSpawnerId()) != spawner) return true;
        Location location = spawner.getSpawnerLocation();
        return location == null || spawnerManager.getSpawnerByLocation(location) != spawner;
    }

    private boolean isCurrentExactInstance(SpawnerData spawner, String expectedId, Location expectedLoc) {
        if (spawner == null || expectedId == null || expectedLoc == null || spawner.isRemovalPending()) {
            return false;
        }
        SpawnerRemovalService removalService = plugin.getSpawnerRemovalService();
        if (removalService != null && removalService.isRemovalPending(spawner)) return false;
        return expectedId.equals(spawner.getSpawnerId())
                && spawner.getSpawnerLocation() == expectedLoc
                && spawnerManager.getSpawnerById(expectedId) == spawner
                && spawnerManager.getSpawnerByLocation(expectedLoc) == spawner;
    }

    private boolean isLifecycleCurrent(SpawnerData spawner, long expectedEpoch) {
        return !shuttingDown.get()
                && plugin.isEnabled()
                && spawner != null
                && spawner.isGeneratedOutputEpoch(expectedEpoch)
                && Boolean.TRUE.equals(spawner.getSpawnerActive())
                && !spawner.getSpawnerStop().get();
    }

    /**
     * Lock order: lootGenerationLock (caller) -> location -> generatedOutput -> data -> inventory.
     * generatedOutputLock remains held across router invocation and state commit so lifecycle reset
     * cannot clear state midway through an external routing pass.
     */
    private CommitStatus commitGeneratedLoot(SpawnerData spawner, List<ItemStack> generatedItems,
                                             long experience, long spawnTime,
                                             boolean ownsGeneratedBatchForRetry,
                                             long expectedEpoch) {
        if (shuttingDown.get() || !plugin.isEnabled()) {
            return CommitStatus.ABORTED_STALE;
        }
        Location location = spawner.getSpawnerLocation();
        if (location == null) {
            return CommitStatus.ABORTED_STALE;
        }

        SpawnerLocationLockManager lockManager = plugin.getSpawnerLocationLockManager();
        if (lockManager == null || !lockManager.tryLock(location)) {
            return CommitStatus.LOCK_UNAVAILABLE;
        }

        boolean outputHeld = false;
        boolean dataHeld = false;
        boolean inventoryHeld = false;
        SpawnerData.PendingCommitClaim pendingClaim = null;
        try {
            if (!spawner.getGeneratedOutputLock().tryLock()) {
                return CommitStatus.LOCK_UNAVAILABLE;
            }
            outputHeld = true;

            if (!spawner.isGeneratedOutputEpoch(expectedEpoch)) {
                return CommitStatus.ABORTED_STALE;
            }
            if (!Boolean.TRUE.equals(spawner.getSpawnerActive()) || spawner.getSpawnerStop().get()) {
                spawner.resetGeneratedLootState();
                return CommitStatus.ABORTED_STALE;
            }
            if (isStaleOrRemoving(spawner)) {
                spawner.resetGeneratedLootState();
                return CommitStatus.ABORTED_STALE;
            }

            if (!spawner.getDataLock().tryLock()) {
                return CommitStatus.LOCK_UNAVAILABLE;
            }
            dataHeld = true;

            // Claim first, then decide whether inventoryLock is required from the exact claim.
            // New batches queued after this claim wait for a later commit and cannot cross the lock decision.
            pendingClaim = spawner.claimPendingCommitBatches();
            boolean itemsPossible = generatedItems != null && !generatedItems.isEmpty();
            if (pendingClaim != null && pendingClaim.hasItemOutput()) {
                itemsPossible = true;
            }
            if (itemsPossible) {
                if (!spawner.getInventoryLock().tryLock()) {
                    spawner.releasePendingCommitClaim(pendingClaim);
                    pendingClaim = null;
                    return CommitStatus.LOCK_UNAVAILABLE;
                }
                inventoryHeld = true;
            }

            final SpawnerData.PendingCommitClaim claim = pendingClaim;
            final boolean[] sellValueRefreshNeeded = {false};
            LootCommitTransaction.Result result = LootCommitTransaction.execute(new LootCommitTransaction.Context() {
                @Override
                public LootCommitTransaction.PendingInput pendingInput() {
                    if (claim == null) return LootCommitTransaction.PendingInput.none();
                    return new LootCommitTransaction.PendingInput(
                            true,
                            claim.getUnroutedItems(),
                            claim.getRouteCompletedItems(),
                            claim.getExperience());
                }

                @Override
                public List<ItemStack> generatedItems() {
                    return generatedItems;
                }

                @Override
                public long generatedExperience() {
                    return experience;
                }

                @Override
                public boolean ownsGeneratedBatchForRetry() {
                    return ownsGeneratedBatchForRetry;
                }

                @Override
                public long spawnTime() {
                    return spawnTime;
                }

                @Override
                public boolean hasActiveRouters() {
                    return outputRoutingService != null && outputRoutingService.hasActiveRouters();
                }

                @Override
                public SpawnerOutputRoutingService.RoutingOutcome route(List<ItemStack> items) {
                    return outputRoutingService.route(spawner, items);
                }

                @Override
                public long currentExperience() {
                    return spawner.getSpawnerExp();
                }

                @Override
                public long maxStoredExperience() {
                    return spawner.getMaxStoredExp();
                }

                @Override
                public void commitExperience(long value, Runnable pointOfNoReturn) {
                    spawner.setSpawnerExpData(value);
                    pointOfNoReturn.run();
                }

                @Override
                public int usedSlots() {
                    return spawner.getVirtualInventory().getUsedSlots();
                }

                @Override
                public int maxSlots() {
                    return spawner.getMaxSpawnerLootSlots();
                }

                @Override
                public int requiredSlots(List<ItemStack> items) {
                    return calculateRequiredSlots(items, spawner.getVirtualInventory());
                }

                @Override
                public List<ItemStack> limitToAvailableSlots(List<ItemStack> items) {
                    return limitItemsToAvailableSlots(new ArrayList<>(items), spawner);
                }

                @Override
                public void insertItems(List<ItemStack> items, Runnable pointOfNoReturn) {
                    spawner.addItemsToVirtualInventoryWhileLocked(items);
                    sellValueRefreshNeeded[0] = true;
                    pointOfNoReturn.run();
                }

                @Override
                public void setLastSpawnTime(long value) {
                    spawner.setLastSpawnTime(value);
                }

                @Override
                public void acknowledgePending() {
                    spawner.ackPendingCommitClaim(claim);
                }

                @Override
                public void releasePending() {
                    spawner.releasePendingCommitClaim(claim);
                }

                @Override
                public void replacePendingWithUnrouted(List<ItemStack> items, long xp) {
                    spawner.replacePendingCommitClaim(claim, items, xp, false);
                }

                @Override
                public void replacePendingWithRouteCompleted(List<ItemStack> items, long xp) {
                    spawner.replacePendingCommitClaim(claim, items, xp, true);
                }

                @Override
                public void queueUnrouted(List<ItemStack> items, long xp) {
                    spawner.queuePendingCommitBatch(items, xp);
                }

                @Override
                public void queueRouteCompleted(List<ItemStack> items, long xp) {
                    spawner.queueRouteCompletedPendingCommitBatch(items, xp);
                }

                @Override
                public void afterCommitted() {
                    if (sellValueRefreshNeeded[0]) {
                        runPostCommitStep(spawner, "sell-value refresh", spawner::recalculateSellValue);
                    }
                    runPostCommitStep(spawner, "capacity refresh", spawner::updateCapacityStatus);
                    runPostCommitStep(spawner, "XP cache invalidation", () -> invalidateSpawnerCaches(spawner));
                    runPostCommitStep(spawner, "GUI/hologram update", () -> handleGuiUpdates(spawner));
                    runPostCommitStep(spawner, "persistence dirty marking",
                            () -> spawnerManager.markSpawnerModified(spawner.getSpawnerId()));
                }

                @Override
                public void handlePostCommitFailure(Throwable throwable) {
                    plugin.getLogger().warning("Post-commit output update failed for spawner "
                            + spawner.getSpawnerId() + ": " + throwable.getMessage());
                }
            });
            return result == LootCommitTransaction.Result.NOOP ? CommitStatus.NOOP : CommitStatus.COMMITTED;
        } finally {
            // Idempotent exact-ID release is a final safety net for setup or recovery failures.
            if (pendingClaim != null) {
                spawner.releasePendingCommitClaim(pendingClaim);
            }
            if (inventoryHeld) spawner.getInventoryLock().unlock();
            if (dataHeld) spawner.getDataLock().unlock();
            if (outputHeld) spawner.getGeneratedOutputLock().unlock();
            lockManager.unlock(location);
        }
    }

    private static long saturatedAdd(long a, long b) {
        long result = a + b;
        return ((a ^ result) & (b ^ result)) < 0 ? Long.MAX_VALUE : result;
    }

    private void handleGuiUpdates(SpawnerData spawner) {
        spawnerGuiViewManager.updateSpawnerMenuViewers(spawner);
        if (Config.get().isSpawnerGenerateLootParticlesEnabled()) {
            Location location = spawner.getSpawnerLocation();
            World world = location != null ? location.getWorld() : null;
            if (world != null) {
                Scheduler.runLocationTask(location, () -> world.spawnParticle(
                        Particle.HAPPY_VILLAGER,
                        location.clone().add(0.5, 0.5, 0.5),
                        10, 0.3, 0.3, 0.3, 0));
            }
        }
        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            spawner.updateHologramData();
        }
    }

    private void runPostCommitStep(SpawnerData spawner, String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Post-commit " + step + " failed for spawner "
                    + spawner.getSpawnerId() + ": " + throwable.getMessage());
        }
    }

    private void invalidateSpawnerCaches(SpawnerData spawner) {
        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(spawner.getSpawnerId());
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(spawner.getSpawnerId());
        }
    }

    public void preGenerateLoot(SpawnerData spawner, LootGenerationCallback callback) {
        if (spawner == null || callback == null || shuttingDown.get()) {
            if (callback != null) callback.onLootGenerated(Collections.emptyList(), 0L);
            return;
        }
        final long generationEpoch = spawner.getGeneratedOutputEpoch();
        if (!isLifecycleCurrent(spawner, generationEpoch)
                || !spawner.getLootGenerationLock().tryLock()) {
            callback.onLootGenerated(Collections.emptyList(), 0L);
            return;
        }

        try {
            try {
                if (!spawner.getDataLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    callback.onLootGenerated(Collections.emptyList(), 0L);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onLootGenerated(Collections.emptyList(), 0L);
                return;
            }

            final int minMobs;
            final int maxMobs;
            final boolean itemStorageFull;
            final boolean routersActive = outputRoutingService != null && outputRoutingService.hasActiveRouters();
            try {
                if (!isLifecycleCurrent(spawner, generationEpoch)) {
                    callback.onLootGenerated(Collections.emptyList(), 0L);
                    return;
                }
                int usedSlots = spawner.getVirtualInventory().getUsedSlots();
                int maxSlots = spawner.getMaxSpawnerLootSlots();
                itemStorageFull = usedSlots >= maxSlots;
                boolean atCapacity = itemStorageFull && spawner.getSpawnerExp() >= spawner.getMaxStoredExp();
                if (atCapacity && !routersActive) {
                    callback.onLootGenerated(Collections.emptyList(), 0L);
                    return;
                }
                minMobs = spawner.getMinMobs();
                maxMobs = spawner.getMaxMobs();
            } finally {
                spawner.getDataLock().unlock();
            }

            Scheduler.runTaskAsync(() -> {
                if (!isLifecycleCurrent(spawner, generationEpoch)) {
                    callback.onLootGenerated(Collections.emptyList(), 0L);
                    return;
                }
                LootResult loot = itemStorageFull && !routersActive
                        ? generateExperienceOnlyLoot(minMobs, maxMobs, spawner)
                        : generateLoot(minMobs, maxMobs, spawner);
                if (!isLifecycleCurrent(spawner, generationEpoch)) {
                    callback.onLootGenerated(Collections.emptyList(), 0L);
                    return;
                }
                callback.onLootGenerated(
                        loot.items() != null ? new ArrayList<>(loot.items()) : Collections.emptyList(),
                        loot.experience());
            });
        } finally {
            spawner.getLootGenerationLock().unlock();
        }
    }

    private LootResult generateExperienceOnlyLoot(int minMobs, int maxMobs, SpawnerData spawner) {
        int mobCount = ThreadLocalRandom.current().nextInt(maxMobs - minMobs + 1) + minMobs;
        long totalExperience = (long) spawner.getEntityExperienceValue() * mobCount;
        return new LootResult(Collections.emptyList(), totalExperience);
    }

    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience) {
        if (spawner == null) return;
        addPreGeneratedLoot(
                spawner, items, experience, System.currentTimeMillis(), spawner.getGeneratedOutputEpoch());
    }

    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items,
                                    long experience, long spawnTime) {
        if (spawner == null) return;
        addPreGeneratedLoot(spawner, items, experience, spawnTime, spawner.getGeneratedOutputEpoch());
    }

    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience,
                                    long spawnTime, long generationEpoch) {
        if (!isLifecycleCurrent(spawner, generationEpoch)
                || ((items == null || items.isEmpty()) && experience == 0L)) {
            return;
        }
        Location location = spawner.getSpawnerLocation();
        if (location == null) return;

        List<ItemStack> ownedItems = deepCloneItems(items);
        schedulePreGeneratedCommit(spawner, ownedItems, experience, spawnTime, generationEpoch, 0);
    }

    private void schedulePreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems,
                                             long experience, long spawnTime,
                                             long generationEpoch, int attempt) {
        if (!isLifecycleCurrent(spawner, generationEpoch)) return;
        Location location = spawner.getSpawnerLocation();
        if (location == null) return;

        Scheduler.runLocationTask(location, () -> {
            if (!isLifecycleCurrent(spawner, generationEpoch)) return;
            if (!spawner.getLootGenerationLock().tryLock()) {
                retryPreGeneratedCommit(
                        spawner, ownedItems, experience, spawnTime, generationEpoch, attempt);
                return;
            }
            CommitStatus status;
            try {
                status = commitGeneratedLoot(
                        spawner, ownedItems, experience, spawnTime, true, generationEpoch);
            } finally {
                spawner.getLootGenerationLock().unlock();
            }
            if (status == CommitStatus.LOCK_UNAVAILABLE) {
                retryPreGeneratedCommit(
                        spawner, ownedItems, experience, spawnTime, generationEpoch, attempt);
            }
        });
    }

    private void retryPreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems,
                                          long experience, long spawnTime,
                                          long generationEpoch, int attempt) {
        if (!isLifecycleCurrent(spawner, generationEpoch)) return;
        if (attempt + 1 >= PREGEN_MAX_COMMIT_ATTEMPTS) {
            queuePendingHandoff(spawner, ownedItems, experience, generationEpoch);
            return;
        }

        final Scheduler.Task[] taskRef = new Scheduler.Task[1];
        Scheduler.Task task = Scheduler.runTaskLaterAsync(() -> {
            Scheduler.Task tracked = taskRef[0];
            if (tracked != null) pendingRetryTasks.remove(tracked);
            if (!isLifecycleCurrent(spawner, generationEpoch)) return;
            schedulePreGeneratedCommit(
                    spawner, ownedItems, experience, spawnTime, generationEpoch, attempt + 1);
        }, PREGEN_RETRY_DELAY_TICKS);
        taskRef[0] = task;
        if (shuttingDown.get()) {
            task.cancel();
        } else {
            pendingRetryTasks.add(task);
            if (shuttingDown.get() && pendingRetryTasks.remove(task)) task.cancel();
        }
    }

    private void warnPreGenRequeue(SpawnerData spawner) {
        String id = spawner.getSpawnerId();
        long now = System.currentTimeMillis();
        Long last = lastPreGenWarnById.get(id);
        if (last != null && now - last < PREGEN_WARN_INTERVAL_MS) return;
        lastPreGenWarnById.put(id, now);
        plugin.getLogger().warning("Pre-generated loot for spawner " + id
                + " could not commit after " + PREGEN_MAX_COMMIT_ATTEMPTS
                + " attempts; batch queued for the pending handoff drain.");
    }

    private void queuePendingHandoff(SpawnerData spawner, List<ItemStack> ownedItems,
                                     long experience, long generationEpoch) {
        if (!isLifecycleCurrent(spawner, generationEpoch)
                || ((ownedItems == null || ownedItems.isEmpty()) && experience <= 0L)) {
            return;
        }
        Location location = spawner.getSpawnerLocation();
        if (location == null || location.getWorld() == null) return;

        HandoffKey key = new HandoffKey(spawner, generationEpoch);
        PendingHandoff handoff = new PendingHandoff(
                spawner, spawner.getSpawnerId(), location, ownedItems, experience, generationEpoch);
        synchronized (pendingHandoffLock) {
            if (!isLifecycleCurrent(spawner, generationEpoch)) return;
            pendingHandoffs.merge(key, handoff, PendingHandoff::merge);
        }
        warnPreGenRequeue(spawner);
    }

    private void drainPendingHandoffs() {
        if (shuttingDown.get() || pendingHandoffs.isEmpty()) return;
        SpawnerLocationLockManager lockManager = plugin.getSpawnerLocationLockManager();
        if (lockManager == null) return;

        for (Map.Entry<HandoffKey, PendingHandoff> entry : pendingHandoffs.entrySet()) {
            if (shuttingDown.get()) return;
            PendingHandoff handoff = entry.getValue();
            Location location = handoff.location;
            if (location == null || location.getWorld() == null) {
                removeHandoff(entry.getKey(), handoff);
                continue;
            }
            if (!lockManager.tryLock(location)) continue;

            boolean outputHeld = false;
            try {
                if (shuttingDown.get()) return;
                if (!handoff.spawner.getGeneratedOutputLock().tryLock()) continue;
                outputHeld = true;

                if (!isLifecycleCurrent(handoff.spawner, handoff.generationEpoch)
                        || !isCurrentExactInstance(handoff.spawner, handoff.spawnerId, location)) {
                    removeHandoff(entry.getKey(), handoff);
                    continue;
                }

                synchronized (pendingHandoffLock) {
                    if (isLifecycleCurrent(handoff.spawner, handoff.generationEpoch)
                            && pendingHandoffs.remove(entry.getKey(), handoff)) {
                        handoff.spawner.queuePendingCommitBatch(handoff.items, handoff.experience);
                    }
                }
            } finally {
                if (outputHeld) handoff.spawner.getGeneratedOutputLock().unlock();
                lockManager.unlock(location);
            }
        }
    }

    private void removeHandoff(HandoffKey key, PendingHandoff handoff) {
        synchronized (pendingHandoffLock) {
            pendingHandoffs.remove(key, handoff);
        }
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        if (pendingHandoffDrainTask != null) pendingHandoffDrainTask.cancel();
        for (Scheduler.Task task : pendingRetryTasks) task.cancel();
        pendingRetryTasks.clear();
        synchronized (pendingHandoffLock) {
            int unresolved = pendingHandoffs.size();
            if (unresolved > 0) {
                plugin.getLogger().warning("Discarding " + unresolved
                        + " unresolved pre-generated pending handoff batch(es) during shutdown.");
                pendingHandoffs.clear();
            }
        }
        lastPreGenWarnById.clear();
    }

    private static List<ItemStack> deepCloneItems(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items == null ? 0 : items.size());
        if (items != null) {
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                    copy.add(item.clone());
                }
            }
        }
        return copy;
    }

    private static final class HandoffKey {
        private final SpawnerData spawner;
        private final long generationEpoch;
        private final int identityHash;

        private HandoffKey(SpawnerData spawner, long generationEpoch) {
            this.spawner = spawner;
            this.generationEpoch = generationEpoch;
            this.identityHash = 31 * System.identityHashCode(spawner) + Long.hashCode(generationEpoch);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof HandoffKey other
                    && spawner == other.spawner
                    && generationEpoch == other.generationEpoch;
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }

    private static final class PendingHandoff {
        private final SpawnerData spawner;
        private final String spawnerId;
        private final Location location;
        private final List<ItemStack> items;
        private final long experience;
        private final long generationEpoch;

        private PendingHandoff(SpawnerData spawner, String spawnerId, Location location,
                               List<ItemStack> items, long experience, long generationEpoch) {
            this.spawner = spawner;
            this.spawnerId = spawnerId;
            this.location = location;
            this.items = Collections.unmodifiableList(deepCloneItems(items));
            this.experience = Math.max(0L, experience);
            this.generationEpoch = generationEpoch;
        }

        private PendingHandoff merge(PendingHandoff other) {
            if (spawner != other.spawner || generationEpoch != other.generationEpoch) {
                throw new IllegalArgumentException("Cannot merge handoffs from different output lifecycles");
            }
            List<ItemStack> mergedItems = new ArrayList<>(items.size() + other.items.size());
            mergedItems.addAll(deepCloneItems(items));
            mergedItems.addAll(deepCloneItems(other.items));
            return new PendingHandoff(
                    spawner,
                    spawnerId,
                    location,
                    mergedItems,
                    saturatedAdd(experience, other.experience),
                    generationEpoch);
        }
    }

    @FunctionalInterface
    public interface LootGenerationCallback {
        void onLootGenerated(List<ItemStack> items, long experience);
    }
}
