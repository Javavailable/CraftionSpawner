package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.config.Config;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.spawner.interactions.destroy.SpawnerRemovalService;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.spawner.utils.SpawnerLocationLockManager;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.spawner.lootgen.loot.LootItem;

import org.bukkit.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class SpawnerLootGenerator {
    private final SmartSpawner plugin;
    private final SpawnerGuiViewManager spawnerGuiViewManager;
    private final SpawnerManager spawnerManager;
    private final SpawnerOutputRoutingService outputRoutingService;

    // Bounded, non-blocking retry for the pre-generated commit when a transient lock is unavailable.
    private static final int PREGEN_MAX_COMMIT_ATTEMPTS = 5;
    private static final long PREGEN_RETRY_DELAY_TICKS = 2L;

    /** Outcome of a single {@link #commitGeneratedLoot} attempt. */
    private enum CommitStatus {
        /** Cycle committed successfully (timing advanced, side effects applied). */
        COMMITTED,
        /** Locks acquired but nothing needed committing; do not retry. */
        NOOP,
        /** A required non-blocking lock/claim was unavailable; safe to retry the same batch. */
        LOCK_UNAVAILABLE,
        /** Spawner is being removed or is a stale instance; must not route/mutate, do not retry. */
        ABORTED_STALE
    }

    public SpawnerLootGenerator(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerGuiViewManager = plugin.getSpawnerGuiViewManager();
        this.spawnerManager = plugin.getSpawnerManager();
        this.outputRoutingService = plugin.getSpawnerOutputRoutingService();
    }

    public void spawnLootToSpawner(SpawnerData spawner) {
        // Skip loot generation while a sell is in progress to avoid inventory conflicts
        if (spawner.isSelling()) {
            return;
        }

        boolean lockAcquired = spawner.getLootGenerationLock().tryLock();
        if (!lockAcquired) {
            return;
        }

        try {
            try {
                if (!spawner.getDataLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            final long currentTime = System.currentTimeMillis();
            final long spawnTime;
            final int minMobs;
            final int maxMobs;

            try {
                boolean routersActive = outputRoutingService != null && outputRoutingService.hasActiveRouters();

                int usedSlots = spawner.getVirtualInventory().getUsedSlots();
                int maxSlots = spawner.getMaxSpawnerLootSlots();

                // Skip only when both inventory and exp are full AND no router could consume items.
                if (!routersActive && usedSlots >= maxSlots && spawner.getSpawnerExp() >= spawner.getMaxStoredExp()) {
                    if (!spawner.getIsAtCapacity()) {
                        spawner.setIsAtCapacity(true);
                    }
                    return;
                }

                minMobs = spawner.getMinMobs();
                maxMobs = spawner.getMaxMobs();
                spawnTime = currentTime;
            } finally {
                spawner.getDataLock().unlock();
            }

            // Heavy random-loot calculation runs async; routers are NEVER invoked here.
            Scheduler.runTaskAsync(() -> {
                LootResult loot = generateLoot(minMobs, maxMobs, spawner);

                if (loot.items().isEmpty() && loot.experience() == 0) {
                    return;
                }

                // Commit phase on the valid location execution context.
                Scheduler.runLocationTask(spawner.getSpawnerLocation(), () -> {
                    boolean updateLockAcquired = spawner.getLootGenerationLock().tryLock();
                    if (!updateLockAcquired) {
                        return;
                    }
                    try {
                        // Normal path regenerates a fresh batch next cycle, so a transient lock failure
                        // simply skips this cycle (no double-routing risk); status intentionally ignored.
                        commitGeneratedLoot(spawner, loot.items(), loot.experience(), spawnTime);
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

        List<LootItem> validItems =  spawner.getValidLootItems();

        if (validItems.isEmpty()) {
            return new LootResult(Collections.emptyList(), totalExperience);
        }

        Map<ItemStack, Integer> consolidatedLoot = new HashMap<>();

        for (LootItem lootItem : validItems) {
            int totalAmount;

            if (Config.get().isApproximateLoot() && shouldApproximate(lootItem.chance(), mobCount)) {
                totalAmount = generateApproximatedLoot(lootItem, mobCount);
            } else {
                totalAmount = generateExactLoot(lootItem, mobCount);
            }

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
        double p = lootItem.chance() / 100.0;
        for (int i = 0; i < mobCount; i++) {
            if (random.nextDouble() < p) {
                successfulDrops++;
            }
        }
        int totalAmount = 0;
        for (int i = 0; i < successfulDrops; i++) {
            totalAmount += lootItem.generateAmount(random);
        }
        return totalAmount;
    }

    private int generateApproximatedLoot(LootItem lootItem, int mobCount) {
        double p = lootItem.chance() / 100.0;
        double expectedDrops = mobCount * p;
        double avgAmount = lootItem.getAverageAmount();
        double jitter = p != 1.0
                ? 0.95 + ThreadLocalRandom.current().nextDouble() * 0.10
                : 1.0;
        return (int) Math.round(expectedDrops * avgAmount * jitter);
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
            ItemSignature sig = VirtualInventory.getSignature(item);
            tempSimulation.merge(sig, (long) item.getAmount(), (a, b) -> a + b);

            int slotsNeeded = calculateSlots(tempSimulation);

            if (slotsNeeded <= maxSlots) {
                acceptedItems.add(item);
                simulatedInventory = tempSimulation;
            } else {
                int maxStackSize = item.getMaxStackSize();
                long currentAmount = simulatedInventory.getOrDefault(sig, 0L);

                int remainingSlots = maxSlots - calculateSlots(simulatedInventory);
                if (remainingSlots > 0) {
                    long maxAddAmount = (long) remainingSlots * maxStackSize - (currentAmount % maxStackSize);
                    if (maxAddAmount > 0) {
                        ItemStack partialItem = item.clone();
                        partialItem.setAmount((int) Math.min(maxAddAmount, item.getAmount()));
                        acceptedItems.add(partialItem);

                        simulatedInventory.merge(sig, (long) partialItem.getAmount(), (a, b) -> a + b);
                    }
                }

                break;
            }
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

            ItemSignature sig = VirtualInventory.getSignature(item);
            simulatedItems.merge(sig, (long) item.getAmount(), (a, b) -> a + b);
        }

        return calculateSlots(simulatedItems);
    }

    /**
     * Removal / stale-instance guard shared by both loot-commit paths. Checks BOTH removal systems
     * (S2B {@link SpawnerData#isRemovalPending()} and the ordinary/API/physical
     * {@link SpawnerRemovalService#isRemovalPending(SpawnerData)}) plus exact-instance identity by ID
     * and location. Callers must hold the shared location lock so this check-then-act cannot race with
     * a concurrent detach.
     *
     * @return true when the batch must NOT be routed or committed
     */
    private boolean isStaleOrRemoving(SpawnerData spawner) {
        if (spawner == null || spawner.isRemovalPending()) {
            return true;
        }
        SpawnerRemovalService removalService = plugin.getSpawnerRemovalService();
        if (removalService != null && removalService.isRemovalPending(spawner)) {
            return true;
        }
        if (spawnerManager.getSpawnerById(spawner.getSpawnerId()) != spawner) {
            return true;
        }
        Location loc = spawner.getSpawnerLocation();
        if (loc == null) {
            return true;
        }
        return spawnerManager.getSpawnerByLocation(loc) != spawner;
    }

    /**
     * Single, race-safe commit used by BOTH loot paths.
     *
     * <p>Must be called on the spawner's location execution context while holding the loot generation
     * lock. Acquisition order: lootGenerationLock (held by caller) -> shared location lock -> dataLock
     * -> inventoryLock (inside addItemsAndUpdateSellValue). All acquisitions here are non-blocking
     * tryLock; if any is unavailable, no router is invoked and no state is mutated.
     *
     * <p>The data lock is acquired BEFORE any router runs so cycle timing can always be committed
     * exactly once. The shared location lock serializes this whole commit against Skyllia island
     * cleanup and ordinary/API/physical removal, so the spawner cannot be detached mid-commit.
     */
    private CommitStatus commitGeneratedLoot(SpawnerData spawner, List<ItemStack> generatedItems, long experience, long spawnTime) {
        Location loc = spawner.getSpawnerLocation();
        if (loc == null) {
            return CommitStatus.ABORTED_STALE;
        }

        SpawnerLocationLockManager lockManager = plugin.getSpawnerLocationLockManager();
        // Non-blocking claim that serializes against Skyllia cleanup and normal removal.
        if (lockManager == null || !lockManager.tryLock(loc)) {
            return CommitStatus.LOCK_UNAVAILABLE;
        }
        try {
            // Removal / stale-instance safety under the shared lock (S2B + normal removal + exact instance).
            if (isStaleOrRemoving(spawner)) {
                return CommitStatus.ABORTED_STALE;
            }

            // Timing lock must be held before any router runs, so lastSpawnTime always commits once.
            if (!spawner.getDataLock().tryLock()) {
                return CommitStatus.LOCK_UNAVAILABLE;
            }
            try {
                boolean routersActive = outputRoutingService != null && outputRoutingService.hasActiveRouters();
                boolean generatedItemsPresent = generatedItems != null && !generatedItems.isEmpty();

                // Experience is never routed; it always uses the existing stored-XP path.
                boolean xpChanged = false;
                if (experience > 0 && spawner.getSpawnerExp() < spawner.getMaxStoredExp()) {
                    long currentExp = spawner.getSpawnerExp();
                    long maxExp = spawner.getMaxStoredExp();
                    long newExp = Math.min((long) currentExp + experience, maxExp);
                    if (newExp != currentExp) {
                        spawner.setSpawnerExp(newExp);
                        xpChanged = true;
                    }
                }

                boolean externallyConsumed = false;
                boolean internallyInserted = false;
                if (generatedItemsPresent) {
                    List<ItemStack> remainder = generatedItems;
                    if (routersActive) {
                        SpawnerOutputRoutingService.RoutingOutcome outcome =
                                outputRoutingService.route(spawner, generatedItems);
                        remainder = outcome.remaining();
                        externallyConsumed = outcome.consumedAny();
                    }
                    // Existing internal slot-capacity limiting applies only to the unconsumed remainder.
                    if (remainder != null && !remainder.isEmpty()) {
                        int usedSlots = spawner.getVirtualInventory().getUsedSlots();
                        int maxSlots = spawner.getMaxSpawnerLootSlots();
                        if (usedSlots < maxSlots) {
                            List<ItemStack> itemsToAdd = new ArrayList<>(remainder);
                            int totalRequiredSlots = calculateRequiredSlots(itemsToAdd, spawner.getVirtualInventory());
                            if (totalRequiredSlots > maxSlots) {
                                itemsToAdd = limitItemsToAvailableSlots(itemsToAdd, spawner);
                            }
                            if (!itemsToAdd.isEmpty()) {
                                spawner.addItemsAndUpdateSellValue(itemsToAdd);
                                internallyInserted = true;
                            }
                        }
                    }
                }

                // Advance the cycle timer once when anything changed OR a non-empty batch was legitimately
                // presented to an active router snapshot. This preserves the configured spawn interval and
                // prevents a full-storage / pass-through router callback storm, without ever claiming false
                // external consumption.
                boolean itemBatchPresentedToRouters = routersActive && generatedItemsPresent;
                boolean advanceCycle = xpChanged || internallyInserted || externallyConsumed || itemBatchPresentedToRouters;

                if (!advanceCycle) {
                    return CommitStatus.NOOP;
                }

                spawner.setLastSpawnTime(spawnTime);
            } finally {
                spawner.getDataLock().unlock();
            }

            spawner.updateCapacityStatus();
            handleGuiUpdates(spawner);
            spawnerManager.markSpawnerModified(spawner.getSpawnerId());
            return CommitStatus.COMMITTED;
        } finally {
            // Never call removeLock() from the generation path; the periodic cleanup task reclaims
            // unused locks so a second lock object can never be created for a referenced location.
            lockManager.unlock(loc);
        }
    }

    /**
     * Handle GUI updates after loot has been committed. Called while lootGenerationLock is held so
     * VirtualInventory stays the single source of truth during viewer dispatch.
     */
    private void handleGuiUpdates(SpawnerData spawner) {
        spawnerGuiViewManager.updateSpawnerMenuViewers(spawner);

        if (Config.get().isSpawnerGenerateLootParticlesEnabled()) {
            Location loc = spawner.getSpawnerLocation();
            World world = loc.getWorld();
            if (world != null) {
                Scheduler.runLocationTask(loc, () -> world.spawnParticle(Particle.HAPPY_VILLAGER,
                        loc.clone().add(0.5, 0.5, 0.5),
                        10, 0.3, 0.3, 0.3, 0));
            }
        }

        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            spawner.updateHologramData();
        }
    }

    /**
     * Pre-generates loot asynchronously for improved UX.
     *
     * @param spawner The spawner to pre-generate loot for
     * @param callback Callback invoked with generated loot (items, experience)
     */
    public void preGenerateLoot(SpawnerData spawner, LootGenerationCallback callback) {
        if (!spawner.getLootGenerationLock().tryLock()) {
            callback.onLootGenerated(Collections.emptyList(), 0);
            return;
        }

        try {
            try {
                if (!spawner.getDataLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    callback.onLootGenerated(Collections.emptyList(), 0);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onLootGenerated(Collections.emptyList(), 0);
                return;
            }

            final int minMobs;
            final int maxMobs;
            final boolean itemStorageFull;
            final boolean routersActive = outputRoutingService != null && outputRoutingService.hasActiveRouters();

            try {
                int usedSlots = spawner.getVirtualInventory().getUsedSlots();
                int maxSlots = spawner.getMaxSpawnerLootSlots();
                itemStorageFull = usedSlots >= maxSlots;
                boolean atCapacity = itemStorageFull && spawner.getSpawnerExp() >= spawner.getMaxStoredExp();

                // Only skip entirely when both storages are full AND no router could consume items.
                if (atCapacity && !routersActive) {
                    callback.onLootGenerated(Collections.emptyList(), 0);
                    return;
                }

                minMobs = spawner.getMinMobs();
                maxMobs = spawner.getMaxMobs();
            } finally {
                spawner.getDataLock().unlock();
            }

            Scheduler.runTaskAsync(() -> {
                LootResult loot;
                // The experience-only shortcut remains active only when no router could consume items.
                if (itemStorageFull && !routersActive) {
                    loot = generateExperienceOnlyLoot(minMobs, maxMobs, spawner);
                } else {
                    loot = generateLoot(minMobs, maxMobs, spawner);
                }

                callback.onLootGenerated(
                        loot.items() != null ? new ArrayList<>(loot.items()) : Collections.emptyList(),
                        loot.experience()
                );
            });
        } finally {
            spawner.getLootGenerationLock().unlock();
        }
    }

    private LootResult generateExperienceOnlyLoot(int minMobs, int maxMobs, SpawnerData spawner) {
        int mobCount = ThreadLocalRandom.current().nextInt(maxMobs - minMobs + 1) + minMobs;
        long totalExperienceLong = (long) spawner.getEntityExperienceValue() * mobCount;
        long totalExperience = Math.min(totalExperienceLong, Long.MAX_VALUE);
        return new LootResult(Collections.emptyList(), totalExperience);
    }

    /**
     * Adds pre-generated loot to spawner instantly when timer expires.
     *
     * @param spawner The spawner to add loot to
     * @param items Pre-generated items list
     * @param experience Pre-generated experience amount
     */
    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience) {
        addPreGeneratedLoot(spawner, items, experience, System.currentTimeMillis());
    }

    /**
     * Adds pre-generated loot to spawner with custom spawn time.
     *
     * <p><b>Thread safety:</b> routing and all final state mutation run on the spawner's valid location
     * execution context under the loot generation lock and the shared location lock, identical to the
     * normal path. A transient lock failure retries the SAME owned clones in a bounded, non-blocking
     * manner so the already-cleared pre-generated batch is neither lost nor routed twice.
     *
     * @param spawner The spawner to add loot to
     * @param items Pre-generated items list
     * @param experience Pre-generated experience amount
     * @param spawnTime The spawn time to set (for timer accuracy)
     */
    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience, long spawnTime) {
        if ((items == null || items.isEmpty()) && experience == 0) {
            return;
        }

        Location spawnerLocation = spawner.getSpawnerLocation();
        if (spawnerLocation == null) {
            return;
        }

        // Build owned deep clones once so a bounded retry can reuse the exact same batch.
        List<ItemStack> ownedItems = new ArrayList<>();
        if (items != null) {
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    ownedItems.add(item.clone());
                }
            }
        }

        schedulePreGeneratedCommit(spawner, ownedItems, experience, spawnTime, 0);
    }

    private void schedulePreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems, long experience, long spawnTime, int attempt) {
        Location loc = spawner.getSpawnerLocation();
        if (loc == null) {
            return;
        }
        Scheduler.runLocationTask(loc, () -> {
            if (!spawner.getLootGenerationLock().tryLock()) {
                retryPreGeneratedCommit(spawner, ownedItems, experience, spawnTime, attempt);
                return;
            }
            CommitStatus status;
            try {
                status = commitGeneratedLoot(spawner, ownedItems, experience, spawnTime);
            } finally {
                spawner.getLootGenerationLock().unlock();
            }
            // Retry ONLY on a transient lock failure (no router was invoked in that case), reusing the
            // same owned clones. COMMITTED / NOOP / ABORTED_STALE must never be retried, preventing any
            // double routing and any tight retry loop.
            if (status == CommitStatus.LOCK_UNAVAILABLE) {
                retryPreGeneratedCommit(spawner, ownedItems, experience, spawnTime, attempt);
            }
        });
    }

    private void retryPreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems, long experience, long spawnTime, int attempt) {
        if (attempt + 1 >= PREGEN_MAX_COMMIT_ATTEMPTS) {
            return; // bounded and non-blocking; give up rather than spin
        }
        Scheduler.runTaskLaterAsync(
                () -> schedulePreGeneratedCommit(spawner, ownedItems, experience, spawnTime, attempt + 1),
                PREGEN_RETRY_DELAY_TICKS);
    }

    /**
     * Callback interface for asynchronous loot pre-generation.
     */
    @FunctionalInterface
    public interface LootGenerationCallback {
        /**
         * Called when loot generation completes.
         *
         * @param items Generated items list (never null, may be empty)
         * @param experience Generated experience amount
         */
        void onLootGenerated(List<ItemStack> items, long experience);
    }
}
