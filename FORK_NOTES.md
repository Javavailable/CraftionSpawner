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
- **Synchronous Lookup Limitation:** `SkylliaAPI.getIslandByChunk` calls `getIslandByRegion` which performs a synchronous JDBC database query on cold cache misses. This limitation is noted; event cancellations rely on Skyllia natively cancelling `BlockPlaceEvent` and `PlayerInteractEvent` where applicable, allowing the plugin to ignore them via `ignoreCancelled = true`.
- **Deferred Tasks:** S2B cleanup remains deferred.

## S2B Skyllia Island Cleanup

- **Async Handling:** `SkyblockDeleteEvent` is asynchronous and pre-delete. The cleanup service schedules a delayed Bukkit/Folia async task to wait for the actual `isDisable()` state, retrying up to 10 times at 20-tick intervals.
- **Deletion Confirmation:** Deletion is only processed if `isDisable()` returns true. If unconfirmed, no cleanup executes.
- **Deduplication:** Jobs are deduplicated via a `pendingDeletions` ConcurrentHashMap.newKeySet(), kept until every deferred retry completes/aborts (via `CompletableFuture.whenComplete`).
- **Exact Boundary Matching:** Iterates over a snapshot of spawners, requiring `SkylliaAPI.isWorldSkyblock(world)` and `island.isInside(spawnerLocation)` to capture only the targeted spawners.
- **Data-Only Cleanup:** Safe data-only detach via `spawnerManager.removeSpawnerDataOnly(id, expected)` (expected-instance conditional) strips references without modifying physical blocks or forcing unloaded chunks into memory. Never sets blocks to AIR, never drops items/XP, never pays money.
- **Atomic removal claim:** `SpawnerData.tryBeginRemoval()`/`isRemovalPending()`/`cancelRemoval()` make selling and removal mutually exclusive.
- **Locking:** Non-blocking `SpawnerLocationLockManager` location lock; `removeLock` is not called from the cleanup path (periodic cleanup reclaims it).
- **Storage Modes:** Supports the active persistent storage method (YAML/SQLite/MariaDB) via `spawnerManager.markSpawnerDeleted`; deletion tombstones win over stale modifications; YAML and database flush snapshots are requeued on failure.
- **Attempts/Timing:** Exactly 10 total processing attempts, 20 ticks apart; no sleeps/blocking; shutdown cancels deferred retries.
- **Inspected Skyllia version:** `3.0-158`, exact commit `daf64687d6cac9c252227c60cd402d9f6f132287`.

## S3 Output Router API

- **Version:** 1.7.0.1-craftion.5
- **Type:** Additive, backward-compatible public API. No existing API signatures were removed or changed.
- **New public package:** `github.nighter.smartspawner.api.output` (`SpawnerOutputRouter`, `SpawnerOutputContext`, `SpawnerOutputResult`, `SpawnerOutputRouterRegistry`), exposed via `SmartSpawnerAPI.getOutputRouterRegistry()`.
- **Registration:** collision-safe Bukkit `NamespacedKey` + deterministic integer order + router. Lower order first, ties by key string; duplicate keys return `false` and never replace; thread-safe; routing uses a stable immutable snapshot.
- **Item-only routing:** Only newly generated item output is routed; experience always uses the existing stored-XP path. Manual withdrawals, selling, drop-all, breaking and already-stored contents are never routed. Unconsumed remainder falls through to internal virtual storage with the existing slot-capacity limiting.
- **CraftionFarmer bridge:** deferred to S4 (out of scope). No S4 bridge code or compatibility verification is included in S3.

### S3 hardening

- **Defensive DTO:** `SpawnerDataDTO` clones the supplied `Location` in its constructor and `getLocation()` returns a fresh clone (Lombok getter disabled for the field), so a router can never mutate the real internal spawner location. Other context values are enums/primitives/String plus deeply cloned, unmodifiable item lists.
- **Removal/routing race elimination:** one shared commit helper acquires the shared `SpawnerLocationLockManager` location lock (non-blocking) before any router runs and holds it across the whole commit; it checks BOTH `SpawnerData.isRemovalPending()` and `SpawnerRemovalService.isRemovalPending(spawner)` plus exact-instance identity by ID and location. `removeLock()` is never called from the generation path.
- **API footguns removed:** `SpawnerOutputResult.passThrough(null)` and `remaining(null)` throw `NullPointerException`; `consumeAll()` is the only explicit full-consumption factory.
- **Provider safety:** `SmartSpawnerProvider` returns null when the plugin is missing, disabled, not a `SmartSpawnerPlugin`, or exposes no API; resolves `CraftionSpawner` first, then legacy `SmartSpawner`.

### S3 final hardening (fix: finalize output router hardening)

- **Transactional pending commits:** pending batches now use internal non-destructive claim/ack/replace semantics. Claims and acknowledgements hold the pending lock only briefly; router invocation and location/data/inventory lock acquisition never happen while holding it. `COMMITTED` acknowledges only exact claimed batch IDs. `NOOP`, `LOCK_UNAVAILABLE`, and genuine pre-side-effect failures release the claim without dropping newer pending work. Once routing, XP mutation, internal insertion, or timer advancement has occurred, the original claim is never released; failures acknowledge or replace it with only the still-uncommitted recovery batch. `ABORTED_STALE` clears normal pre-generated loot, pending batches, and claim state for the stale instance.
- **Atomic item+XP pre-generation:** pre-generated item and XP output is claimed/cleared as one internal batch operation, so `SpawnerRangeChecker` and `LootPreGenerationHelper` cannot split item commits from XP commits.
- **Retry-exhaustion handoff:** the original five non-blocking commit attempts still reuse owned deep clones. Exhausted batches move into a generator-owned deduplicated handoff queue, merged by exact spawner instance with deep clones and saturating XP. A single managed periodic drain retries with non-blocking location-lock acquisition and re-checks both removal systems plus exact ID and exact location object identity before queueing into pending commit. Definitely stale/removed batches are aborted, and unresolved handoffs are logged and cleared on shutdown.
- **Lifecycle cleanup:** `SpawnerData.resetGeneratedLootState()` clears normal pre-generated items, XP, active pre-generation state, pending batches, and pending claim state. It is used for inactive cleanup, deactivation/stop, ordinary/API/physical removal, expected-instance data-only detach, Skyllia S2B detach, and unload/reload cleanup. `SpawnerLootGenerator.shutdown()` cancels the managed handoff drain.
- **Commit locking:** generation commit order remains `lootGenerationLock -> shared location lock -> dataLock -> inventoryLock -> router invocation / route-completed recovery point -> XP/item/timer commit`. Commit-path acquisitions are non-blocking, and `inventoryLock.tryLock()` is acquired before router invocation whenever item state may be touched. Internal insertion uses `SpawnerData.addItemsAndUpdateSellValueWhileLocked(...)`.
- **Router semantics:** routing still uses a stable immutable registry snapshot. A package-private pure routing seam supports deterministic unit tests without adding public API. Router failures, null results, malformed replacements, inflated quantities, and unregister-after-hint races fall back to the safe remaining-output path.
- **Java compatibility:** deterministic project-aware Gradle configuration compiles `:api` to Java 21 bytecode (major 65) and all other projects to Java 25 bytecode (major 69). Local `javap -verbose` inspection confirmed major 65 for `SmartSpawnerAPI`, `SmartSpawnerProvider`, and all public output API classes, and major 69 for `SpawnerOutputRoutingService` and `SpawnerLootGenerator`.
- **Dependency resolution:** the core test dependency conflict was diagnosed with `dependencyInsight` for `guava`, `gson`, `fastutil`, and `log4j-bom` before changing Gradle. The fix is scoped to test configurations: core tests use the same WorldGuard 7.1.0-SNAPSHOT line as production compile resolution, and the malformed `BMUtils` transitive POM is excluded from `testRuntimeClasspath` only.
- **Local tests:** `./gradlew :api:test :core:test --no-daemon --stacktrace` passed with 22 tests, 0 failures, 0 errors, 0 skipped. Covered suites: `SpawnerOutputResultTest` (4), `SpawnerDataDTOTest` (3), `ApiBytecodeVersionTest` (1), `SpawnerOutputRouterRegistryImplTest` (4), and `SpawnerOutputRoutingServiceTest` (10).
- **Local build:** `./gradlew clean build --no-daemon --stacktrace` passed on JDK 25.0.3+9 with Gradle wrapper 9.6.0. `api/build/libs/api-1.7.0.1-craftion.5.jar` was 35,726 bytes with SHA-256 `a05c093b9492834d4f10967555be9bcd9c125e9c56516cefc8eaaf0ae68ba805`. `core/build/libs/CraftionSpawner-1.7.0.1-craftion.5.jar` was 1,914,989 bytes with SHA-256 `983da9a55b3af7140222ace0288a2f9861a09142f248bfe7fbedd18f7f39ec5f`.
- **Artifact audit:** the plugin JAR contains the public output API classes and `SpawnerOutputRouterRegistryImpl`, advertises `provides: [ SmartSpawner ]`, contains no Skyllia shaded classes, no test entries, no duplicate entries, and no unintended nested archives. No new tracked archive files were added.
- **Testing boundary:** no live Paper/Folia/Luminol runtime testing was performed in S3. Live runtime testing remains deferred to the release-candidate phase.
