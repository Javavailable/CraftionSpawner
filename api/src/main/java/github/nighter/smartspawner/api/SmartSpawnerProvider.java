package github.nighter.smartspawner.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Utility class to get the SmartSpawnerAPI instance.
 *
 * <p>Resolves the CraftionSpawner runtime plugin first, then falls back to the legacy
 * {@code SmartSpawner} plugin name for backward compatibility.
 */
public class SmartSpawnerProvider {

    private static final String PRIMARY_PLUGIN_NAME = "CraftionSpawner";
    private static final String LEGACY_PLUGIN_NAME = "SmartSpawner";

    /**
     * Gets the SmartSpawnerAPI instance.
     *
     * @return the API instance, or null if neither CraftionSpawner nor SmartSpawner is loaded,
     *         enabled, and exposing an API
     */
    public static SmartSpawnerAPI getAPI() {
        SmartSpawnerAPI api = resolve(PRIMARY_PLUGIN_NAME);
        if (api != null) {
            return api;
        }
        return resolve(LEGACY_PLUGIN_NAME);
    }

    private static SmartSpawnerAPI resolve(String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);

        // Null when: plugin not installed, disabled, not a SmartSpawnerPlugin, or API unavailable.
        if (plugin == null) {
            return null;
        }
        if (!plugin.isEnabled()) {
            return null;
        }
        if (!(plugin instanceof SmartSpawnerPlugin)) {
            return null;
        }
        return ((SmartSpawnerPlugin) plugin).getAPI();
    }
}
