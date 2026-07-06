package github.nighter.smartspawner.api.output;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for registry collision-safety, deterministic ordering, and immutability. */
class SpawnerOutputRouterRegistryImplTest {

    @SuppressWarnings("deprecation")
    private NamespacedKey key(String value) {
        return new NamespacedKey("craftionspawner", value);
    }

    @Test
    void duplicateKeyDoesNotReplace() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        NamespacedKey k = key("a");
        SpawnerOutputRouter first = ctx -> SpawnerOutputResult.consumeAll();
        SpawnerOutputRouter second = ctx -> SpawnerOutputResult.consumeAll();

        assertTrue(registry.register(k, 0, first));
        assertFalse(registry.register(k, 0, second));
        assertTrue(registry.isRegistered(k));
        assertEquals(1, registry.getRegisteredKeys().size());
        assertSame(first, registry.getSnapshot().get(0).getRouter());
    }

    @Test
    void deterministicOrderLowerIntegerFirstThenKeyString() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        NamespacedKey b = key("b");
        NamespacedKey a = key("a");
        NamespacedKey c = key("c");

        registry.register(b, 10, ctx -> SpawnerOutputResult.consumeAll());
        registry.register(a, 5, ctx -> SpawnerOutputResult.consumeAll());
        registry.register(c, 5, ctx -> SpawnerOutputResult.consumeAll());

        List<SpawnerOutputRouterRegistryImpl.RegisteredRouter> snapshot = registry.getSnapshot();
        assertEquals(a, snapshot.get(0).getKey()); // order 5, key ...a
        assertEquals(c, snapshot.get(1).getKey()); // order 5, key ...c
        assertEquals(b, snapshot.get(2).getKey()); // order 10
    }

    @Test
    void unregisterReportsWhetherRouterExisted() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        NamespacedKey k = key("a");
        assertFalse(registry.unregister(k));
        registry.register(k, 0, ctx -> SpawnerOutputResult.consumeAll());
        assertTrue(registry.unregister(k));
        assertFalse(registry.isRegistered(k));
        assertTrue(registry.getSnapshot().isEmpty());
    }

    @Test
    void registeredKeysAreImmutable() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("a"), 0, ctx -> SpawnerOutputResult.consumeAll());
        assertThrows(UnsupportedOperationException.class, () -> registry.getRegisteredKeys().add(key("z")));
    }
}
