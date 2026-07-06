package github.nighter.smartspawner.spawner.lootgen;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootCommitTransactionSetupFailureTest {

    @Test
    void pendingInputFailureReleasesProductionClaim() {
        TestContext context = new TestContext();
        context.throwFromPendingInput = true;

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(context));

        assertEquals(1, context.releaseCalls);
    }

    @Test
    void generatedCloneFailureReleasesProductionClaim() {
        TestContext context = new TestContext();
        context.pending = new LootCommitTransaction.PendingInput(
                true, List.of(new FakeItemStack(Material.DIAMOND, 1)), List.of(), 0L);
        context.generated = List.of(new CloneThrowingItemStack(Material.DIAMOND, 1));

        assertThrows(InjectedFailure.class, () -> LootCommitTransaction.execute(context));

        assertEquals(1, context.releaseCalls);
        assertTrue(context.pending.hasClaim());
    }

    private static final class TestContext implements LootCommitTransaction.Context {
        private LootCommitTransaction.PendingInput pending = LootCommitTransaction.PendingInput.none();
        private List<ItemStack> generated = List.of();
        private boolean throwFromPendingInput;
        private int releaseCalls;

        @Override
        public LootCommitTransaction.PendingInput pendingInput() {
            if (throwFromPendingInput) throw new InjectedFailure();
            return pending;
        }

        @Override public List<ItemStack> generatedItems() { return generated; }
        @Override public long generatedExperience() { return 0L; }
        @Override public boolean ownsGeneratedBatchForRetry() { return false; }
        @Override public long spawnTime() { return 0L; }
        @Override public boolean hasActiveRouters() { return false; }
        @Override public SpawnerOutputRoutingService.RoutingOutcome route(List<ItemStack> items) {
            throw new AssertionError("router must not run during setup failure");
        }
        @Override public long currentExperience() { return 0L; }
        @Override public long maxStoredExperience() { return 0L; }
        @Override public void commitExperience(long experience, Runnable pointOfNoReturn) { }
        @Override public int usedSlots() { return 0; }
        @Override public int maxSlots() { return 0; }
        @Override public int requiredSlots(List<ItemStack> items) { return 0; }
        @Override public List<ItemStack> limitToAvailableSlots(List<ItemStack> items) { return items; }
        @Override public void insertItems(List<ItemStack> items, Runnable pointOfNoReturn) { }
        @Override public void setLastSpawnTime(long spawnTime) { }
        @Override public void acknowledgePending() { }
        @Override public void releasePending() { releaseCalls++; }
        @Override public void replacePendingWithUnrouted(List<ItemStack> items, long experience) { }
        @Override public void replacePendingWithRouteCompleted(List<ItemStack> items, long experience) { }
        @Override public void queueUnrouted(List<ItemStack> items, long experience) { }
        @Override public void queueRouteCompleted(List<ItemStack> items, long experience) { }
        @Override public void afterCommitted() { }
        @Override public void handlePostCommitFailure(Throwable throwable) { }
    }

    private static class FakeItemStack extends ItemStack {
        private final Material type;
        private int amount;

        private FakeItemStack(Material type, int amount) {
            super();
            this.type = type;
            this.amount = amount;
        }

        @Override public Material getType() { return type; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean hasItemMeta() { return false; }
        @Override public ItemStack clone() { return new FakeItemStack(type, amount); }
    }

    private static final class CloneThrowingItemStack extends FakeItemStack {
        private CloneThrowingItemStack(Material type, int amount) {
            super(type, amount);
        }

        @Override
        public ItemStack clone() {
            throw new InjectedFailure();
        }
    }

    private static final class InjectedFailure extends RuntimeException {
    }
}
