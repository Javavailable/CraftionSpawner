package github.nighter.smartspawner.spawner.config;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.hooks.economy.ItemPriceManager;
import github.nighter.smartspawner.spawner.lootgen.loot.EntityLootConfig;
import github.nighter.smartspawner.spawner.lootgen.loot.LootItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the item spawner settings configuration
 */
public class ItemSpawnerSettingsConfig {
    private final SmartSpawner plugin;
    private FileConfiguration config;
    private final File configFile;
    
    // Item head data
    private Material defaultMaterial;
    private final Map<Material, ItemHeadData> itemHeadMap = new EnumMap<>(Material.class);
    private final Set<Material> validItemSpawnerMaterials = new HashSet<>();
    
    // Loot data for item spawners
    private final Map<Material, EntityLootConfig> itemLootConfigs = new ConcurrentHashMap<>();
    
    public ItemSpawnerSettingsConfig(SmartSpawner plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "item_spawners_settings.yml");
    }
    
    /**
     * Load or create the item spawners settings configuration
     */
    public void load() {
        // Create config file if it doesn't exist
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        
        // Load the configuration
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // Parse configuration
        parseConfig();
    }
    
    /**
     * Save the default configuration from resources
     */
    private void saveDefaultConfig() {
        try {
            InputStream inputStream = plugin.getResource("item_spawners_settings.yml");
            if (inputStream == null) {
                plugin.debug("Could not find item_spawners_settings.yml in plugin resources");
                return;
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            
            plugin.debug("Created default item_spawners_settings.yml configuration");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to create default item_spawners_settings.yml: " + e.getMessage());
        }
    }
    
    /**
     * Parse the configuration and populate item head data
     */
    private void parseConfig() {
        itemHeadMap.clear();
        validItemSpawnerMaterials.clear();
        itemLootConfigs.clear();
        
        // Get default material
        String defaultMaterialName = config.getString("default_material", "SPAWNER");
        try {
            defaultMaterial = Material.valueOf(defaultMaterialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid default_material in item_spawners_settings.yml: " + defaultMaterialName + ", using SPAWNER");
            defaultMaterial = Material.SPAWNER;
        }
        
        // Parse each item's configuration
        for (String materialName : config.getKeys(false)) {
            // Skip special keys
            if (materialName.equals("default_material")) {
                continue;
            }
            
            // Validate material type
            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Material '" + materialName + "' is invalid or not available in server version " + plugin.getServer().getBukkitVersion());
                continue;
            }
            
            ConfigurationSection itemSection = config.getConfigurationSection(materialName);
            if (itemSection == null) continue;
            
            // Verify the material field matches
            String configMaterial = itemSection.getString("material");
            if (configMaterial == null || !configMaterial.equalsIgnoreCase(materialName)) {
                plugin.getLogger().warning("Material mismatch for " + materialName + " in item_spawners_settings.yml");
                continue;
            }
            
            // Parse head texture data
            parseHeadTexture(material, itemSection);
            
            // Parse loot data
            parseLootData(material, itemSection);
            
            // Add to valid materials set
            validItemSpawnerMaterials.add(material);
        }
        
        plugin.debug("Loaded " + validItemSpawnerMaterials.size() + " item spawner configurations");
    }
    
    /**
     * Parse loot configuration for an item spawner
     */
    private void parseLootData(Material material, ConfigurationSection itemSection) {
        int experience = itemSection.getInt("experience", 0);
        List<LootItem> items = new ArrayList<>();
        
        // Cache price manager reference for better performance
        ItemPriceManager priceManager = plugin.getItemPriceManager();
        
        ConfigurationSection lootSection = itemSection.getConfigurationSection("loot");
        if (lootSection != null) {
            for (String itemKey : lootSection.getKeys(false)) {
                ConfigurationSection lootItemSection = lootSection.getConfigurationSection(itemKey);
                if (lootItemSection == null) continue;
                
                try {
                    // Get the material
                    Material lootMaterial;
                    try {
                        lootMaterial = Material.valueOf(itemKey.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Material '" + itemKey + "' is not available in server version " +
                                plugin.getServer().getBukkitVersion() + " - skipping for item spawner " + material.name());
                        continue;
                    }
                    
                    String[] amounts = lootItemSection.getString("amount", "1-1").split("-");
                    int minAmount = Integer.parseInt(amounts[0]);
                    int maxAmount = Integer.parseInt(amounts.length > 1 ? amounts[1] : amounts[0]);
                    double chance = lootItemSection.getDouble("chance", 100.0);
                    
                    double sellPrice = 0.0;
                    if (priceManager != null) {
                        sellPrice = priceManager.getPrice(lootMaterial);
                    }
                    
                    // Item spawners don't need durability or potion type
                    Integer minDurability = null;
                    Integer maxDurability = null;
                    PotionType potionType = null;
                    
                    LootItem lootItem = new LootItem(lootMaterial, minAmount, maxAmount, chance, 
                            minDurability, maxDurability, potionType, sellPrice);
                    items.add(lootItem);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error parsing loot item " + itemKey + " for item spawner " + material.name() + ": " + e.getMessage());
                }
            }
        }
        
        // Create and store EntityLootConfig
        EntityLootConfig lootConfig = new EntityLootConfig(experience, items);
        itemLootConfigs.put(material, lootConfig);
    }
    
    /**
     * Parse head texture configuration for an item
     */
    private void parseHeadTexture(Material material, ConfigurationSection itemSection) {
        ConfigurationSection headSection = itemSection.getConfigurationSection("head_texture");
        if (headSection == null) {
            return;
        }
        
        String headMaterialName = headSection.getString("material", material.name());
        String customTexture = headSection.getString("custom_texture");
        
        // Validate material
        Material headMaterial;
        try {
            headMaterial = Material.valueOf(headMaterialName.toUpperCase());
            if (!headMaterial.isItem()) {
                plugin.getLogger().warning("Material " + headMaterialName + " for " + material + " is not an item, using the item itself");
                headMaterial = material;
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid head material " + headMaterialName + " for " + material + ", using the item itself");
            headMaterial = material;
        }
        
        // Store item head data
        itemHeadMap.put(material, new ItemHeadData(headMaterial, customTexture));
    }
    
    /**
     * Get the head texture data for an item material
     */
    public ItemHeadData getHeadData(Material material) {
        return itemHeadMap.getOrDefault(material, new ItemHeadData(defaultMaterial, null));
    }
    
    /**
     * Get the loot configuration for an item spawner material
     */
    public EntityLootConfig getLootConfig(Material material) {
        return itemLootConfigs.get(material);
    }
    
    /**
     * Check if a material is a valid item spawner type
     */
    public boolean isValidItemSpawner(Material material) {
        return validItemSpawnerMaterials.contains(material);
    }
    
    /**
     * Get all valid item spawner materials
     */
    public Set<Material> getValidItemSpawnerMaterials() {
        return Collections.unmodifiableSet(validItemSpawnerMaterials);
    }
    
    /**
     * Reload the configuration
     */
    public void reload() {
        load();
    }
    
    /**
     * Data class for item head information
     */
    public static class ItemHeadData {
        private final Material material;
        private final String customTexture;
        
        public ItemHeadData(Material material, String customTexture) {
            this.material = material;
            this.customTexture = customTexture;
        }
        
        public Material getMaterial() {
            return material;
        }
        
        public String getCustomTexture() {
            return customTexture;
        }
        
        public boolean hasCustomTexture() {
            return customTexture != null && !customTexture.isEmpty() && !customTexture.equalsIgnoreCase("null");
        }
    }
}
