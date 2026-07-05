package github.nighter.smartspawner.api.output;

import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link SpawnerOutputRouterRegistry}.
 *
 * <p>Maintains a deterministically ordered, immutable snapshot of registered routers that the
 * internal routing service reads once per routing pass. Registration/unregistration rebuilds the
 * snapshot atomically, so changes never corrupt an in-flight route.
 */
public class SpawnerOutputRouterRegistryImpl implements SpawnerOutputRouterRegistry {

    /**
     * An immutable registered router entry.
     */
    public static final class RegisteredRouter {
        private final NamespacedKey key;
        private final int order;
        private final SpawnerOutputRouter router;

        RegisteredRouter(NamespacedKey key, int order, SpawnerOutputRouter router) {
            this.key = key;
            this.order = order;
            this.router = router;
        }

        public NamespacedKey getKey() {
            return key;
        }

        public int getOrder() {
            return order;
        }

        public SpawnerOutputRouter getRouter() {
            return router;
        }
    }

    private final ConcurrentHashMap<NamespacedKey, RegisteredRouter> routers = new ConcurrentHashMap<>();
    private volatile List<RegisteredRouter> snapshot = Collections.emptyList();

    @Override
    public boolean register(NamespacedKey key, int order, SpawnerOutputRouter router) {
        if (key == null || router == null) {
            return false;
        }
        RegisteredRouter entry = new RegisteredRouter(key, order, router);
        // Duplicate keys never silently replace an existing router.
        if (routers.putIfAbsent(key, entry) != null) {
            return false;
        }
        rebuildSnapshot();
        return true;
    }

    @Override
    public boolean unregister(NamespacedKey key) {
        if (key == null) {
            return false;
        }
        boolean removed = routers.remove(key) != null;
        if (removed) {
            rebuildSnapshot();
        }
        return removed;
    }

    @Override
    public boolean isRegistered(NamespacedKey key) {
        return key != null && routers.containsKey(key);
    }

    @Override
    public Set<NamespacedKey> getRegisteredKeys() {
        return Collections.unmodifiableSet(new HashSet<>(routers.keySet()));
    }

    /**
     * @return {@code true} if at least one router is registered
     */
    public boolean hasRouters() {
        return !snapshot.isEmpty();
    }

    /**
     * @return the current immutable, deterministically ordered router snapshot
     */
    public List<RegisteredRouter> getSnapshot() {
        return snapshot;
    }

    private synchronized void rebuildSnapshot() {
        List<RegisteredRouter> list = new ArrayList<>(routers.values());
        list.sort(Comparator.comparingInt(RegisteredRouter::getOrder)
                .thenComparing(entry -> entry.getKey().toString()));
        snapshot = Collections.unmodifiableList(list);
    }
}
