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
*(Note: Build failed due to upstream missing dependency `com.iridium:IridiumSkyblock:4.1.4`. No tests existed to run. Shaded JAR was not generated. Live Paper/Folia/Luminol smoke testing remains pending.)*

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
