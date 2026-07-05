package github.nighter.smartspawner.spawner.lootgen;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LootCommitTransaction {

    enum Result {
        COMMITTED,
        NOOP
    }

    record PendingInput(boolean hasClaim, List<ItemStack> unroutedItems,
                        List<ItemStack> routeCompletedItems, long experience) {
        static PendingInput none() {
            return new PendingInput(false, Collections.emptyList(), Collections.emptyList(), 0L);
        }

        PendingInput {
            unroutedItems = deepCloneItems(unroutedItems);
            routeCompletedItems = deepCloneItems(routeCompletedItems);
            experience = Math.max(0L, experience);
        }
    }

    interface Context {
        PendingInput pendingInput();

        List<ItemStack> generatedItems();

        long generatedExperience();

        long spawnTime();

        boolean hasActiveRouters();

        SpawnerOutputRoutingService.RoutingOutcome route(List<ItemStack> items);

        long currentExperience();

        long maxStoredExperience();

        void setExperience(long experience);

        int usedSlots();

        int maxSlots();

        int requiredSlots(List<ItemStack> items);

        List<ItemStack> limitToAvailableSlots(List<ItemStack> items);

        void insertItems(List<ItemStack> items);

        void setLastSpawnTime(long spawnTime);

        void acknowledgePending();

        void releasePending();

        void replacePendingWithUnrouted(List<ItemStack> items, long experience);

        void replacePendingWithRouteCompleted(List<ItemStack> items, long experience);

        void queueUnrouted(List<ItemStack> items, long experience);

        void queueRouteCompleted(List<ItemStack> items, long experience);

        void afterCommitted();

        void handlePostCommitFailure(Throwable throwable);

        default void beforeRouter() {
        }

        default void afterRouter() {
        }

        default void beforeExperienceCommit() {
        }

        default void afterExperienceCommit() {
        }

        default void afterInternalInsertion() {
        }

        default void afterTimerAdvance() {
        }
    }

    private LootCommitTransaction() {
    }

    static Result execute(Context context) {
        PendingInput pending = context.pendingInput();
        boolean pendingFinalized = false;
        boolean routeAttempted = false;
        boolean existingRouteCompletedInput = !pending.routeCompletedItems().isEmpty();
        boolean xpCommitted = false;
        boolean itemsInserted = false;
        boolean timerAdvanced = false;

        long effectiveExperience = saturatedAdd(context.generatedExperience(), pending.experience());
        List<ItemStack> unroutedItems = deepCloneItems(context.generatedItems());
        unroutedItems.addAll(pending.unroutedItems());
        List<ItemStack> itemsForInternalCommit = deepCloneItems(pending.routeCompletedItems());
        boolean externallyConsumed = false;

        try {
            if (!unroutedItems.isEmpty()) {
                List<ItemStack> remainder = unroutedItems;
                if (context.hasActiveRouters()) {
                    context.beforeRouter();
                    SpawnerOutputRoutingService.RoutingOutcome outcome = context.route(unroutedItems);
                    remainder = deepCloneItems(outcome.remaining());
                    externallyConsumed = outcome.consumedAny();
                    routeAttempted = outcome.attempted();
                }
                itemsForInternalCommit.addAll(deepCloneItems(remainder));
                if (routeAttempted) {
                    context.afterRouter();
                }
            }

            boolean xpChanged = false;
            if (effectiveExperience > 0 && context.currentExperience() < context.maxStoredExperience()) {
                long currentExp = context.currentExperience();
                long maxExp = context.maxStoredExperience();
                long newExp = Math.min(saturatedAdd(currentExp, effectiveExperience), maxExp);
                if (newExp != currentExp) {
                    context.beforeExperienceCommit();
                    context.setExperience(newExp);
                    xpCommitted = true;
                    xpChanged = true;
                    context.afterExperienceCommit();
                }
            }

            if (!itemsForInternalCommit.isEmpty() && context.usedSlots() < context.maxSlots()) {
                List<ItemStack> itemsToAdd = new ArrayList<>(itemsForInternalCommit);
                if (context.requiredSlots(itemsToAdd) > context.maxSlots()) {
                    itemsToAdd = context.limitToAvailableSlots(itemsToAdd);
                }
                if (!itemsToAdd.isEmpty()) {
                    context.insertItems(itemsToAdd);
                    itemsInserted = true;
                    context.afterInternalInsertion();
                }
            }

            boolean advanceCycle = xpChanged || itemsInserted || externallyConsumed || routeAttempted;
            if (!advanceCycle) {
                if (pending.hasClaim()) {
                    context.releasePending();
                    pendingFinalized = true;
                }
                return Result.NOOP;
            }

            context.setLastSpawnTime(context.spawnTime());
            timerAdvanced = true;
            context.afterTimerAdvance();

            if (pending.hasClaim()) {
                context.acknowledgePending();
                pendingFinalized = true;
            }

            try {
                context.afterCommitted();
            } catch (Throwable t) {
                context.handlePostCommitFailure(t);
            }

            return Result.COMMITTED;
        } catch (Throwable t) {
            recoverPendingClaim(
                    context,
                    pending,
                    pendingFinalized,
                    routeAttempted,
                    existingRouteCompletedInput,
                    xpCommitted,
                    itemsInserted,
                    timerAdvanced,
                    itemsForInternalCommit,
                    effectiveExperience);
            throw t;
        }
    }

    private static void recoverPendingClaim(Context context, PendingInput pending, boolean pendingFinalized,
                                            boolean routeAttempted, boolean existingRouteCompletedInput,
                                            boolean xpCommitted, boolean itemsInserted, boolean timerAdvanced,
                                            List<ItemStack> itemsForInternalCommit, long effectiveExperience) {
        boolean irreversible = routeAttempted || xpCommitted || itemsInserted || timerAdvanced;
        if (!irreversible) {
            if (pending.hasClaim() && !pendingFinalized) {
                context.releasePending();
            }
            return;
        }

        if (itemsInserted || timerAdvanced) {
            if (pending.hasClaim() && !pendingFinalized) {
                context.acknowledgePending();
            }
            return;
        }

        long recoveryExperience = xpCommitted ? 0L : effectiveExperience;
        boolean routeCompletedRecovery = routeAttempted || existingRouteCompletedInput;

        if (pending.hasClaim() && !pendingFinalized) {
            if (routeCompletedRecovery) {
                context.replacePendingWithRouteCompleted(itemsForInternalCommit, recoveryExperience);
            } else {
                context.replacePendingWithUnrouted(itemsForInternalCommit, recoveryExperience);
            }
            return;
        }

        if (routeCompletedRecovery) {
            context.queueRouteCompleted(itemsForInternalCommit, recoveryExperience);
        } else if (xpCommitted) {
            context.queueUnrouted(itemsForInternalCommit, recoveryExperience);
        }
    }

    private static List<ItemStack> deepCloneItems(List<ItemStack> items) {
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

    private static long saturatedAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
