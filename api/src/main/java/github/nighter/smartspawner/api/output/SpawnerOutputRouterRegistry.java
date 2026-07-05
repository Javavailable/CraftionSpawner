package github.nighter.smartspawner.api.output;

import org.bukkit.NamespacedKey;

import java.util.Set;

/**
 * Registry for external plugins to register {@link SpawnerOutputRouter} instances.
 * Obtain an instance via {@link github.nighter.smartspawner.api.SmartSpawnerAPI#getOutputRouterRegistry()}.
 *
 * <p>Routers execute in ascending {@code order} (lower first); ties are resolved deterministically
 * by the registration key's string form. All operations are thread-safe, and an active routing pass
 * uses a stable snapshot so registration changes cannot corrupt it.
 */
public interface SpawnerOutputRouterRegistry {

    /**
     * Registers a router under a collision-safe key.
     *
     * @param key   unique registration key (e.g. {@code new NamespacedKey(plugin, "my-router")})
     * @param order execution order; lower values run first, ties broken by key string
     * @param router the router implementation
     * @return {@code true} if registered; {@code false} if the key was null, the router was null,
     *         or a router is already registered under this key (existing router is never replaced)
     */
    boolean register(NamespacedKey key, int order, SpawnerOutputRouter router);

    /**
     * Unregisters the router associated with the given key.
     *
     * @param key the registration key
     * @return {@code true} if a router existed under this key and was removed
     */
    boolean unregister(NamespacedKey key);

    /**
     * @param key the registration key
     * @return {@code true} if a router is registered under this key
     */
    boolean isRegistered(NamespacedKey key);

    /**
     * @return an immutable snapshot of all registered router keys
     */
    Set<NamespacedKey> getRegisteredKeys();
}
