package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public class SkylliaHook {

    public enum SpawnerAction {
        PLACE,
        BREAK,
        OPEN,
        STACK,
        CHANGE_TYPE
    }

    public enum ProtectionDecision {
        ALLOW,
        DENY,
        ABSTAIN
    }

    private final SmartSpawner plugin;
    private boolean enabled = false;
    private long lastWarningTime = 0;

    private PermissionId blockPlace;
    private PermissionId blockBreak;
    private PermissionId blockInteract;

    public SkylliaHook(SmartSpawner plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        try {
            Plugin skylliaPlugin = plugin.getServer().getPluginManager().getPlugin("Skyllia");
            if (skylliaPlugin == null || !skylliaPlugin.isEnabled()) {
                enabled = false;
                return;
            }
            enabled = true;
            plugin.getLogger().info("Skyllia integration initialized successfully!");
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize Skyllia integration: " + e.getMessage());
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        initialize();
    }
    
    private void lazyLoadPermissions() {
        if (blockPlace == null) blockPlace = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.place"));
        if (blockBreak == null) blockBreak = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.break"));
        if (blockInteract == null) blockInteract = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.interact"));
    }

    public ProtectionDecision canInteract(Player player, Location location, SpawnerAction action) {
        if (!enabled || location == null || location.getWorld() == null) {
            return ProtectionDecision.ABSTAIN;
        }

        try {
            if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) {
                return ProtectionDecision.ABSTAIN;
            }

            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
            
            if (island == null) {
                return ProtectionDecision.ABSTAIN;
            }

            if (player.hasPermission("smartspawner.bypass.skyllia")) {
                return ProtectionDecision.ALLOW;
            }

            lazyLoadPermissions();

            PermissionId permId = switch (action) {
                case PLACE, STACK -> blockPlace;
                case BREAK -> blockBreak;
                case OPEN, CHANGE_TYPE -> blockInteract;
            };

            if (permId == null) {
                logWarning("Skyllia API lookup failure: permission for " + action.name() + " not found.");
                return ProtectionDecision.DENY;
            }

            boolean hasPerm = SkylliaAPI.getPermissionsManager().hasPermission(player, island, permId);
            return hasPerm ? ProtectionDecision.ALLOW : ProtectionDecision.DENY;
        } catch (NoClassDefFoundError | Exception e) {
            logWarning("Skyllia API lookup failure: " + e.getMessage());
            return ProtectionDecision.DENY;
        }
    }

    private void logWarning(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarningTime > 5000) {
            plugin.getLogger().warning(message);
            lastWarningTime = now;
        }
    }
}
