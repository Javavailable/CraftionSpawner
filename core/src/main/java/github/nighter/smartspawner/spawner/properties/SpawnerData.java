package github.nighter.smartspawner.spawner.properties;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.hologram.SpawnerHologram;
import github.nighter.smartspawner.spawner.lootgen.loot.EntityLootConfig;
import github.nighter.smartspawner.spawner.lootgen.loot.LootItem;
import github.nighter.smartspawner.spawner.sell.SellResult;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class SpawnerData {
    @Getter
    private final SmartSpawner plugin;

    @Getter @Setter
    private String spawnerId;
    @Getter
    private final Location spawnerLocation;

    // Fine-grained locks for different operations (Lock Striping Pattern)
    @Getter
    private final ReentrantLock inventoryLock = new ReentrantLock();  // For storage operations
    @Getter
    private final ReentrantLock lootGenerationLock = new ReentrantLock();  // For loot spawning
    @Getter
    private final ReentrantLock dataLock = new ReentrantLock();  // For metadata changes (exp, stack size, etc.)

    // Atomic sell state
    private final AtomicBoolean selling = new AtomicBoolean(false);

    // Atomic removal claim (S2B Skyllia island cleanup).
    private final AtomicBoolean removalPending = new AtomicBoolean(false);

    // Dirty flag for storage GUI
    private final AtomicBoolean storageDirty = new AtomicBoolean(false);

    // Base values from config (immutable after load)
    @Getter
    private long baseMaxStoredExp;
    @Getter @Setter
    private int baseMaxStoragePages;
    @Getter @Setter
    private int baseMinMobs;
    @Getter @Setter
    private int baseMaxMobs;

    @Getter
    private long spawnerExp;
    @Getter @Setter
    private Boolean spawnerActive;
    @Getter @Setter
    private Integer spawnerRange;
    @Getter
    private AtomicBoolean spawnerStop;
    @Getter @Setter
    private Boolean isAtCapacity;
    @Getter @Setter
    private Long lastSpawnTime;
    @Getter
    private long spawnDelay;

    @Getter
    private EntityType entityType;
    @Getter @Setter
    private EntityLootConfig lootConfig;

    @Getter @Setter
    private Material spawnedItemMaterial;

    @Getter
    private int maxStoragePages;
    @Getter @Setter
    private int maxSpawnerLootSlots;
    @Getter @Setter
    private long maxStoredExp;
    @Getter @Setter
    private int minMobs;
    @Getter @Setter
    private int maxMobs;

    @Getter
    private int stackSize;
    @Getter @Setter
    private int maxStackSize;

    @Getter @Setter
    private VirtualInventory virtualInventory;
    @Getter
    private final Set<Material> filteredItems = new HashSet<>();

    @Getter @Setter
    private String lastInteractedPlayer;

    @Getter
    private SellResult lastSellResult;
    @Getter
    private boolean lastSellProcessed;

    @Getter
    private volatile double accumulatedSellValue;

    @Getter
    private volatile boolean sellValueDirty;

    private SpawnerHologram hologram;
    @Getter @Setter
    private long cachedSpawnDelay;

    @Getter @Setter
    private Material preferredSortItem;

    // Pre-generated loot storage - access must be synchronized via lootGenerationLock
    private volatile List<ItemStack> preGeneratedItems;
    private volatile long preGeneratedExperience;
    private volatile boolean isPreGenerating;

    // Pending commit holder for pre-generated loot that could not be committed due to transient
    // lock contention. Drained and merged by the next generation commit. Not exposed via the
    // public API.
    private final Object pendingCommitLock = new Object();
    private PreGeneratedLootBatch pendingCommitBatch;

    private volatile Boolean cachedHasNoLoot = null;

    public SpawnerData(String id, Location location, EntityType type, SmartSpawner plugin) {
        super();
        this.plugin = plugin;
        this.spawnerId = id;
        this.spawnerLocation = location;
        this.entityType = type;
        this.spawnedItemMaterial = null;

        initializeDefaults();
        loadConfigurationValues();
        calculateStackBasedValues();
        initializeComponents();
    }

    public SpawnerData(String id, Location location, Material itemMaterial, SmartSpawner plugin) {
        super();
        this.plugin = plugin;
        this.spawnerId = id;
        this.spawnerLocation = location;
        this.entityType = EntityType.ITEM;
        this.spawnedItemMaterial = itemMaterial;

        initializeDefaults();
        loadConfigurationValues();
        calculateStackBasedValues();
        initializeComponents();
    }

    private void initializeDefaults() {
        this.spawnerExp = 0;
        this.spawnerActive = true;
        this.spawnerStop = new AtomicBoolean(true);
        this.isAtCapacity = false;
        this.stackSize = 1;
        this.lastSpawnTime = System.currentTimeMillis();
        this.preferredSortItem = null;
        this.accumulatedSellValue = 0.0;
        this.sellValueDirty = true;
    }

    public void loadConfigurationValues() {
        this.baseMaxStoredExp = plugin.getConfig().getLong("spawner_properties.default.max_stored_exp", 1000L);
        this.baseMaxStoragePages = plugin.getConfig().getInt("spawner_properties.default.max_storage_pages", 1);
        this.baseMinMobs = plugin.getConfig().getInt("spawner_properties.default.min_mobs", 1);
        this.baseMaxMobs = plugin.getConfig().getInt("spawner_properties.default.max_mobs", 4);
        this.maxStackSize = plugin.getConfig().getInt("spawner_properties.default.max_stack_size", 1000);
        this.spawnDelay = plugin.getTimeFromConfig("spawner_properties.default.delay", "25s");
        this.cachedSpawnDelay = (this.spawnDelay + 20L) * 50L;
        this.spawnerRange = plugin.getConfig().getInt("spawner_properties.default.range", 16);

        if (isItemSpawner() && spawnedItemMaterial != null) {
            this.lootConfig = plugin.getItemSpawnerSettingsConfig().getLootConfig(spawnedItemMaterial);
        } else {
            this.lootConfig = plugin.getSpawnerSettingsConfig().getLootConfig(entityType);
        }
    }

    public void recalculateAfterConfigReload() {
        calculateStackBasedValues();
        if (virtualInventory != null && virtualInventory.getMaxSlots() != maxSpawnerLootSlots) {
            recreateVirtualInventory();
        }
        this.sellValueDirty = true;
        updateHologramData();

        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    public void recalculateAfterAPIModification() {
        calculateStackBasedValues();
        if (virtualInventory != null && virtualInventory.getMaxSlots() != maxSpawnerLootSlots) {
            recreateVirtualInventory();
        }
        updateHologramData();

        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    private void calculateStackBasedValues() {
        this.maxStoredExp = clampToLong(baseMaxStoredExp * stackSize, 0L, Long.MAX_VALUE);
        this.maxStoragePages = clampToInt((long) baseMaxStoragePages * stackSize, 0, Integer.MAX_VALUE);
        this.maxSpawnerLootSlots = clampToInt((long) maxStoragePages * 45L, 0, Integer.MAX_VALUE);
        this.minMobs = clampToInt((long) baseMinMobs * stackSize, 0, Integer.MAX_VALUE);
        this.maxMobs = clampToInt((long) baseMaxMobs * stackSize, 0, Integer.MAX_VALUE);
        this.spawnerExp = clampToLong(this.spawnerExp, 0L, this.maxStoredExp);
    }

    public void setSpawnDelay(long baseSpawnerDelay) {
        this.spawnDelay = baseSpawnerDelay > 0 ? baseSpawnerDelay : 500;
        long ticksWithBuffer = this.spawnDelay > Long.MAX_VALUE - 20L ? Long.MAX_VALUE : this.spawnDelay + 20L;
        this.cachedSpawnDelay = ticksWithBuffer > Long.MAX_VALUE / 50L ? Long.MAX_VALUE : ticksWithBuffer * 50L;
        if (baseSpawnerDelay <= 0) {
            plugin.getLogger().warning("Invalid spawner delay value. Setting to default: 500 ticks (25s)");
        }
    }

    public void setSpawnDelayFromConfig() {
        long delay = plugin.getTimeFromConfig("spawner_properties.default.delay", "25s");
        if (delay <= 0) {
            plugin.getLogger().warning("Invalid spawner delay value in config. Setting to default: 500 ticks (25s)");
            delay = 500L;
        }
        setSpawnDelay(delay);
    }

    private void initializeComponents() {
        this.virtualInventory = new VirtualInventory(maxSpawnerLootSlots);
        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            createHologram();
        }

        if (this.preferredSortItem == null && this.lootConfig != null && this.lootConfig.getAllItems() != null) {
            var lootItems = this.lootConfig.getAllItems();
            if (!lootItems.isEmpty()) {
                var sortedLoot = lootItems.stream()
                        .map(LootItem::material)
                        .distinct()
                        .sorted(Comparator.comparing(Material::name))
                        .toList();

                if (!sortedLoot.isEmpty()) {
                    this.preferredSortItem = sortedLoot.getFirst();
                }
            }
        }
        this.virtualInventory.sortItems(this.preferredSortItem);
    }

    private void createHologram() {
        this.hologram = new SpawnerHologram(spawnerLocation);
        this.hologram.createHologram();
        updateHologramData();
    }

    public void setStackSize(int stackSize) {
        setStackSize(stackSize, true);
    }

    public void setStackSize(int stackSize, boolean restartHopper) {
        dataLock.lock();
        try {
            inventoryLock.lock();
            try {
                updateStackSize(stackSize, restartHopper);
            } finally {
                inventoryLock.unlock();
            }
        } finally {
            dataLock.unlock();
        }
    }

    private void updateStackSize(int newStackSize, boolean restartHopper) {
        if (newStackSize <= 0) {
            this.stackSize = 1;
            plugin.getLogger().warning("Invalid stack size. Setting to 1");
            return;
        }

        if (newStackSize > this.maxStackSize && newStackSize > this.stackSize) {
            plugin.getLogger().warning("Stack size " + newStackSize + " exceeds maximum " + this.maxStackSize + ". Ignoring.");
            return;
        }

        this.stackSize = newStackSize;
        calculateStackBasedValues();
        virtualInventory.resize(this.maxSpawnerLootSlots);
        this.lastSpawnTime = System.currentTimeMillis();
        updateHologramData();

        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    private void recreateVirtualInventory() {
        if (virtualInventory == null) return;
        virtualInventory.resize(maxSpawnerLootSlots);
    }

    public void setSpawnerExp(long exp) {
        this.spawnerExp = Math.clamp(exp, 0L, maxStoredExp);
        updateHologramData();

        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    public void setSpawnerExpData(long exp) {
        this.spawnerExp = Math.max(0L, exp);
    }

    public void setBaseMaxStoredExp(long baseMaxStoredExp) {
        this.baseMaxStoredExp = Math.max(0L, baseMaxStoredExp);
    }

    private int clampToInt(long value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return (int) value;
    }

    private long clampToLong(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public void updateHologramData() {
        if (hologram != null) {
            hologram.updateData(stackSize, entityType, spawnerExp, maxStoredExp,
                    virtualInventory.getUsedSlots(), maxSpawnerLootSlots);
        }
    }

    public void reloadHologramData() {
        if (hologram != null) {
            hologram.remove();
            createHologram();
        }
    }

    public void refreshHologram() {
        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            if (hologram == null) createHologram();
        } else if (hologram != null) {
            removeHologram();
        }
    }

    public void removeHologram() {
        if (hologram != null) {
            hologram.remove();
            hologram = null;
        }
    }

    public boolean isCompletelyFull() {
        return virtualInventory.getUsedSlots() >= maxSpawnerLootSlots && spawnerExp >= maxStoredExp;
    }

    public boolean updateCapacityStatus() {
        boolean newStatus = isCompletelyFull();
        if (newStatus != isAtCapacity) {
            isAtCapacity = newStatus;
            return true;
        }
        return false;
    }

    public void setEntityType(EntityType newType) {
        this.entityType = newType;
        this.lootConfig = plugin.getSpawnerSettingsConfig().getLootConfig(newType);
        this.sellValueDirty = true;
        updateHologramData();
    }

    public boolean toggleItemFilter(Material material) {
        boolean wasFiltered = filteredItems.contains(material);
        if (wasFiltered) filteredItems.remove(material);
        else filteredItems.add(material);
        return !wasFiltered;
    }

    public List<LootItem> getValidLootItems() {
        if (lootConfig == null) return Collections.emptyList();
        return lootConfig.getAllItems().stream()
                .filter(this::isLootItemValid)
                .collect(Collectors.toList());
    }

    private boolean isLootItemValid(LootItem item) {
        ItemStack example = item.createItemStack();
        return example != null && !filteredItems.contains(example.getType());
    }

    public int getEntityExperienceValue() {
        return lootConfig != null ? lootConfig.experience() : 0;
    }

    public boolean hasNoLootOrExperience() {
        if (cachedHasNoLoot != null) return cachedHasNoLoot;
        boolean result = (lootConfig == null ||
                (lootConfig.experience() == 0 && getValidLootItems().isEmpty()));
        cachedHasNoLoot = result;
        return result;
    }

    public void setLootConfig() {
        if (isItemSpawner() && spawnedItemMaterial != null) {
            this.lootConfig = plugin.getItemSpawnerSettingsConfig().getLootConfig(spawnedItemMaterial);
        } else {
            this.lootConfig = plugin.getSpawnerSettingsConfig().getLootConfig(entityType);
        }
        this.sellValueDirty = true;
        this.cachedHasNoLoot = null;
    }

    public void setLastSellResult(SellResult sellResult) {
        this.lastSellResult = sellResult;
        this.lastSellProcessed = false;
    }

    public void markLastSellAsProcessed() {
        this.lastSellProcessed = true;
        this.lastSellResult = null;
    }

    public boolean isSelling() { return selling.get(); }

    public boolean startSelling() {
        if (removalPending.get()) return false;
        if (!selling.compareAndSet(false, true)) return false;
        if (removalPending.get()) { selling.set(false); return false; }
        return true;
    }

    public void stopSelling() { selling.set(false); }

    public boolean tryBeginRemoval() {
        if (!removalPending.compareAndSet(false, true)) return false;
        if (selling.get()) { removalPending.set(false); return false; }
        return true;
    }

    public void cancelRemoval() { removalPending.set(false); }

    public boolean isRemovalPending() { return removalPending.get(); }

    public boolean isStorageDirty() { return storageDirty.get(); }
    public void markStorageDirty() { storageDirty.set(true); }
    public void clearStorageDirty() { storageDirty.set(false); }

    public void updateLastInteractedPlayer(String playerName) { this.lastInteractedPlayer = playerName; }

    public void markSellValueDirty() { this.sellValueDirty = true; }

    public void incrementSellValue(Map<ItemSignature, Long> itemsAdded, Map<String, Double> priceCache) {
        if (itemsAdded == null || itemsAdded.isEmpty()) return;
        double addedValue = 0.0;
        for (Map.Entry<ItemSignature, Long> entry : itemsAdded.entrySet()) {
            double itemPrice = findItemPrice(entry.getKey(), priceCache);
            if (itemPrice > 0.0) addedValue += itemPrice * entry.getValue();
        }
        this.accumulatedSellValue += addedValue;
        this.sellValueDirty = false;
    }

    public void decrementSellValue(List<ItemStack> itemsRemoved, Map<String, Double> priceCache) {
        if (itemsRemoved == null || itemsRemoved.isEmpty()) return;
        Map<ItemSignature, Long> consolidated = new java.util.HashMap<>();
        for (ItemStack item : itemsRemoved) {
            if (item == null || item.getAmount() <= 0) continue;
            ItemSignature sig = VirtualInventory.getSignature(item);
            consolidated.merge(sig, (long) item.getAmount(), (a, b) -> a + b);
        }
        double removedValue = 0.0;
        for (Map.Entry<ItemSignature, Long> entry : consolidated.entrySet()) {
            double itemPrice = findItemPrice(entry.getKey(), priceCache);
            if (itemPrice > 0.0) removedValue += itemPrice * entry.getValue();
        }
        this.accumulatedSellValue = Math.max(0.0, this.accumulatedSellValue - removedValue);
    }

    public void recalculateSellValue() {
        if (lootConfig == null) { this.accumulatedSellValue = 0.0; this.sellValueDirty = false; return; }
        Map<String, Double> priceCache = createPriceCache();
        Map<ItemSignature, Long> items = virtualInventory.getConsolidatedItems();
        double totalValue = 0.0;
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            double itemPrice = findItemPrice(entry.getKey(), priceCache);
            if (itemPrice > 0.0) totalValue += itemPrice * entry.getValue();
        }
        this.accumulatedSellValue = totalValue;
        this.sellValueDirty = false;
    }

    public Map<String, Double> createPriceCache() {
        if (lootConfig == null) return new java.util.HashMap<>();
        github.nighter.smartspawner.hooks.economy.ItemPriceManager priceManager = plugin.getItemPriceManager();
        Map<String, Double> cache = new java.util.HashMap<>();
        java.util.List<LootItem> allLootItems = lootConfig.getAllItems();
        for (LootItem lootItem : allLootItems) {
            double price = (priceManager != null) ? priceManager.getPrice(lootItem.material()) : 0.0;
            if (price <= 0.0) price = lootItem.sellPrice();
            if (price > 0.0) {
                ItemStack template = lootItem.createItemStack();
                if (template != null) {
                    String key = createItemKey(template);
                    cache.put(key, price);
                }
            }
        }
        return cache;
    }

    private double findItemPrice(ItemSignature itemSignature, Map<String, Double> priceCache) {
        if (priceCache == null) return 0.0;
        String itemKey = createItemKey(itemSignature);
        Double price = priceCache.get(itemKey);
        return price != null ? price : 0.0;
    }

    private String createItemKey(ItemStack itemStack) {
        if (itemStack == null) return "null";
        return createItemKey(new ItemSignature(itemStack));
    }

    private String createItemKey(ItemSignature itemSignature) {
        StringBuilder key = new StringBuilder();
        key.append(itemSignature.getMaterial().name());
        ItemMeta meta = itemSignature.getUnsafeTemplateRef().getItemMeta();
        if (itemSignature.hasItemMeta() && meta.hasEnchants()) {
            key.append("_enchants:");
            meta.getEnchants().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(enchantment -> enchantment.getKey().toString())))
                    .forEach(entry -> key.append(entry.getKey().getKey()).append(":").append(entry.getValue()).append(","));
        }
        if (itemSignature.hasItemMeta() && meta.hasDisplayName()) {
            key.append("_name:").append(meta.displayName());
        }
        return key.toString();
    }

    /**
     * Adds items to virtual inventory and updates accumulated sell value.
     * Acquires {@link #inventoryLock} (blocking) then delegates to
     * {@link #addItemsAndUpdateSellValueWhileLocked}. Use this for all callers that do NOT
     * already own the inventory lock (e.g. sell flow, hopper, API).
     *
     * @param items Items to add
     */
    public void addItemsAndUpdateSellValue(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        inventoryLock.lock();
        try {
            addItemsAndUpdateSellValueWhileLocked(items);
        } finally {
            inventoryLock.unlock();
        }
    }

    /**
     * Adds items to the virtual inventory and updates sell value WITHOUT acquiring
     * {@link #inventoryLock}. The caller MUST already hold the inventory lock (as the generation
     * commit path does after its non-blocking {@code tryLock}). Throws
     * {@link IllegalStateException} if called without owning the lock.
     *
     * @param items Items to add
     */
    public void addItemsAndUpdateSellValueWhileLocked(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        if (!inventoryLock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "addItemsAndUpdateSellValueWhileLocked requires the caller to hold inventoryLock");
        }
        Map<ItemSignature, Long> itemsToAdd = new java.util.HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            ItemSignature sig = VirtualInventory.getSignature(item);
            itemsToAdd.merge(sig, (long) item.getAmount(), (a, b) -> a + b);
        }
        virtualInventory.addItems(items);
        if (!sellValueDirty) {
            Map<String, Double> priceCache = createPriceCache();
            incrementSellValue(itemsToAdd, priceCache);
        }
    }

    public boolean removeItemsAndUpdateSellValue(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return true;
        inventoryLock.lock();
        try {
            boolean removed = virtualInventory.removeItems(items);
            if (removed && !sellValueDirty) {
                Map<String, Double> priceCache = createPriceCache();
                decrementSellValue(items, priceCache);
            }
            return removed;
        } finally {
            inventoryLock.unlock();
        }
    }

    // ===== Pre-generated loot (synchronized via lootGenerationLock by callers) =====

    public synchronized void storePreGeneratedLoot(List<ItemStack> items, long experience) {
        this.preGeneratedItems = items;
        this.preGeneratedExperience = experience;
    }

    public synchronized List<ItemStack> getAndClearPreGeneratedItems() {
        List<ItemStack> items = preGeneratedItems;
        preGeneratedItems = null;
        return items;
    }

    public synchronized long getAndClearPreGeneratedExperience() {
        long exp = preGeneratedExperience;
        preGeneratedExperience = 0;
        return exp;
    }

    public synchronized boolean hasPreGeneratedLoot() {
        return (preGeneratedItems != null && !preGeneratedItems.isEmpty()) || preGeneratedExperience > 0;
    }

    public synchronized void setPreGenerating(boolean generating) { this.isPreGenerating = generating; }
    public synchronized boolean isPreGenerating() { return isPreGenerating; }

    public synchronized void clearPreGeneratedLoot() {
        preGeneratedItems = null;
        preGeneratedExperience = 0;
        isPreGenerating = false;
    }

    // ===== Pending commit holder for pre-generated batches =====

    /**
     * Immutable holder for a pre-generated item and XP batch that was requeued after retry
     * exhaustion. Drained and merged by the next successful generation commit. Never exposed
     * through the public API.
     */
    public static final class PreGeneratedLootBatch {
        private final List<ItemStack> items;    // unmodifiable, owned deep clones
        private final long experience;

        PreGeneratedLootBatch(List<ItemStack> items, long experience) {
            List<ItemStack> copy = new ArrayList<>();
            if (items != null) for (ItemStack it : items) if (it != null) copy.add(it.clone());
            this.items = Collections.unmodifiableList(copy);
            this.experience = Math.max(0L, experience);
        }

        public List<ItemStack> getItems() { return items; }
        public long getExperience() { return experience; }
    }

    /**
     * Merges a batch into the pending-commit holder. If a holder already exists (from an earlier
     * exhaustion) the two are merged rather than overwritten, so a newer requeue never silently
     * discards an older one.
     */
    public void queuePendingCommitBatch(List<ItemStack> items, long experience) {
        synchronized (pendingCommitLock) {
            if (pendingCommitBatch == null) {
                pendingCommitBatch = new PreGeneratedLootBatch(items, experience);
            } else {
                List<ItemStack> merged = new ArrayList<>(pendingCommitBatch.getItems());
                if (items != null) for (ItemStack it : items) if (it != null) merged.add(it.clone());
                long e = pendingCommitBatch.getExperience();
                long add = Math.max(0L, experience);
                long total = (add > Long.MAX_VALUE - e) ? Long.MAX_VALUE : e + add;
                pendingCommitBatch = new PreGeneratedLootBatch(merged, total);
            }
        }
    }

    /**
     * Atomically takes and clears the pending-commit holder. Returns {@code null} if none exists.
     * The caller owns the returned batch and is responsible for committing it.
     */
    public PreGeneratedLootBatch takePendingCommitBatch() {
        synchronized (pendingCommitLock) {
            PreGeneratedLootBatch b = pendingCommitBatch;
            pendingCommitBatch = null;
            return b;
        }
    }

    /** @return {@code true} if there is a pending pre-generated batch waiting to be committed. */
    public boolean hasPendingCommitBatch() {
        synchronized (pendingCommitLock) {
            return pendingCommitBatch != null;
        }
    }

    /** Clears any pending-commit batch (e.g. when the spawner is detached). */
    public void clearPendingCommitBatch() {
        synchronized (pendingCommitLock) {
            pendingCommitBatch = null;
        }
    }

    public boolean isItemSpawner() {
        return entityType == EntityType.ITEM && spawnedItemMaterial != null;
    }
}
