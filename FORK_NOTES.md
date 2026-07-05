# CraftionSpawner Fork Notes

## Upstream
- **Upstream Repository URL:** https://github.com/OpenVdra/SmartSpawner
- **Fork Repository URL:** https://github.com/Javavailable/CraftionSpawner
- **Date Baseline Recorded:** 2026-07-05
- **Upstream Branch Used:** main
- **Origin Baseline Commit:** 87c2b3a819b8d1f1a9ea31d7ed8a849065938522
- **Upstream Main Commit:** 87c2b3a819b8d1f1a9ea31d7ed8a849065938522
- **Divergence:** Fork and upstream are exactly identical (0 ahead, 0 behind).

## Baseline Scope
This baseline explicitly makes no runtime, configuration, branding, API, database, or dependency changes. It establishes an auditable record of the fork origin before Craftion-specific adjustments.

## Project Structure
- `api`: The API module intended for external plugins to interact with SmartSpawner.
- `core`: The main implementation module containing plugin logic, database integration, and UI.
- **Root Gradle Project:** Contains the main build configurations and subproject declarations (`SmartSpawner`).
- **Shaded Plugin JAR Output:** The final shaded plugin JAR is produced at `core/build/libs/SmartSpawner-1.7.0.1.jar` via the `shadowJar` task.

## Build Verification
- **JDK:** 25.0.3+9
- **Gradle:** Wrapper 9.6.0
- **Supported Ranges:**
  - Minecraft versions: 1.21.5 - 26.1.2
  - Server software: Paper, Folia, or compatible forks
  - Java: 25+

**Verified Build Command:**
`./gradlew clean build`
*(Note: `./gradlew clean build` was attempted with JDK 25.0.3+9 and Gradle 9.6.0. The API module compiled, but the core build stopped because Gradle could not resolve `com.iridium:IridiumSkyblock:4.1.4` from the repositories configured by the upstream baseline. The root cause has not yet been classified as a removed artifact, changed coordinate, repository outage, or local/network issue. Tests were not run because the build failed before test execution. No shaded plugin JAR or SHA-256 was produced. Live Paper/Folia/Luminol testing remains pending.)*

## S0.1 Build Resolution

- **Previous Coordinate:** `com.iridium:IridiumSkyblock:4.1.4`
- **Failure Observed During S0:** `com.iridium:IridiumSkyblock:4.1.4` could not be resolved from the repositories configured by the upstream baseline during S0.
- **Replacement Coordinate:** `maven.modrinth:iridiumskyblock:4.1.4`
- **Repository:** Modrinth Maven repository (`https://api.modrinth.com/maven`)
- **Legitimacy:** IridiumSkyblock 4.1.4 is available through the official Modrinth project, and Modrinth’s Maven endpoint exposes it reliably.
- **Java Integration:** The existing Iridium integration compiles without Java source changes against that artifact.
- **JDK Version:** 25.0.3+9
- **Gradle Version:** 9.6.0
- **Successful Commands:**
  - `./gradlew clean build --refresh-dependencies`
  - `./gradlew clean build`
- **Tests:** `test`, `api:test`, `core:test` tasks reported `NO-SOURCE` (no tests executed because there are no test sources).
- **Generated Shaded JAR:** `core/build/libs/SmartSpawner-1.7.0.1.jar`
- **Byte Size:** 1869972 bytes
- **SHA-256:** 71d08a9b8843f5ae533e83cf39e66ef4cad07a77f5fd1f5e3e395c5a9ab9fc43
- Live Paper/Folia/Luminol testing remains pending.

## Upstream Synchronization
Recommended future workflow for syncing upstream updates:
```bash
git fetch upstream
git checkout main
git pull --ff-only origin main
git merge --ff-only upstream/main
```
Non-fast-forward upstream updates must be handled through a dedicated sync branch and reviewed PR, not force-pushed directly to `main`. Example branch name: `chore/sync-upstream-<version>`.

## License and Attribution
- Upstream copyright and attribution must remain.
- GPL requirements must continue to be followed.
- `LICENSE` files must not be removed.
- Redistributed modified builds must comply with the upstream license.
- The API POM’s MIT metadata differs from the root GPLv3 declaration and requires later review.

## Planned Craftion Packages
These are future planned packages (not implemented in S0):
- S1 Craftion identity
- S2 Skyllia protection integration
- S3 output router API
- S4 CraftionFarmer bridge
- S5 per-Farmer mob states
- S6 Craftion UX
- S7 hardening and release

## S1A Project Identity

- **Old Project Identity:** SmartSpawner (github.nighter)
- **New Project Identity:** CraftionSpawner
- **New Version:** 1.7.0.1-craftion.1
- **New Gradle/Maven Group:** io.github.javavailable
- **New JAR Name:** CraftionSpawner-1.7.0.1-craftion.1.jar
- **New API Publication Coordinate:** io.github.javavailable:craftionspawner-api:1.7.0.1-craftion.1
- **Java packages and API class names:** intentionally retained
- **Runtime plugin name:** intentionally retained temporarily
- **Commands and permission nodes:** intentionally unchanged
- **Data folder and persistent keys:** intentionally unchanged
- **Full runtime identity migration:** deferred to S1B
- **Full Craftion language/GUI localization:** deferred to a later package
- **Upstream attribution:** preserved

## S1B Runtime Identity

- Runtime plugin name: CraftionSpawner
- Version: 1.7.0.1-craftion.2
- Primary command: `/craftionspawner`
- Aliases: `/cspawner`, `/spawner`, `/smartspawner`, `/ss`
- Retained permissions: `smartspawner.*`
- Retained PDC namespace: `smartspawner`
- Legacy folder migration: Safely moves `plugins/SmartSpawner` to `plugins/CraftionSpawner` if the new folder does not exist or is completely empty. Fails safely without deletion if both exist.
- Upstream updater: Disabled.
- Upstream bStats: Disabled.
- Java packages and API class names: Retained.
- Localization: Full Craftion language and GUI localization remains pending.

## S2A Skyllia Protection

- **Skyllia Version:** fr.euphyllia.skyllia:api:3.0-158
- **Inspected Commit:** daf64687d6cac9c252227c60cd402d9f6f132287
- **Public API Used:** `SkylliaAPI.getIslandByChunk(int, int)`, `island.isInside(Location)`, `SkylliaAPI.getPermissionsManager().hasPermission(...)`, `SkylliaAPI.getPermissionRegistry().getIfPresent(...)`
- **Protected Actions:** PLACE, BREAK, OPEN, STACK, CHANGE_TYPE
- **Permission Mapping:**
  - PLACE / STACK -> `skyllia:block.place`
  - BREAK -> `skyllia:block.break`
  - OPEN / CHANGE_TYPE -> `skyllia:block.interact`
- **Bypass Permission:** `smartspawner.bypass.skyllia`
- **Boundary Behavior:** Confirms exact island boundary via `island.isInside(location)`. Abstains if not inside the island.
- **Absent/Outside-island Behavior:** Leaves existing behavior unchanged (ABSTAIN)
- **API Failure Behavior:** Exception before valid island: ABSTAIN. Exception after island confirmed: DENY. Missing permission node: DENY with rate-limited warning.
- **Synchronous Lookup Limitation:** `SkylliaAPI.getIslandByChunk` calls `getIslandByRegion` which performs a synchronous JDBC database query (`plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getIslandByRegion`) on cold cache misses. This limitation is noted and event cancellations will rely on Skyllia natively cancelling `BlockPlaceEvent` and `PlayerInteractEvent` where applicable, which allows the plugin to ignore them via `ignoreCancelled = true`.
- **Deferred Tasks:** S2B cleanup remains deferred.

## S2B Skyllia Island Cleanup

- **Async Handling:** `SkyblockDeleteEvent` is asynchronous and pre-delete. The cleanup service schedules a delayed Bukkit/Folia async task to wait for the actual `isDisable()` state, retrying up to 10 times at 20-tick intervals.
- **Deletion Confirmation:** Deletion is only processed if `isDisable()` returns true. If unconfirmed, no cleanup executes.
- **Deduplication:** Jobs are deduplicated via a `pendingDeletions` ConcurrentHashMap.newKeySet().
- **Exact Boundary Matching:** Iterates over a snapshot of spawners, calling `island.isInside(spawnerLocation)` to capture only the targeted spawners.
- **Data-Only Cleanup:** Safe data-only batch deletion is utilized. `spawnerManager.removeSpawnersDataOnly` safely strips references without modifying physical blocks or forcing unloaded chunks into memory.
- **Storage Modes:** Supports the active persistent storage method (YAML/SQLite/MariaDB) seamlessly via `spawnerManager.markSpawnerDeleted`.
- **Failure/Retry Behavior:** If an individual spawner fails, it proceeds to the next.
- **Inspected Skyllia version:** `3.0-158`, exact commit `daf64687d6cac9c252227c60cd402d9f6f132287`.

## S3 Output Router API

- **Version:** 1.7.0.1-craftion.5
- **Type:** Additive, backward-compatible public API. No existing API signatures were removed or changed.
- **New public package:** `github.nighter.smartspawner.api.output`
  - `SpawnerOutputRouter` (functional interface: `SpawnerOutputResult route(SpawnerOutputContext)`)
  - `SpawnerOutputContext` (immutable: read-only `SpawnerDataDTO` + deeply cloned, unmodifiable generated item list)
  - `SpawnerOutputResult` (immutable: `consumeAll()`, `remaining(list)`, `passThrough(context)`; owns deep clones, exposes an unmodifiable list; `null` is never a valid decision)
  - `SpawnerOutputRouterRegistry` (exposed via `SmartSpawnerAPI.getOutputRouterRegistry()`)
- **Registration:** collision-safe Bukkit `NamespacedKey` + deterministic integer order + router. Lower order runs first; ties break by key string. Duplicate keys return `false` and never replace. `unregister` returns whether a router existed. `isRegistered`/`getRegisteredKeys` (immutable set) provided. Thread-safe; routing reads a stable immutable snapshot so registration changes cannot corrupt an active route.
- **Item-only routing:** Only newly generated item output is routed. Generated experience always continues through the existing stored-XP path. Manual withdrawals, selling, drop-all, spawner breaking and already-stored contents are never routed.
- **Internal fallback:** The unconsumed remainder falls through to CraftionSpawner internal virtual storage using the existing slot-capacity limiting.
- **Capacity behavior:** With no routers, capacity behavior is unchanged. With at least one router, generated items are routed first even when internal storage is full; existing slot-capacity limiting applies only to the remainder. XP capacity is independent and unchanged. The "experience-only when item storage is full" shortcut applies only when no router could consume items.
- **Generation-cycle correctness:** Full external consumption still counts as handled; `lastSpawnTime` advances once, nothing is inserted internally, and persistence/GUI state stay coherent. Partial routing inserts only the remainder; the batch is never duplicated or silently discarded.
- **Removal/stale-instance safety:** Before routing/committing, the commit phase re-validates that the spawner is not `removalPending` and that the exact instance is still indexed by ID and location; otherwise no routers run and no items/XP/state/persistence changes occur. S2B tombstone and expected-instance protections are unchanged.
- **Failure behavior (fail-open):** A router that throws, returns `null`, returns a null/AIR/non-positive item, introduces a new item type/meta, or inflates a per-type quantity is defensively ignored for that batch with a rate-limited (60s/key) warning; the full current remainder is preserved and other routers are unaffected.
- **Threading:** Routing occurs only in the safe location-thread commit phase (never during async loot calculation). The pre-generated commit path was corrected (smallest scoped change) so routing and final state mutation run on the valid location execution context under the loot-generation lock, instead of a fire-and-forget async task that previously ran after the lock was released. No `join()`/`get()`/sleep; existing sale/removal mutual exclusion preserved.
- **CraftionFarmer bridge:** deferred to S4 (strictly out of scope here).
- **Testing:** Final integrated runtime testing is deferred to the release-candidate phase; no live testing was performed for this package.
