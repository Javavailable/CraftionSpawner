# CraftionSpawner

A maintained SmartSpawner fork for Craftion, handling spawner management, stacking, and integration.

## Requirements

- **Minecraft Version:** 1.21.5 - 26.1.2
- **Server Software:** Paper, Folia or compatible forks
- **Java Version:** 25+

## Building

```bash
git clone https://github.com/Javavailable/CraftionSpawner.git
cd CraftionSpawner
./gradlew clean build
```

The compiled shaded JAR will be available in:
`core/build/libs/CraftionSpawner-1.7.0.1-craftion.5.jar`

## API Usage

```kotlin
dependencies {
    compileOnly("io.github.javavailable:craftionspawner-api:1.7.0.1-craftion.5")
}
```

### Output Router API (S3)

Trusted external plugins can consume part or all of a spawner's newly generated item output before
the unconsumed remainder falls back to CraftionSpawner's internal virtual storage. The API is
additive and fail-open: with no routers registered, generation and storage behave exactly as before.
Generated experience is never routed.

```java
SmartSpawnerAPI api = SmartSpawnerProvider.getAPI();
api.getOutputRouterRegistry().register(
        new NamespacedKey(myPlugin, "my-router"),
        100, // lower order runs first; ties break by key string
        context -> {
            // Consume some/all of context.getGeneratedItems(); return the unconsumed remainder.
            // Never mutate the supplied context/items. Must be quick, non-blocking and thread-safe.
            return SpawnerOutputResult.passThrough(context); // consume nothing (example)
        });
```

Routers run in deterministic order and are validated defensively: a router that throws, returns
`null`, returns malformed/AIR/non-positive items, introduces a new item type, or inflates quantities
is ignored for that batch (with a rate-limited warning) and the generated items are preserved.
The result factories reject `null` (`passThrough(null)` / `remaining(null)` throw); use
`SpawnerOutputResult.consumeAll()` to consume the whole batch.

### API Java compatibility

The `craftionspawner-api` module is compiled to **Java 21 bytecode** (class-file major version 65) so
it can be consumed by Java 21 toolchains and plugins such as CraftionFarmer. The CraftionSpawner
core/plugin remains **Java 25** (class-file major version 69) and shades the Java 21 API classes. The
public API module must not use Java 22+ language or API features.

## Changes

- [x] Initial fork structure and cleanup
- [x] Adopt baseline reproducible build
- [x] Apply project and artifact identity (`io.github.javavailable:craftionspawner-*`)
- [x] Add S1A: Apply CraftionSpawner project identity
- [x] Add S1B: Apply CraftionSpawner runtime identity
- [x] Add S2A: Add Skyllia access protection integration
- [x] Add S2B: Clean CraftionSpawner data when a Skyllia island is deleted
- [x] Add S3: Public output-router API for newly generated item output (additive, fail-open, Java 21 API)
- [ ] S4: CraftionFarmer output-router bridge (planned, not included)
- [ ] Live testing of S1/S2/S3 features pending
- [ ] More features coming soon...

## Identity & Migration

- **Runtime Name:** CraftionSpawner
- **Primary Command:** `/craftionspawner`
- **Aliases:** `/cspawner`, `/spawner`, `/smartspawner`, `/ss`
- **Permissions:** Remain `smartspawner.*`
- **PDC Namespace:** Persisted data namespace remains `smartspawner`
- **Legacy Migration:** The plugin automatically moves the legacy `plugins/SmartSpawner` folder to `plugins/CraftionSpawner` if the new folder does not exist or is empty.
- **Upstream Services:** Upstream UpdateChecker and bStats are cleanly disabled.
- **Testing:** Live Paper/Folia/Luminol testing remains pending.

## Integrations & Protections

- **Skyllia Protection (Optional):** Protects spawner actions (`PLACE`, `BREAK`, `OPEN`, `STACK`, `CHANGE_TYPE`) to respect Skyllia island permissions.
- **Bypass Permission:** Admins can bypass Skyllia checks with `smartspawner.bypass.skyllia`.
- **Absent Skyllia:** If Skyllia is absent or disabled, the plugin leaves existing behavior unchanged.
- **Non-island Locations:** If the spawner location is outside any Skyllia island, the integration will abstain from interfering.
- **Island Deletion Cleanup:** When a Skyllia island is successfully deleted, all associated spawners within the exact island boundary are cleanly removed from runtime data, memory indexes, and persistence (YAML/SQLite/MariaDB). This cleanup prevents phantom drops and closes any open management GUIs asynchronously without physical chunk loads.
- **Output Router API (Optional):** External plugins may register output routers to consume newly generated spawner item output before it enters internal storage. See the API Usage section above.

## Upstream References & Attribution

This project is a fork of [SmartSpawner](https://github.com/OpenVdra/SmartSpawner) by OpenVdra. We are grateful for their work.

- [![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/plugin/smartspawner)
- [![Spigot](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/spigot_vector.svg)](https://www.spigotmc.org/resources/120743/)
- [![Hangar](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/hangar_vector.svg)](https://hangar.papermc.io/Nighter/SmartSpawner)
- [![Documentation](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/documentation/ghpages_vector.svg)](https://docs.smartspawner.site/)
- [![discord-plural](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/social/discord-plural_46h.png)](http://discord.com/invite/FJN7hJKPyb)
- [![bStats](https://bstats.org/signatures/bukkit/SmartSpawner.svg)](https://bstats.org/plugin/bukkit/SmartSpawner)

## License

This project is licensed under the GPLv3 License - see the [LICENSE](LICENSE) file for details.
