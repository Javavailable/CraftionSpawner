package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.api.data.SpawnerDataDTO;
import github.nighter.smartspawner.api.output.SpawnerOutputContext;
import github.nighter.smartspawner.api.output.SpawnerOutputResult;
import github.nighter.smartspawner.api.output.SpawnerOutputRouterRegistryImpl;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerOutputRoutingServiceTest {

    @SuppressWarnings("deprecation")
    private NamespacedKey key(String value) {
        return new NamespacedKey("craftionspawner", value);
    }

    private SpawnerDataDTO dto() {
        return new SpawnerDataDTO(
                "spawner-1",
                new Location(null, 1, 64, 2),
                EntityType.ZOMBIE,
                null,
                1,
                1000,
                1,
                1,
                4,
                1000L,
                500L);
    }

    private SpawnerOutputRoutingService service(SpawnerOutputRouterRegistryImpl registry) {
        return new SpawnerOutputRoutingService(null, registry);
    }

    private List<ItemStack> items(Material material, int amount) {
        return List.of(new FakeItemStack(material, amount));
    }

    private List<ItemStack> items(Material material, int amount, String metaTag) {
        return List.of(new FakeItemStack(material, amount, metaTag));
    }

    private ItemStack onlyItem(SpawnerOutputRoutingService.RoutingOutcome outcome) {
        return outcome.remaining().getFirst();
    }

    @Test
    void emptySnapshotPassesThroughWithoutAttempt() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertFalse(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(Material.DIAMOND, onlyItem(outcome).getType());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void passThroughRouterConsumesNothing() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("pass"), 0, SpawnerOutputResult::passThrough);

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void throwingRouterFailsOpen() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("throwing"), 0, context -> {
            throw new IllegalStateException("boom");
        });

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void nullResultFailsOpen() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("null"), 0, context -> null);

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void consumeAllConsumesWholeBatch() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("all"), 0, context -> SpawnerOutputResult.consumeAll());

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertTrue(outcome.consumedAny());
        assertTrue(outcome.remaining().isEmpty());
    }

    @Test
    void partialConsumptionKeepsOnlyReturnedRemainder() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("partial"), 0, context -> SpawnerOutputResult.remaining(items(Material.DIAMOND, 2)));

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 5), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertTrue(outcome.consumedAny());
        assertEquals(2, onlyItem(outcome).getAmount());
    }

    @Test
    void malformedNewTypeFailsOpen() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("new-type"), 0, context -> SpawnerOutputResult.remaining(items(Material.EMERALD, 3)));

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(Material.DIAMOND, onlyItem(outcome).getType());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void malformedNewMetadataFailsOpen() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("new-meta"), 0,
                context -> SpawnerOutputResult.remaining(items(Material.DIAMOND, 3, "other-meta")));

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3, "source-meta"), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(Material.DIAMOND, onlyItem(outcome).getType());
        assertEquals(3, onlyItem(outcome).getAmount());
        assertEquals("source-meta", ((FakeItemStack) onlyItem(outcome)).metaTag);
    }

    @Test
    void inflatedQuantityFailsOpen() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        registry.register(key("inflated"), 0, context -> SpawnerOutputResult.remaining(items(Material.DIAMOND, 4)));

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void routersRunInSnapshotOrderAgainstCurrentRemainder() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        List<String> calls = new ArrayList<>();

        registry.register(key("second"), 10, context -> {
            calls.add("second:" + context.getGeneratedItems().getFirst().getAmount());
            return SpawnerOutputResult.remaining(items(Material.DIAMOND, 1));
        });
        registry.register(key("first"), 5, context -> {
            calls.add("first:" + context.getGeneratedItems().getFirst().getAmount());
            return SpawnerOutputResult.remaining(items(Material.DIAMOND, 3));
        });

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                service(registry).route(dto(), items(Material.DIAMOND, 5), registry.getSnapshot());

        assertEquals(List.of("first:5", "second:3"), calls);
        assertTrue(outcome.consumedAny());
        assertEquals(1, onlyItem(outcome).getAmount());
    }

    @Test
    void unregisterAfterHintUsesCurrentEmptySnapshot() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        SpawnerOutputRoutingService routingService = service(registry);
        NamespacedKey key = key("race");

        registry.register(key, 0, SpawnerOutputResult::passThrough);
        assertTrue(routingService.hasActiveRouters());
        registry.unregister(key);

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                routingService.route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertFalse(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(3, onlyItem(outcome).getAmount());
    }

    @Test
    void postRouterCloneFailureIsAttemptedAndTransactionDoesNotReroute() {
        SpawnerOutputRouterRegistryImpl registry = new SpawnerOutputRouterRegistryImpl();
        SpawnerOutputRoutingService routingService = service(registry);
        AtomicInteger routerCalls = new AtomicInteger();

        registry.register(key("bad-clone"), 0, context -> {
            routerCalls.incrementAndGet();
            return rawResult(List.of(new CloneThrowingItemStack(Material.DIAMOND, 1)));
        });

        SpawnerOutputRoutingService.RoutingOutcome outcome =
                routingService.route(dto(), items(Material.DIAMOND, 3), registry.getSnapshot());

        assertTrue(outcome.attempted());
        assertFalse(outcome.consumedAny());
        assertEquals(Material.DIAMOND, onlyItem(outcome).getType());
        assertEquals(3, onlyItem(outcome).getAmount());
        assertEquals(1, routerCalls.get());

        RoutingTransactionContext firstAttempt = new RoutingTransactionContext(
                routingService,
                registry,
                dto(),
                items(Material.DIAMOND, 3));
        firstAttempt.failAfterRouter = true;

        try {
            LootCommitTransaction.execute(firstAttempt);
        } catch (InjectedFailure expected) {
            // Expected: force the transaction into route-completed recovery.
        }

        assertTrue(firstAttempt.queuedRouteCompleted);
        assertEquals(3, totalAmount(firstAttempt.queuedItems));
        assertEquals(2, routerCalls.get());

        RoutingTransactionContext retry = new RoutingTransactionContext(
                routingService,
                registry,
                dto(),
                List.of());
        retry.pending = new LootCommitTransaction.PendingInput(
                true,
                List.of(),
                firstAttempt.queuedItems,
                0L);

        LootCommitTransaction.execute(retry);

        assertEquals(2, routerCalls.get());
        assertEquals(3, retry.insertedAmount);
    }

    private static SpawnerOutputResult rawResult(List<ItemStack> remaining) {
        try {
            Constructor<SpawnerOutputResult> constructor =
                    SpawnerOutputResult.class.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(remaining);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int totalAmount(List<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            total += item.getAmount();
        }
        return total;
    }

    private static class FakeItemStack extends ItemStack {
        private final Material type;
        private final String metaTag;
        private int amount;

        private FakeItemStack(Material type, int amount) {
            this(type, amount, null);
        }

        private FakeItemStack(Material type, int amount, String metaTag) {
            super();
            this.type = type;
            this.amount = amount;
            this.metaTag = metaTag;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean hasItemMeta() {
            return metaTag != null;
        }

        @Override
        public ItemMeta getItemMeta() {
            return metaTag == null ? null : fakeMeta(metaTag);
        }

        @Override
        public ItemStack asQuantity(int amount) {
            return new FakeItemStack(type, amount, metaTag);
        }

        @Override
        public boolean isSimilar(ItemStack stack) {
            return stack instanceof FakeItemStack other
                    && other.type == type
                    && Objects.equals(other.metaTag, metaTag);
        }

        @Override
        public ItemStack clone() {
            return new FakeItemStack(type, amount, metaTag);
        }

        private static ItemMeta fakeMeta(String tag) {
            return (ItemMeta) Proxy.newProxyInstance(
                    ItemMeta.class.getClassLoader(),
                    new Class<?>[]{ItemMeta.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasDisplayName" -> true;
                        case "displayName" -> Component.text(tag);
                        case "hasLore" -> false;
                        case "lore" -> null;
                        case "hasEnchants" -> false;
                        case "getEnchants" -> Map.of();
                        case "clone" -> fakeMeta(tag);
                        case "hashCode" -> tag.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeItemMeta[" + tag + "]";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return '\0';
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            return null;
        }
    }

    private static final class CloneThrowingItemStack extends FakeItemStack {
        private CloneThrowingItemStack(Material type, int amount) {
            super(type, amount);
        }

        @Override
        public ItemStack clone() {
            throw new IllegalStateException("clone failure");
        }
    }

    private static final class InjectedFailure extends RuntimeException {
    }

    private static final class RoutingTransactionContext implements LootCommitTransaction.Context {
        private final SpawnerOutputRoutingService routingService;
        private final SpawnerOutputRouterRegistryImpl registry;
        private final SpawnerDataDTO dto;
        private final List<ItemStack> generatedItems;
        private LootCommitTransaction.PendingInput pending = LootCommitTransaction.PendingInput.none();
        private boolean failAfterRouter;
        private boolean queuedRouteCompleted;
        private List<ItemStack> queuedItems = List.of();
        private int insertedAmount;

        private RoutingTransactionContext(SpawnerOutputRoutingService routingService,
                                          SpawnerOutputRouterRegistryImpl registry,
                                          SpawnerDataDTO dto,
                                          List<ItemStack> generatedItems) {
            this.routingService = routingService;
            this.registry = registry;
            this.dto = dto;
            this.generatedItems = generatedItems;
        }

        @Override
        public LootCommitTransaction.PendingInput pendingInput() {
            return pending;
        }

        @Override
        public List<ItemStack> generatedItems() {
            return generatedItems;
        }

        @Override
        public long generatedExperience() {
            return 0L;
        }

        @Override
        public boolean ownsGeneratedBatchForRetry() {
            return false;
        }

        @Override
        public long spawnTime() {
            return 1L;
        }

        @Override
        public boolean hasActiveRouters() {
            return routingService.hasActiveRouters();
        }

        @Override
        public SpawnerOutputRoutingService.RoutingOutcome route(List<ItemStack> items) {
            return routingService.route(dto, items, registry.getSnapshot());
        }

        @Override
        public long currentExperience() {
            return 0L;
        }

        @Override
        public long maxStoredExperience() {
            return 1000L;
        }

        @Override
        public void commitExperience(long experience, Runnable pointOfNoReturn) {
            pointOfNoReturn.run();
        }

        @Override
        public int usedSlots() {
            return 0;
        }

        @Override
        public int maxSlots() {
            return 64;
        }

        @Override
        public int requiredSlots(List<ItemStack> items) {
            return items.isEmpty() ? 0 : 1;
        }

        @Override
        public List<ItemStack> limitToAvailableSlots(List<ItemStack> items) {
            return items;
        }

        @Override
        public void insertItems(List<ItemStack> items, Runnable pointOfNoReturn) {
            insertedAmount += totalAmount(items);
            pointOfNoReturn.run();
        }

        @Override
        public void setLastSpawnTime(long spawnTime) {
        }

        @Override
        public void acknowledgePending() {
        }

        @Override
        public void releasePending() {
        }

        @Override
        public void replacePendingWithUnrouted(List<ItemStack> items, long experience) {
        }

        @Override
        public void replacePendingWithRouteCompleted(List<ItemStack> items, long experience) {
        }

        @Override
        public void queueUnrouted(List<ItemStack> items, long experience) {
        }

        @Override
        public void queueRouteCompleted(List<ItemStack> items, long experience) {
            queuedRouteCompleted = true;
            queuedItems = new ArrayList<>(items);
        }

        @Override
        public void afterCommitted() {
        }

        @Override
        public void handlePostCommitFailure(Throwable throwable) {
        }

        @Override
        public void afterRouter() {
            if (failAfterRouter) {
                throw new InjectedFailure();
            }
        }
    }
}
