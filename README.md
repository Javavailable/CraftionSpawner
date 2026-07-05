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
`core/build/libs/CraftionSpawner-1.7.0.1-craftion.2.jar`

## API

**Artifact Coordinate:**
`io.github.javavailable:craftionspawner-api:1.7.0.1-craftion.2`

*Note: Java packages and public API class names remain upstream-compatible for now.*

## Identity & Migration

- **Runtime Name:** CraftionSpawner
- **Primary Command:** `/craftionspawner`
- **Aliases:** `/cspawner`, `/spawner`, `/smartspawner`, `/ss`
- **Permissions:** Remain `smartspawner.*`
- **PDC Namespace:** Persisted data namespace remains `smartspawner`
- **Legacy Migration:** The plugin automatically moves the legacy `plugins/SmartSpawner` folder to `plugins/CraftionSpawner` if the new folder does not exist or is empty.
- **Upstream Services:** Upstream UpdateChecker and bStats are cleanly disabled.
- **Testing:** Live Paper/Folia/Luminol testing remains pending.

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
