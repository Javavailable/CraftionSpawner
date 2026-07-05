package github.nighter.smartspawner.api.output;

/**
 * A router that may consume part or all of a spawner's newly generated item output before the
 * unconsumed remainder falls back to CraftionSpawner's internal virtual storage.
 *
 * <p>Register implementations through
 * {@link github.nighter.smartspawner.api.SmartSpawnerAPI#getOutputRouterRegistry()}.
 *
 * <p><b>Scope:</b> Only newly generated item output is routed. Generated experience is never routed
 * and always continues through CraftionSpawner's existing stored-XP path. Manual withdrawals,
 * selling, drop-all actions, spawner breaking and already-stored contents are never routed.
 *
 * <p><b>Contract for implementations:</b>
 * <ul>
 *   <li>Must return quickly and must not perform blocking database/network operations.</li>
 *   <li>Must be thread-safe.</li>
 *   <li>Must not retain or mutate the supplied context, its item list, or any supplied
 *       {@link org.bukkit.inventory.ItemStack} instances. Build a new result instead.</li>
 *   <li>Must not assume a single global Bukkit main thread; the server may be Folia.</li>
 *   <li>May be invoked from the spawner's valid Folia location execution context.</li>
 *   <li>The returned {@link SpawnerOutputResult} must describe only the <i>remaining</i> items,
 *       and may only return items whose type/meta and per-type quantity were present in the
 *       supplied batch. Returning new item types, inflated quantities, or {@code null}/AIR/
 *       non-positive entries causes the core to defensively ignore this router for that batch
 *       (fail-open) and keep the full current remainder.</li>
 * </ul>
 */
@FunctionalInterface
public interface SpawnerOutputRouter {

    /**
     * Routes the current batch of newly generated items.
     *
     * @param context immutable context with a read-only spawner snapshot and a deeply cloned,
     *                unmodifiable view of the items currently available to this router
     * @return the items that remain unconsumed and should continue to later routers or internal
     *         storage; never {@code null} (use {@link SpawnerOutputResult#consumeAll()} to consume
     *         everything or {@link SpawnerOutputResult#passThrough(SpawnerOutputContext)} to keep
     *         the batch unchanged)
     */
    SpawnerOutputResult route(SpawnerOutputContext context);
}
