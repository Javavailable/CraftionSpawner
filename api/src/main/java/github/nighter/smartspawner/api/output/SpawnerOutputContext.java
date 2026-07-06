package github.nighter.smartspawner.api.output;

import github.nighter.smartspawner.api.data.SpawnerDataDTO;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable context supplied to a {@link SpawnerOutputRouter}.
 *
 * <p>Exposes only a read-only {@link SpawnerDataDTO} and a deeply cloned, unmodifiable snapshot of
 * the items currently available to the router. Internal {@code SpawnerData}, {@code VirtualInventory},
 * locks, indexes and storage handlers are intentionally never exposed. Stored experience is not part
 * of this contract in S3.
 */
public final class SpawnerOutputContext {

    private final SpawnerDataDTO spawner;
    private final List<ItemStack> generatedItems;

    /**
     * @param spawner read-only spawner snapshot
     * @param generatedItems the items available to the router; deeply cloned defensively
     */
    public SpawnerOutputContext(SpawnerDataDTO spawner, List<ItemStack> generatedItems) {
        this.spawner = spawner;
        this.generatedItems = deepCloneUnmodifiable(generatedItems);
    }

    /**
     * @return the read-only spawner snapshot
     */
    public SpawnerDataDTO getSpawner() {
        return spawner;
    }

    /**
     * @return an unmodifiable, deeply cloned list of the items currently available to the router
     */
    public List<ItemStack> getGeneratedItems() {
        return generatedItems;
    }

    /**
     * Deep-clones the given items into an unmodifiable list, skipping null entries.
     *
     * @param items source items (may be null)
     * @return an unmodifiable list of clones
     */
    static List<ItemStack> deepCloneUnmodifiable(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null) {
                copy.add(item.clone());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
