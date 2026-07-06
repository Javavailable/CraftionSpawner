package github.nighter.smartspawner.api.data;

import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Data Transfer Object containing read-only spawner information.
 * This class provides read-only access to spawner data through the API.
 * To modify spawner properties, use {@link SpawnerDataModifier} obtained from
 * {@link github.nighter.smartspawner.api.SmartSpawnerAPI#getSpawnerModifier(String)}.
 */
@Getter
public class SpawnerDataDTO {

    private final String spawnerId;
    // Location is mutable. Never expose the internal instance: the Lombok getter is disabled and
    // getLocation() returns a defensive clone, so external routers/consumers cannot corrupt the real
    // spawner location (which the location indexes still rely on).
    @Getter(AccessLevel.NONE)
    private final Location location;
    private final EntityType entityType;
    private final Material spawnedItemMaterial;
    private final int stackSize;
    private final int maxStackSize;
    private final int baseMaxStoragePages;
    private final int baseMinMobs;
    private final int baseMaxMobs;
    private final long baseMaxStoredExp;
    private final long baseSpawnerDelay;

    /**
     * Creates a new spawner data DTO. The supplied {@code location} is defensively cloned; the DTO
     * never retains the caller's mutable Location instance.
     *
     * @param spawnerId the unique spawner ID
     * @param location the spawner location (defensively cloned)
     * @param entityType the entity type
     * @param spawnedItemMaterial the spawned item material for item spawners
     * @param stackSize the current stack size (read-only)
     * @param maxStackSize the maximum stack size
     * @param baseMaxStoragePages the base storage pages
     * @param baseMinMobs the base minimum mobs
     * @param baseMaxMobs the base maximum mobs
     * @param baseMaxStoredExp the base maximum stored experience
     * @param baseSpawnerDelay the base spawner delay in ticks
     */
    public SpawnerDataDTO(String spawnerId, Location location, EntityType entityType,
                          Material spawnedItemMaterial, int stackSize, int maxStackSize,
                          int baseMaxStoragePages, int baseMinMobs, int baseMaxMobs,
                          long baseMaxStoredExp, long baseSpawnerDelay) {
        this.spawnerId = spawnerId;
        // Defensive copy: do not retain the caller's mutable Location instance.
        this.location = (location != null) ? location.clone() : null;
        this.entityType = entityType;
        this.spawnedItemMaterial = spawnedItemMaterial;
        this.stackSize = stackSize;
        this.maxStackSize = maxStackSize;
        this.baseMaxStoragePages = baseMaxStoragePages;
        this.baseMinMobs = baseMinMobs;
        this.baseMaxMobs = baseMaxMobs;
        this.baseMaxStoredExp = baseMaxStoredExp;
        this.baseSpawnerDelay = baseSpawnerDelay;
    }

    /**
     * Returns a defensive clone of the spawner location. Mutating the returned instance never affects
     * the DTO's internal Location or the real internal spawner location.
     *
     * @return a fresh clone of the location, or null if none
     */
    public Location getLocation() {
        return (location != null) ? location.clone() : null;
    }

    /**
     * Checks if this is an item spawner.
     *
     * @return true if spawner spawns items instead of entities
     */
    public boolean isItemSpawner() {
        return entityType == EntityType.ITEM && spawnedItemMaterial != null;
    }
}
