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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class SpawnerLootGenerator {
    private final SmartSpawner plugin;
    private final SpawnerGuiViewManager spawnerGuiViewManager;
    private final SpawnerManager spawnerManager;
    private final SpawnerOutputRoutingService outputRoutingService;

    // Bounded, non-blocking retry for the pre-generated commit when a transient lock is unavailable.
    private static final int PREGEN_MAX_COMMIT_ATTEMPTS = 5;
    private static final long PREGEN_RETRY_DELAY_TICKS = 2L;
    private static final long PREGEN_HANDOFF_DRAIN_PERIOD_TICKS = 20L;
    private static final long PREGEN_WARN_INTERVAL_MS = 60_000L;

    // Rate-limited warnings for pre-gen exhaustion requeue events (per spawner ID).
    private final ConcurrentHashMap<String, Long> lastPreGenWarnById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HandoffKey, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Scheduler.Task pendingHandoffDrainTask;

    /**
     * Outcome of a single {@link #commitGeneratedLoot} attempt.
     */
    private enum CommitStatus {
        /** Cycle committed successfully (timing advanced, side effects applied). */
        COMMITTED,
        /** Locks acquired but nothing needed committing; do not retry. */
        NOOP,
        /**
         * A required non-blocking lock/claim was unavailable. Safe to retry the same batch;
         * no router was invoked and no state was mutated.
         */
        LOCK_UNAVAILABLE,
        /** Spawner is being removed or is a stale instance; must not route/mutate, do not retry. */
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

                Scheduler.runLocationTask(spawner.getSpawnerLocation(), () -> {
                    boolean updateLockAcquired = spawner.getLootGenerationLock().tryLock();
                    if (!updateLockAcquired) {
                        return;
                    }
                    try {
                        // Normal path regenerates a fresh batch next cycle, so a transient lock
                        // failure simply skips this cycle (no double-routing risk).
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

        List<LootItem> validItems = spawner.getValidLootItems();

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
     * {@link SpawnerRemovalService#isRemovalPending(SpawnerData)}) plus exact-instance identity by
     * ID and location. Callers must hold the shared location lock so this check-then-act cannot
     * race with a concurrent detach.
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
     * Strict handoff-drain guard. Callers must hold the shared location lock for {@code expectedLoc}.
     */
    private boolean isCurrentExactInstance(SpawnerData spawner, String expectedId, Location expectedLoc) {
        if (spawner == null || expectedId == null || expectedLoc == null || spawner.isRemovalPending()) {
            return false;
        }
        SpawnerRemovalService removalService = plugin.getSpawnerRemovalService();
        if (removalService != null && removalService.isRemovalPending(spawner)) {
            return false;
        }
        if (!expectedId.equals(spawner.getSpawnerId())) {
            return false;
        }
        if (spawner.getSpawnerLocation() != expectedLoc) {
            return false;
        }
        return spawnerManager.getSpawnerById(expectedId) == spawner
                && spawnerManager.getSpawnerByLocation(expectedLoc) == spawner;
    }

    /**
     * Single, race-safe commit used by BOTH loot paths.
     *
     * <p>Must be called on the spawner's location execution context while holding the loot
     * generation lock. Full, non-blocking acquisition order:
     * <pre>
     *   lootGenerationLock (held by caller)
     *   -&gt; shared location lock  (SpawnerLocationLockManager)
     *   -&gt; dataLock
     *   -&gt; inventoryLock (when an item batch is possible)
     *   -&gt; router invocation
     *   -&gt; XP / item / timer commit
     * </pre>
     * All four acquisition points are non-blocking {@code tryLock}. If any is unavailable, no
     * router runs and no state is mutated, making the status safe to retry.
     *
     * <p>The shared location lock serializes this entire commit against Skyllia cleanup and
     * ordinary/API/physical removal, preventing the spawner from being detached mid-commit.
     */
    private CommitStatus commitGeneratedLoot(SpawnerData spawner, List<ItemStack> generatedItems, long experience, long spawnTime) {
        Location loc = spawner.getSpawnerLocation();
        if (loc == null) {
            spawner.resetGeneratedLootState();
            return CommitStatus.ABORTED_STALE;
        }

        SpawnerLocationLockManager lockManager = plugin.getSpawnerLocationLockManager();
        if (lockManager == null || !lockManager.tryLock(loc)) {
            return CommitStatus.LOCK_UNAVAILABLE;
        }
        SpawnerData.PendingCommitClaim pendingClaim = null;
        boolean pendingClaimAcked = false;
        try {
            // Removal / stale-instance safety under the shared lock.
            if (isStaleOrRemoving(spawner)) {
                spawner.resetGeneratedLootState();
                return CommitStatus.ABORTED_STALE;
            }

            // dataLock acquired before any router runs so lastSpawnTime always commits exactly once.
            if (!spawner.getDataLock().tryLock()) {
                return CommitStatus.LOCK_UNAVAILABLE;
            }
            boolean inventoryHeld = false;
            try {
                // Non-blocking inventory claim before router invocation when an item batch is possible.
                // hasPendingCommitBatch() peeks without taking; taking happens after all locks held.
                boolean itemsPossible = (generatedItems != null && !generatedItems.isEmpty())
                        || spawner.hasPendingCommitBatch();
                if (itemsPossible) {
                    if (!spawner.getInventoryLock().tryLock()) {
                        return CommitStatus.LOCK_UNAVAILABLE; // nothing mutated or taken yet
                    }
                    inventoryHeld = true;
                }

                // Claim any requeued pre-generated batches and merge them with fresh loot. The
                // pending entries remain in SpawnerData until this commit returns COMMITTED.
                pendingClaim = spawner.claimPendingCommitBatches();
                List<ItemStack> effectiveItems;
                long effectiveExperience;
                if (pendingClaim != null) {
                    effectiveItems = new ArrayList<>();
                    if (generatedItems != null) effectiveItems.addAll(generatedItems);
                    effectiveItems.addAll(pendingClaim.getItems());
                    effectiveExperience = saturatedAdd(experience, pendingClaim.getExperience());
                } else {
                    effectiveItems = generatedItems;
                    effectiveExperience = experience;
                }

                boolean generatedItemsPresent = effectiveItems != null && !effectiveItems.isEmpty();

                boolean xpChanged = false;
                if (effectiveExperience > 0 && spawner.getSpawnerExp() < spawner.getMaxStoredExp()) {
                    long currentExp = spawner.getSpawnerExp();
                    long maxExp = spawner.getMaxStoredExp();
                    long newExp = Math.min(saturatedAdd(currentExp, effectiveExperience), maxExp);
                    if (newExp != currentExp) {
                        spawner.setSpawnerExp(newExp);
                        xpChanged = true;
                    }
                }

                boolean externallyConsumed = false;
                boolean routerAttempted = false;
                boolean internallyInserted = false;

                if (generatedItemsPresent) {
                    List<ItemStack> remainder = effectiveItems;
                    if (outputRoutingService != null && outputRoutingService.hasActiveRouters()) {
                        SpawnerOutputRoutingService.RoutingOutcome outcome =
                                outputRoutingService.route(spawner, effectiveItems);
                        remainder = outcome.remaining();
                        externallyConsumed = outcome.consumedAny();
                        // attempted() is derived from the exact snapshot used by route() — never
                        // from a separate hasActiveRouters() read.
                        routerAttempted = outcome.attempted();
                    }
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
                                // Caller already holds inventoryLock; use the locked variant to
                                // avoid the blocking lock() call inside addItemsAndUpdateSellValue.
                                spawner.addItemsAndUpdateSellValueWhileLocked(itemsToAdd);
                                internallyInserted = true;
                            }
                        }
                    }
                }

                // Advance the cycle timer exactly once when something changed or a batch was
                // genuinely presented to active routers. routerAttempted uses the snapshot-accurate
                // outcome.attempted() — never a stale hasActiveRouters() read.
                boolean advanceCycle = xpChanged || internallyInserted
                        || externallyConsumed || routerAttempted;
                if (!advanceCycle) {
                    return CommitStatus.NOOP;
                }
                spawner.setLastSpawnTime(spawnTime);
            } finally {
                if (inventoryHeld) spawner.getInventoryLock().unlock();
                spawner.getDataLock().unlock();
            }

            // Side effects after releasing dataLock/inventoryLock, still under location lock.
            spawner.updateCapacityStatus();
            handleGuiUpdates(spawner);
            spawnerManager.markSpawnerModified(spawner.getSpawnerId());
            if (pendingClaim != null) {
                spawner.ackPendingCommitClaim(pendingClaim);
                pendingClaimAcked = true;
            }
            return CommitStatus.COMMITTED;
        } finally {
            if (pendingClaim != null && !pendingClaimAcked) {
                spawner.releasePendingCommitClaim(pendingClaim);
            }
            // Never call removeLock() from the generation path; the periodic cleanup task
            // reclaims unused locks so a second lock object can never be created for the location.
            lockManager.unlock(loc);
        }
    }

    /**
     * Saturating addition that returns {@link Long#MAX_VALUE} instead of overflowing.
     */
    private static long saturatedAdd(long a, long b) {
        long result = a + b;
        // If a and b have the same sign and result has a different sign, overflow occurred.
        if (((a ^ result) & (b ^ result)) < 0) return Long.MAX_VALUE;
        return result;
    }

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

    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience) {
        addPreGeneratedLoot(spawner, items, experience, System.currentTimeMillis());
    }

    /**
     * Adds pre-generated loot to spawner with custom spawn time.
     *
     * <p><b>Thread safety:</b> routing and all final state mutation run on the spawner's valid
     * location execution context under the loot generation lock and the shared location lock,
     * identical to the normal path. A transient lock failure retries the SAME owned clones in a
     * bounded, non-blocking manner. On retry exhaustion (all locks busy for the full budget), the
     * batch is requeued into the spawner's pending-commit holder and flushed by the next generation
     * commit via a single managed handoff drain, so the already-cleared batch is neither lost nor
     * routed twice.
     */
    public void addPreGeneratedLoot(SpawnerData spawner, List<ItemStack> items, long experience, long spawnTime) {
        if ((items == null || items.isEmpty()) && experience == 0) {
            return;
        }

        Location spawnerLocation = spawner.getSpawnerLocation();
        if (spawnerLocation == null) {
            return;
        }

        // Build owned deep clones once; the same batch is reused across all retries.
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

    private void schedulePreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems,
                                             long experience, long spawnTime, int attempt) {
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
            // Retry ONLY on transient lock failure (no router was invoked in that case), reusing
            // the same owned clones. COMMITTED / NOOP / ABORTED_STALE must never be retried,
            // preventing any double-routing and avoiding tight retry loops.
            if (status == CommitStatus.LOCK_UNAVAILABLE) {
                retryPreGeneratedCommit(spawner, ownedItems, experience, spawnTime, attempt);
            }
        });
    }

    private void retryPreGeneratedCommit(SpawnerData spawner, List<ItemStack> ownedItems,
                                          long experience, long spawnTime, int attempt) {
        if (attempt + 1 >= PREGEN_MAX_COMMIT_ATTEMPTS) {
            // Retry budget exhausted. Never silently discard the already-cleared batch:
            // merge the SAME owned clones into the generator-owned handoff queue. A single
            // periodic drain will later claim the location lock, re-check exact identity/removal
            // state, and only then queue the batch into SpawnerData pending commit state.
            queuePendingHandoff(spawner, ownedItems, experience);
            return;
        }
        Scheduler.runTaskLaterAsync(
                () -> schedulePreGeneratedCommit(spawner, ownedItems, experience, spawnTime, attempt + 1),
                PREGEN_RETRY_DELAY_TICKS);
    }

    private void warnPreGenRequeue(SpawnerData spawner) {
        String id = spawner.getSpawnerId();
        long now = System.currentTimeMillis();
        Long last = lastPreGenWarnById.get(id);
        if (last != null && now - last < PREGEN_WARN_INTERVAL_MS) return;
        lastPreGenWarnById.put(id, now);
        plugin.getLogger().warning("Pre-generated loot for spawner " + id
                + " could not commit after " + PREGEN_MAX_COMMIT_ATTEMPTS
                + " attempts (locks busy); batch queued for pending handoff drain.");
    }

    private void queuePendingHandoff(SpawnerData spawner, List<ItemStack> ownedItems, long experience) {
        if (spawner == null || ((ownedItems == null || ownedItems.isEmpty()) && experience <= 0L)) {
            return;
        }

        Location location = spawner.getSpawnerLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        HandoffKey key = new HandoffKey(spawner);
        PendingHandoff handoff = new PendingHandoff(spawner, spawner.getSpawnerId(), location, ownedItems, experience);
        pendingHandoffs.merge(key, handoff, PendingHandoff::merge);
        warnPreGenRequeue(spawner);
    }

    private void drainPendingHandoffs() {
        if (pendingHandoffs.isEmpty()) {
            return;
        }

        SpawnerLocationLockManager lockManager = plugin.getSpawnerLocationLockManager();
        if (lockManager == null) {
            return;
        }

        for (Map.Entry<HandoffKey, PendingHandoff> entry : pendingHandoffs.entrySet()) {
            PendingHandoff handoff = entry.getValue();
            Location location = handoff.location;
            if (location == null || location.getWorld() == null) {
                pendingHandoffs.remove(entry.getKey(), handoff);
                continue;
            }

            if (!lockManager.tryLock(location)) {
                continue;
            }

            try {
                if (!isCurrentExactInstance(handoff.spawner, handoff.spawnerId, location)) {
                    pendingHandoffs.remove(entry.getKey(), handoff);
                    continue;
                }

                if (pendingHandoffs.remove(entry.getKey(), handoff)) {
                    handoff.spawner.queuePendingCommitBatch(handoff.items, handoff.experience);
                }
            } finally {
                lockManager.unlock(location);
            }
        }
    }

    public void shutdown() {
        if (pendingHandoffDrainTask != null) {
            pendingHandoffDrainTask.cancel();
        }

        int unresolved = pendingHandoffs.size();
        if (unresolved > 0) {
            plugin.getLogger().warning("Discarding " + unresolved
                    + " unresolved pre-generated pending handoff batch(es) during shutdown.");
            pendingHandoffs.clear();
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
        private final int identityHash;

        private HandoffKey(SpawnerData spawner) {
            this.spawner = spawner;
            this.identityHash = System.identityHashCode(spawner);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof HandoffKey other && spawner == other.spawner;
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

        private PendingHandoff(SpawnerData spawner, String spawnerId, Location location,
                               List<ItemStack> items, long experience) {
            this.spawner = spawner;
            this.spawnerId = spawnerId;
            this.location = location;
            this.items = Collections.unmodifiableList(deepCloneItems(items));
            this.experience = Math.max(0L, experience);
        }

        private PendingHandoff merge(PendingHandoff other) {
            List<ItemStack> mergedItems = new ArrayList<>(items.size() + other.items.size());
            mergedItems.addAll(deepCloneItems(items));
            mergedItems.addAll(deepCloneItems(other.items));
            return new PendingHandoff(
                    spawner,
                    spawnerId,
                    location,
                    mergedItems,
                    saturatedAdd(experience, other.experience));
        }
    }

    @FunctionalInterface
    public interface LootGenerationCallback {
        void onLootGenerated(List<ItemStack> items, long experience);
    }
}
