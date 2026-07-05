package github.nighter.smartspawner.api.output;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result returned by a {@link SpawnerOutputRouter}, describing the items that remain
 * unconsumed after the router ran. The result owns deep clones of its items and exposes them as an
 * unmodifiable list.
 *
 * <p>{@code null} is never a valid routing decision: {@link #passThrough(SpawnerOutputContext)} and
 * {@link #remaining(List)} reject null arguments. {@link #consumeAll()} is the only explicit
 * full-consumption factory. Because the routing service catches router failures, an accidental null
 * factory call surfaces as a caught exception and results in fail-open preservation of the current
 * remainder — never a silent full consumption.
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
     * This is the only explicit full-consumption factory.
     *
     * @return a result with an empty remainder
     */
    public static SpawnerOutputResult consumeAll() {
        return CONSUMED_ALL;
    }

    /**
     * Passes the current batch through unchanged (consumes nothing).
     *
     * @param context the context whose generated items should remain; must not be null
     * @return a result whose remainder equals the supplied batch
     * @throws NullPointerException if {@code context} is null (use {@link #consumeAll()} to consume everything)
     */
    public static SpawnerOutputResult passThrough(SpawnerOutputContext context) {
        Objects.requireNonNull(context, "context must not be null; use consumeAll() to consume everything");
        return new SpawnerOutputResult(
                SpawnerOutputContext.deepCloneUnmodifiable(context.getGeneratedItems()));
    }

    /**
     * Returns a partial remainder. Items are deep-cloned defensively.
     *
     * @param remainingItems the items that remain unconsumed; must not be null (may be empty)
     * @return a result owning deep clones of the given items
     * @throws NullPointerException if {@code remainingItems} is null (use {@link #consumeAll()} to consume everything)
     */
    public static SpawnerOutputResult remaining(List<ItemStack> remainingItems) {
        Objects.requireNonNull(remainingItems, "remainingItems must not be null; use consumeAll() to consume everything");
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
