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
        /** Locks acquired but there was nothing to commit; do not retry. */
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

        // Try to acquire the lock, but don't block if it's already locked
        boolean lockAcquired = spawner.getLootGenerationLock().tryLock();
        if (!lockAcquired) {
            return;
        }

        try {
            // Acquire dataLock to safely read spawn timing and configuration values (non-blocking)
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

            // Run heavy calculations async (random-loot phase); NEVER route here.
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
                        // Normal path regenerates fresh each cycle, so a transient lock failure simply
                        // skips this cycle (no double-routing risk); status is intentionally ignored.
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

        // Get valid items from the spawner's EntityLootConfig
        List<LootItem> validItems =  spawner.getValidLootItems();

        if (validItems.isEmpty()) {
            return new LootResult(Collections.emptyList(), totalExperience);
        }

        // Use a Map to consolidate identical drops instead of List
        Map<ItemStack, Integer> consolidatedLoot = new HashMap<>();

        // Process mobs in batch rather than individually
        for (LootItem lootItem : validItems) {
            // Calculate the probability for the entire mob batch at once
            int totalAmount;

            if (Config.get().isApproximateLoot() && shouldApproximate(lootItem.chance(), mobCount)) {
                // O(1) binomial approximation
                totalAmount = generateApproximatedLoot(lootItem, mobCount);
            } else {
                // O(n) binomial distribution
                totalAmount = generateExactLoot(lootItem, mobCount);
            }

            if (totalAmount > 0) {
                // Create item just once per loot type
                ItemStack prototype = lootItem.createItemStack();
                if (prototype != null) {
                    consolidatedLoot.merge(prototype, totalAmount, Integer::sum);
                }
            }
        }

        // Convert consolidated map to item stacks
        List<ItemStack> finalLoot = new ArrayList<>(consolidatedLoot.size());
        for (Map.Entry<ItemStack, Integer> entry : consolidatedLoot.entrySet()) {
            ItemStack item = entry.getKey().clone();
            item.setAmount(Math.min(entry.getValue(), item.getMaxStackSize()));
            finalLoot.add(item);

            // Handle amounts exceeding max stack size
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

    // Determines whether to use expected-value approximation
    private boolean shouldApproximate(double chance, int mobCount) {
        if (chance <= 0D) return false;
        return mobCount > (97.5D / chance) * Config.get().getApproximationThreshold();
    }

    // O(n) simulation: exact per-mob drop calculation
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

    // O(1) expected-value calculation with small jitter
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
     * ID and location. Callers must hold the shared location lock so this check-then-act cannot race
     * with a concurrent detach.
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
     * lock. Acquisition order: lootGenerationLock (held by caller) -> shared location lock -> d