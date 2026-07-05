package github.nighter.smartspawner.api.output;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result returned by a {@link SpawnerOutputRouter}, describing the items that remain
 * unconsumed after the router ran. The result owns deep clones of its items and exposes them as an
 * unmodifiable list. {@code null} is never a valid routing decision.
 */
public final class SpawnerOutputResult {

    private static final SpawnerOutputResult CONSUMED_ALL =
            new SpawnerOutputResult(Collections.emptyList());

    private final List<ItemStack> remainingItems;

    private SpawnerOutputResult(List<ItemStack> remainingItems) {
        this.remainingItems = remainingItems;
    }

    /**
     * Consumes the entire current batch; nothing remains for later routers or internal storage.
     *
     * @return a result with an empty remainder
     */
    public static SpawnerOutputResult consumeAll() {
        return CONSUMED_ALL;
    }

    /**
     * Passes the current batch through unchanged (consumes nothing).
     *
     * @param context the context whose generated items should remain
     * @return a result whose remainder equals the supplied batch
     */
    public static SpawnerOutputResult passThrough(SpawnerOutputContext context) {
        if (context == null) {
            return CONSUMED_ALL;
        }
        return new SpawnerOutputResult(
                SpawnerOutputContext.deepCloneUnmodifiable(context.getGeneratedItems()));
    }

    /**
     * Returns a partial remainder. Items are deep-cloned defensively.
     *
     * @param remainingItems the items that remain unconsumed (may be null/empty for full consumption)
     * @return a result owning deep clones of the given items
     */
    public static SpawnerOutputResult remaining(List<ItemStack> remainingItems) {
        return new SpawnerOutputResult(
                SpawnerOutputContext.deepCloneUnmodifiable(remainingItems));
    }

    /**
     * @return an unmodifiable, deeply cloned list of the items that remain unconsumed
     */
    public List<ItemStack> getRemainingItems() {
        return remainingItems;
    }
}
