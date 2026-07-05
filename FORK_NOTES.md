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

## S0.1 Build Resolution

- **Previous Coordinate:** `com.iridium:IridiumSkyblock:4.1.4`
- **Replacement Coordinate:** `maven.modrinth:iridiumskyblock:4.1.4`
- **Repository:** Modrinth Maven repository (`https://api.modrinth.com/maven`)
- **JDK Version:** 25.0.3+9
- **Gradle Version:** 9.6.0
- **Tests:** `test`, `api:test`, `core:test` tasks reported `NO-SOURCE` at that time.
- **Generated Shaded JAR:** `core/build/libs/SmartSpawner-1.7.0.1.jar`
- Live Paper/Folia/Luminol testing remains pending.

## Upstream Synchronization
Recommended future workflow for syncing upstream updates:
```bash
git fetch upstream
git checkout main
git pull --ff-only origin main
git merge --ff-only upstream/main
```
Non-fast-forward upstream updates must be handled through a dedicated sync branch and reviewed PR, not force-pushed directly to `main`.

## License and Attribution
- Upstream copyright and attribution must remain.
- GPL requirements must continue to be followed.
- `LICENSE` files must not be removed.
- The API POM’s MIT metadata differs from the root GPLv3 declaration and requires later review.

## Planned Craftion Packages
- S1 Craftion identity
- S2 Skyllia protection integration
- S3 output router API
- S4 CraftionFarmer bridge
- S5 per-Farmer mob states
- S6 Craftion UX
- S7 hardening and release

## S1A Project Identity
- **New Version:** 1.7.0.1-craftion.1
- **New Gradle/Maven Group:** io.github.javavailable
- **Java packages and API class names:** intentionally retained

## S1B Runtime Identity
- Runtime plugin name: CraftionSpawner
- Version: 1.7.0.1-craftion.2
- Primary command: `/craftionspawner`; aliases `/cspawner`, `/spawner`, `/smartspawner`, `/ss`
- Retained permissions: `smartspawner.*`; retained PDC namespace: `smartspawner`
- Upstream updater and bStats: Disabled.

## S2A Skyllia Protection
- **Skyllia Version:** fr.euphyllia.skyllia:api:3.0-158 (commit daf64687d6cac9c252227c60cd402d9f6f132287)
- **Protected Actions:** PLACE, BREAK, OPEN, STACK, CHANGE_TYPE
- **Bypass Permission:** `smartspawner.bypass.skyllia`

## S2B Skyllia Island Cleanup
- Bounded, race-safe cleanup on confirmed island deletion across runtime indexes and YAML/SQLite/MariaDB persistence, with atomic removal claims, expected-instance detach, and tombstone durability.

## S3 Output Router API

- **Version:** 1.7.0.1-craftion.5
- **Type:** Additive, backward-compatible public API. No existing API signatures were removed or changed.
- **New public package:** `github.nighter.smartspawner.api.output` (`SpawnerOutputRouter`, `SpawnerOutputContext`, `SpawnerOutputResult`, `SpawnerOutputRouterRegistry`), exposed via `SmartSpawnerAPI.getOutputRouterRegistry()`.
- **Registration:** collision-safe Bukkit `NamespacedKey` + deterministic integer order + router. Lower order first, ties by key string; duplicate keys return `false` and never replace; thread-safe; routing uses a stable immutable snapshot.
- **Item-only routing:** Only newly generated item output is routed; experience always uses the existing stored-XP path. Manual withdrawals, selling, drop-all, breaking and already-stored contents are never routed. Unconsumed remainder falls through to internal virtual storage with the existing slot-capacity limiting.
- **CraftionFarmer bridge:** deferred to S4 (out of scope).

### S3 hardening (fix: harden output router commit safety)

- **Defensive DTO:** `SpawnerDataDTO` now clones the supplied `Location` in its constructor and `getLocation()` returns a fresh clone (Lombok getter disabled for the field), so a router can never mutate the real internal spawner location. The output context otherwise exposes only immutable/enum/primitive values and deeply cloned, unmodifiable item lists — no internal mutable-object leakage.
- **Removal/routing race elimination:** the single shared commit helper acquires the shared `SpawnerLocationLockManager` location lock (non-blocking) BEFORE any router runs and holds it across router execution, XP commit, remainder insertion, `lastSpawnTime`, capacity update, GUI/hologram update and persistence marking. It checks BOTH removal systems — S2B `SpawnerData.isRemovalPending()` and `SpawnerRemovalService.isRemovalPending(spawner)` (which stays claimed even while its own location lock is temporarily released) — plus exact-instance identity by ID and location. Skyllia cleanup and normal removal cannot detach the spawner mid-commit. `removeLock()` is never called from the generation path. Acquisition order: lootGenerationLock → location lock → dataLock → inventoryLock; all non-blocking `tryLock` (no blocking `lock()`, sleeps, `join()` or `Future.get()`).
- **Timing before side effects:** the data lock (needed for `lastSpawnTime`) is acquired before any router is invoked, so external consumption is never followed by a failed late timing lock; `lastSpawnTime` advances exactly once and no router observes the same cycle twice.
- **Full-capacity storm prevention:** a richer commit result distinguishes router-pass-attempted, external consumption, internal insertion and XP change. When a non-empty batch is legitimately presented to an active router snapshot, the cycle timer advances once even if nothing was consumed/inserted — preserving the configured spawn interval and preventing per-tick router callbacks against permanently full storage, without ever claiming false external consumption.
- **Pre-generated durability:** the pre-generated commit builds owned clones once and retries the SAME clones in a bounded (max 5 attempts, 2 ticks apart), non-blocking manner only on transient lock failure (before any router runs), so the already-cleared batch is neither dropped nor routed twice.
- **API footguns removed:** `SpawnerOutputResult.passThrough(null)` and `remaining(null)` now throw `NullPointerException`; `consumeAll()` is the only explicit full-consumption factory.
- **Provider safety:** `SmartSpawnerProvider` returns null when the plugin is missing, disabled, not a `SmartSpawnerPlugin`, or exposes no API; it resolves `CraftionSpawner` first, then legacy `SmartSpawner`.
- **API Java compatibility:** the `api` module is compiled to Java 21 bytecode (class-file major version 65) so `craftionspawner-api` is consumable by Java 21 builds (CraftionFarmer/S4). Core/plugin remains Java 25 (major version 69) and shades the Java 21 API. No Java 22+ features are used in the API module.
- **Tests:** JUnit 5 regression tests were added (api: `SpawnerOutputResultTest`, `SpawnerDataDTOTest`, `ApiBytecodeVersionTest`; core: `SpawnerOutputRouterRegistryImplTest`). Deeper routing/removal/timing scenarios need a full server (MockBukkit) harness and are covered by design; they are not asserted as plain unit tests.
- **Testing:** final integrated runtime testing is deferred to the release-candidate phase; no live testing was performed for this package.
