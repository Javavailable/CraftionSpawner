package github.nighter.smartspawner.hooks.protections.api;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;
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

    private static class PermissionSnapshot {
        final PermissionId blockPlace;
        final PermissionId blockBreak;
        final PermissionId blockInteract;

        PermissionSnapshot() {
            blockPlace = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.place"));
            blockBreak = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.break"));
            blockInteract = SkylliaAPI.getPermissionRegistry().getIfPresent(new NamespacedKey("skyllia", "block.interact"));
        }
    }

    private final SmartSpawner plugin;
    private volatile boolean enabled = false;
    private volatile Plugin skylliaPlugin;
    private volatile PermissionSnapshot permissions;
    private final AtomicLong lastWarningTime = new AtomicLong(0);

    public SkylliaHook(SmartSpawner plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        try {
            Plugin resolvedPlugin = plugin.getServer().getPluginManager().getPlugin("Skyllia");
            if (resolvedPlugin == null || !resolvedPlugin.isEnabled()) {
                enabled = false;
                skylliaPlugin = null;
                permissions = null;
                return;
            }

            skylliaPlugin = resolvedPlugin;
            permissions = new PermissionSnapshot();
            enabled = true;
            plugin.getLogger().info("Skyllia integration initialized successfully!");
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize Skyllia integration: " + e.getMessage());
            enabled = false;
            skylliaPlugin = null;
            permissions = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        initialize();
    }

    public ProtectionDecision canInteract(Player player, Location location, SpawnerAction action) {
        Plugin resolvedPlugin = this.skylliaPlugin;
        if (!enabled || resolvedPlugin == null || !resolvedPlugin.isEnabled()) {
            return ProtectionDecision.ABSTAIN;
        }

        if (location == null || location.getWorld() == null) {
            return ProtectionDecision.ABSTAIN;
        }

        Island island = null;
        boolean insideIsland = false;

        try {
            if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) {
                return ProtectionDecision.ABSTAIN;
            }

            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);

            if (island == null) {
                return ProtectionDecision.ABSTAIN;
            }

            if (!island.isInside(location)) {
                return ProtectionDecision.ABSTAIN;
            }

            insideIsland = true; // Exact boundary confirmed

            if (player.hasPermission("smartspawner.bypass.skyllia")) {
                return ProtectionDecision.ALLOW;
            }

            PermissionSnapshot snapshot = this.permissions;
            if (snapshot == null) {
                logWarning("Skyllia API lookup failure: permission snapshot is null.");
                return ProtectionDecision.DENY;
            }

            PermissionId permId = switch (action) {
                case PLACE, STACK -> snapshot.blockPlace;
                case BREAK -> snapshot.blockBreak;
                case OPEN, CHANGE_TYPE -> snapshot.blockInteract;
            };

            if (permId == null) {
                logWarning("Skyllia API lookup failure: permission for " + action.name() + " not found.");
                return ProtectionDecision.DENY;
            }

            boolean hasPerm = SkylliaAPI.getPermissionsManager().hasPermission(player, island, permId);
            return hasPerm ? ProtectionDecision.ALLOW : ProtectionDecision.DENY;

        } catch (NoClassDefFoundError | Exception e) {
            if (insideIsland) {
                logWarning("Skyllia API evaluation failure after island boundary confirmed: " + e.getMessage());
                return ProtectionDecision.DENY;
            } else {
                return ProtectionDecision.ABSTAIN;
            }
        }
    }

    private void logWarning(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarningTime.get();
        if (now - last > 5000) {
            if (lastWarningTime.compareAndSet(last, now)) {
                plugin.getLogger().warning(message);
            }
        }
    }
}
