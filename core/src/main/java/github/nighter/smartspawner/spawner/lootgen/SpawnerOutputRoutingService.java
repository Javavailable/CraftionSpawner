package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.SmartSpawnerAPIImpl;
import github.nighter.smartspawner.api.output.SpawnerOutputContext;
import github.nighter.smartspawner.api.output.SpawnerOutputResult;
import github.nighter.smartspawner.api.output.SpawnerOutputRouterRegistryImpl;
import github.nighter.smartspawner.api.output.SpawnerOutputRouterRegistryImpl.RegisteredRouter;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal service that routes newly generated spawner item output through registered external
 * routers before the unconsumed remainder is committed to internal virtual storage.
 *
 * <p>Used by BOTH loot-commit paths in {@link SpawnerLootGenerator} (normal and pre-generated) so
 * routing behavior is identical. This service:
 * <ul>
 *   <li>Deep-clones the batch and every router input/output at the API boundary.</li>
 *   <li>Runs routers in the registry's deterministic snapshot order.</li>
 *   <li>Gives each router only the current remaining batch.</li>
 *   <li>Never routes experience, manual actions, or already-stored contents.</li>
 *   <li>Is fail-open: a throwing/malformed router is ignored for the batch (bounded warning) and
 *       the full current remainder is preserved.</li>
 * </ul>
 */
public class SpawnerOutputRoutingService {

    private static final long WARN_INTERVAL_MS = 60_000L;

    private final SmartSpawner plugin;
    private final SpawnerOutputRouterRegistryImpl registry;
    private final ConcurrentHashMap<String, Long> lastWarnByKey = new ConcurrentHashMap<>();

    public SpawnerOutputRoutingService(SmartSpawner plugin, SpawnerOutputRouterRegistryImpl registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * @return {@code true} if at least one router is registered
     */
    public boolean hasActiveRouters() {
        return registry.hasRouters();
    }

    /**
     * Immutable outcome of a routing pass.
     */
    public static final class RoutingOutcome {
        private final List<ItemStack> remaining;
        private final boolean consumedAny;

        RoutingOutcome(List<ItemStack> remaining, boolean consumedAny) {
            this.remaining = remaining;
            this.consumedAny = consumedAny;
        }

        /**
         * @return an owned, mutable list of the unconsumed remainder (safe to insert internally)
         */
        public List<ItemStack> remaining() {
            return remaining;
        }

        /**
         * @return {@code true} if any positive item quantity was consumed externally
         */
        public boolean consumedAny() {
            return consumedAny;
        }
    }

    /**
     * Routes the given newly generated items. Never mutates the supplied list.
     *
     * @param spawner   the owning spawner (used only to build a read-only DTO)
     * @param generated the newly generated items for this cycle
     * @return the unconsumed remainder and whether any external consumption occurred
     */
    public RoutingOutcome route(SpawnerData spawner, List<ItemStack> generated) {
        List<RegisteredRouter> snapshot = registry.getSnapshot();
        List<ItemStack> remaining = deepClone(generated);

        if (snapshot.isEmpty() || remaining.isEmpty()) {
            return new RoutingOutcome(remaining, false);
        }

        final long originalTotal = totalQuantity(remaining);

        for (RegisteredRouter entry : snapshot) {
            if (remaining.isEmpty()) {
                break;
            }

            // Immutable context with a deep clone of the CURRENT remaining batch only.
            SpawnerOutputContext context = new SpawnerOutputContext(
                    SmartSpawnerAPIImpl.convertToDTO(spawner),
                    remaining);

            SpawnerOutputResult result;
            try {
                result = entry.getRouter().route(context);
            } catch (Throwable t) {
                warn(entry.getKey(), "threw an exception (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                continue; // fail-open: keep current remainder
            }

            if (result == null) {
                warn(entry.getKey(), "returned null");
                continue;
            }
            List<ItemStack> returned = result.getRemainingItems();
            if (returned == null) {
                warn(entry.getKey(), "returned a null remaining list");
                continue;
            }

            // Defensive validation against the input batch (multiset by item signature).
            Map<ItemSignature, Long> inputCounts = countBySignature(remaining);
            Map<ItemSignature, Long> outputCounts = new HashMap<>();
            boolean valid = true;
            for (ItemStack item : returned) {
                if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                    warn(entry.getKey(), "returned a null, AIR, or non-positive item");
                    valid = false;
                    break;
                }
                ItemSignature sig = VirtualInventory.getSignature(item);
                if (!inputCounts.containsKey(sig)) {
                    warn(entry.getKey(), "returned an item type/meta not present in its input");
                    valid = false;
                    break;
                }
                outputCounts.merge(sig, (long) item.getAmount(), Long::sum);
            }
            if (valid) {
                for (Map.Entry<ItemSignature, Long> e : outputCounts.entrySet()) {
                    if (e.getValue() > inputCounts.getOrDefault(e.getKey(), 0L)) {
                        warn(entry.getKey(), "returned a greater quantity than it received");
                        valid = false;
                        break;
                    }
                }
            }
            if (!valid) {
                continue; // fail-open: keep full current remainder
            }

            // Accept the router's decision; adopt an owned deep clone of the remainder.
            remaining = deepClone(returned);
        }

        boolean consumedAny = totalQuantity(remaining) < originalTotal;
        return new RoutingOutcome(remaining, consumedAny);
    }

    private void warn(NamespacedKey key, String detail) {
        String k = key.toString();
        long now = System.currentTimeMillis();
        Long last = lastWarnByKey.get(k);
        if (last != null && now - last < WARN_INTERVAL_MS) {
            return; // rate-limited: avoid one warning per generation tick
        }
        lastWarnByKey.put(k, now);
        plugin.getLogger().warning("Output router '" + k + "' " + detail
                + "; ignoring it for this batch (fail-open, generated items preserved).");
    }

    private static List<ItemStack> deepClone(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items == null ? 0 : items.size());
        if (items != null) {
            for (ItemStack item : items) {
                if (item != null) {
                    copy.add(item.clone());
                }
            }
        }
        return copy;
    }

    private static long totalQuantity(List<ItemStack> items) {
        long total = 0L;
        for (ItemStack item : items) {
            if (item != null && item.getAmount() > 0) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static Map<ItemSignature, Long> countBySignature(List<ItemStack> items) {
        Map<ItemSignature, Long> counts = new HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) {
                continue;
            }
            counts.merge(VirtualInventory.getSignature(item), (long) item.getAmount(), Long::sum);
        }
        return counts;
    }
}
