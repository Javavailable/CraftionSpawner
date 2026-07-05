package github.nighter.smartspawner.spawner.lootgen;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootCommitTransactionTest {

    @Test
    void exceptionBeforeRouterReleasesClaim() {
        FakeContext context = new FakeContext();
        context.pending = pending(items(5), List.of(), 0);
        context.throwPoint = ThrowPoint.BEFORE_ROUTER;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(context));

        assertTrue(context.released);
        assertFalse(context.acknowledged);
        assertEquals(0, context.routerCalls);
        assertFalse(context.replacedRouteCompleted);
        assertFalse(context.replacedUnrouted);
    }

    @Test
    void exceptionAfterRouterAttemptDoesNotInvokeRouterAgain() {
        FakeContext firstAttempt = new FakeContext();
        firstAttempt.pending = pending(items(5), List.of(), 0);
        firstAttempt.routerRemainder = items(2);
        firstAttempt.throwPoint = ThrowPoint.AFTER_ROUTER;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(firstAttempt));

        assertFalse(firstAttempt.released);
        assertTrue(firstAttempt.replacedRouteCompleted);
        assertEquals(2, totalAmount(firstAttempt.replacementItems));

        FakeContext retry = new FakeContext();
        retry.pending = pending(List.of(), firstAttempt.replacementItems, firstAttempt.replacementExperience);

        LootCommitTransaction.Result result = LootCommitTransaction.execute(retry);

        assertEquals(LootCommitTransaction.Result.COMMITTED, result);
        assertEquals(0, retry.routerCalls);
        assertEquals(2, retry.insertedAmount);
        assertTrue(retry.acknowledged);
    }

    @Test
    void exceptionAfterXpMutationDoesNotAddXpTwice() {
        FakeContext firstAttempt = new FakeContext();
        firstAttempt.pending = pending(items(5), List.of(), 10);
        firstAttempt.routerRemainder = items(2);
        firstAttempt.throwPoint = ThrowPoint.AFTER_XP;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(firstAttempt));

        assertEquals(10, firstAttempt.currentExperience);
        assertTrue(firstAttempt.replacedRouteCompleted);
        assertEquals(0, firstAttempt.replacementExperience);

        FakeContext retry = new FakeContext();
        retry.currentExperience = firstAttempt.currentExperience;
        retry.pending = pending(List.of(), firstAttempt.replacementItems, firstAttempt.replacementExperience);

        LootCommitTransaction.execute(retry);

        assertEquals(10, retry.currentExperience);
        assertEquals(0, retry.routerCalls);
        assertEquals(2, retry.insertedAmount);
    }

    @Test
    void exceptionAfterInternalInsertionDoesNotInsertItemsTwice() {
        FakeContext context = new FakeContext();
        context.pending = pending(List.of(), items(4), 0);
        context.throwPoint = ThrowPoint.AFTER_INSERT;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(context));

        assertEquals(4, context.insertedAmount);
        assertTrue(context.acknowledged);
        assertFalse(context.released);
        assertFalse(context.replacedRouteCompleted);
        assertFalse(context.replacedUnrouted);
    }

    @Test
    void postCommitNotificationFailureKeepsClaimAcknowledged() {
        FakeContext context = new FakeContext();
        context.pending = pending(items(3), List.of(), 5);
        context.routerRemainder = items(1);
        context.throwPoint = ThrowPoint.AFTER_COMMIT;

        LootCommitTransaction.Result result = LootCommitTransaction.execute(context);

        assertEquals(LootCommitTransaction.Result.COMMITTED, result);
        assertTrue(context.acknowledged);
        assertFalse(context.released);
        assertTrue(context.postCommitFailureHandled);
    }

    @Test
    void genuinePreSideEffectFailureKeepsPendingBatchAvailable() {
        FakeContext context = new FakeContext();
        context.routersActive = false;
        context.pending = pending(List.of(), List.of(), 10);
        context.throwPoint = ThrowPoint.BEFORE_XP;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(context));

        assertTrue(context.released);
        assertFalse(context.acknowledged);
        assertEquals(0, context.currentExperience);
        assertFalse(context.replacedRouteCompleted);
        assertFalse(context.replacedUnrouted);
    }

    private static LootCommitTransaction.PendingInput pending(List<ItemStack> unrouted,
                                                              List<ItemStack> routeCompleted,
                                                              long experience) {
        return new LootCommitTransaction.PendingInput(true, unrouted, routeCompleted, experience);
    }

    private static List<ItemStack> items(int amount) {
        return List.of(new FakeItemStack(Material.DIAMOND, amount));
    }

    private static int totalAmount(List<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            total += item.getAmount();
        }
        return total;
    }

    private enum ThrowPoint {
        NONE,
        BEFORE_ROUTER,
        AFTER_ROUTER,
        BEFORE_XP,
        AFTER_XP,
        AFTER_INSERT,
        AFTER_COMMIT
    }

    private static final class FakeContext implements LootCommitTransaction.Context {
        private LootCommitTransaction.PendingInput pending = LootCommitTransaction.PendingInput.none();
        private List<ItemStack> generatedItems = List.of();
        private List<ItemStack> routerRemainder = List.of();
        private long generatedExperience;
        private long currentExperience;
        private long maxExperience = 1000L;
        private boolean routersActive = true;
        private ThrowPoint throwPoint = ThrowPoint.NONE;
        private int routerCalls;
        private int insertedAmount;
        private boolean acknowledged;
        private boolean released;
        private boolean replacedRouteCompleted;
        private boolean replacedUnrouted;
        private List<ItemStack> replacementItems = List.of();
        private long replacementExperience;
        private boolean postCommitFailureHandled;

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
            return generatedExperience;
        }

        @Override
        public long spawnTime() {
            return 1234L;
        }

        @Override
        public boolean hasActiveRouters() {
            return routersActive;
        }

        @Override
        public SpawnerOutputRoutingService.RoutingOutcome route(List<ItemStack> items) {
            routerCalls++;
            return new SpawnerOutputRoutingService.RoutingOutcome(
                    deepClone(routerRemainder),
                    totalAmount(routerRemainder) < totalAmount(items),
                    true);
        }

        @Override
        public long currentExperience() {
            return currentExperience;
        }

        @Override
        public long maxStoredExperience() {
            return maxExperience;
        }

        @Override
        public void setExperience(long experience) {
            currentExperience = experience;
        }

        @Override
        public int usedSlots() {
            return insertedAmount == 0 ? 0 : 1;
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
        public void insertItems(List<ItemStack> items) {
            insertedAmount += totalAmount(items);
        }

        @Override
        public void setLastSpawnTime(long spawnTime) {
        }

        @Override
        public void acknowledgePending() {
            acknowledged = true;
        }

        @Override
        public void releasePending() {
            released = true;
        }

        @Override
        public void replacePendingWithUnrouted(List<ItemStack> items, long experience) {
            replacedUnrouted = true;
            replacementItems = deepClone(items);
            replacementExperience = experience;
        }

        @Override
        public void replacePendingWithRouteCompleted(List<ItemStack> items, long experience) {
            replacedRouteCompleted = true;
            replacementItems = deepClone(items);
            replacementExperience = experience;
        }

        @Override
        public void queueUnrouted(List<ItemStack> items, long experience) {
            replacePendingWithUnrouted(items, experience);
        }

        @Override
        public void queueRouteCompleted(List<ItemStack> items, long experience) {
            replacePendingWithRouteCompleted(items, experience);
        }

        @Override
        public void afterCommitted() {
            throwIf(ThrowPoint.AFTER_COMMIT);
        }

        @Override
        public void handlePostCommitFailure(Throwable throwable) {
            postCommitFailureHandled = true;
        }

        @Override
        public void beforeRouter() {
            throwIf(ThrowPoint.BEFORE_ROUTER);
        }

        @Override
        public void afterRouter() {
            throwIf(ThrowPoint.AFTER_ROUTER);
        }

        @Override
        public void beforeExperienceCommit() {
            throwIf(ThrowPoint.BEFORE_XP);
        }

        @Override
        public void afterExperienceCommit() {
            throwIf(ThrowPoint.AFTER_XP);
        }

        @Override
        public void afterInternalInsertion() {
            throwIf(ThrowPoint.AFTER_INSERT);
        }

        private void throwIf(ThrowPoint expected) {
            if (throwPoint == expected) {
                throw new InjectedFailure();
            }
        }
    }

    private static List<ItemStack> deepClone(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copy.add(item.clone());
        }
        return copy;
    }

    private static final class InjectedFailure extends RuntimeException {
    }

    private static final class FakeItemStack extends ItemStack {
        private final Material type;
        private int amount;

        private FakeItemStack(Material type, int amount) {
            super();
            this.type = type;
            this.amount = amount;
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
            return false;
        }

        @Override
        public ItemMeta getItemMeta() {
            return null;
        }

        @Override
        public ItemStack asQuantity(int amount) {
            return new FakeItemStack(type, amount);
        }

        @Override
        public boolean isSimilar(ItemStack stack) {
            return stack != null && stack.getType() == type && !stack.hasItemMeta();
        }

        @Override
        public ItemStack clone() {
            return new FakeItemStack(type, amount);
        }
    }
}
