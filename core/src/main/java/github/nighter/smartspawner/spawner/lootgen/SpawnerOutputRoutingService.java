package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.SmartSpawnerAPIImpl;
import github.nighter.smartspawner.api.data.SpawnerDataDTO;
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
 * routing behavior is identical. Deep-clones the batch and every router input/output at the API
 * boundary, runs routers in the registry's deterministic snapshot order, never routes experience,
 * and is fail-open (a throwing/malformed router is ignored for the batch with a bounded warning and
 * the full current remainder is preserved).
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
     * @return {@code true} if at least one router is currently registered. This is a hint only; the
     * authoritative per-pass information is {@link RoutingOutcome#attempted()} from {@link #route}.
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
        private final boolean attempted;

        RoutingOutcome(List<ItemStack> remaining, boolean consumedAny, boolean attempted) {
            this.remaining = remaining;
            this.consumedAny = consumedAny;
            this.attempted = attempted;
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

        /**
         * @return {@code true} only when the generated remainder was non-empty, the exact immutable
         * snapshot used for this pass contained at least one router, and at least one router
         * invocation was actually attempted. Derived from the same snapshot used for routing (never
         * from a separate {@code hasActiveRouters()} read).
         */
        public boolean attempted() {
            return attempted;
        }
    }

    /**
     * Routes the given newly generated items. Never mutates the supplied list.
     *
     * @param spawner   the owning spawner (used only to build a read-only DTO)
     * @param generated the newly generated items for this cycle
     * @return the unconsumed remainder, whether any external consumption occurred, and whether a
     *         router pass was actually attempted against a non-empty snapshot
     */
    public RoutingOutcome route(SpawnerData spawner, List<ItemStack> generated) {
        return route(SmartSpawnerAPIImpl.convertToDTO(spawner), generated, registry.getSnapshot());
    }

    /*
     * Package-private pure-routing seam for deterministic unit tests. Callers supply the exact
     * immutable router snapshot to verify unregister/register races without constructing a plugin.
     */
    RoutingOutcome route(SpawnerDataDTO spawnerSnapshot, List<ItemStack> generated, List<RegisteredRouter> snapshot) {
        List<RegisteredRouter> effectiveSnapshot = snapshot != null ? snapshot : List.of();
        List<ItemStack> remaining = deepClone(generated);

        if (effectiveSnapshot.isEmpty() || remaining.isEmpty()) {
            return new RoutingOutcome(remaining, false, false);
        }

        final long originalTotal = totalQuantity(remaining);
        boolean attempted = false;

        for (RegisteredRouter entry : effectiveSnapshot) {
            if (remaining.isEmpty()) {
                break;
            }

            // Immutable context with a deep clone of the CURRENT remaining batch only.
            SpawnerOutputContext context = new SpawnerOutputContext(
                    spawnerSnapshot,
                    remaining);

            // A router invocation is now being attempted against this non-empty snapshot.
            attempted = true;

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

            try {
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
            } catch (Throwable t) {
                warn(entry.getKey(), "returned an invalid result ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                continue; // fail-open after attempted router invocation: keep current remainder
            }
        }

        boolean consumedAny = false;
        try {
            consumedAny = totalQuantity(remaining) < originalTotal;
        } catch (Throwable ignored) {
            // An exception here would be after a router-attempt boundary. Preserve the current
            // remainder and keep attempted=true rather than allowing the caller to replay routers.
        }
        return new RoutingOutcome(remaining, consumedAny, attempted);
    }

    private void warn(NamespacedKey key, String detail) {
        String k = key.toString();
        long now = System.currentTimeMillis();
        Long last = lastWarnByKey.get(k);
        if (last != null && now - last < WARN_INTERVAL_MS) {
            return; // rate-limited: avoid one warning per generation tick
        }
        lastWarnByKey.put(k, now);
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().warning("Output router '" + k + "' " + detail
                    + "; ignoring it for this batch (fail-open, generated items preserved).");
        }
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
