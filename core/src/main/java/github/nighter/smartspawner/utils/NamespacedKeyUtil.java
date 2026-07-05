package github.nighter.smartspawner.utils;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

public class NamespacedKeyUtil {
    
    private static final String LEGACY_NAMESPACE = "smartspawner";

    @NotNull
    @SuppressWarnings("deprecation")
    public static NamespacedKey create(@NotNull String key) {
        return new NamespacedKey(LEGACY_NAMESPACE, key);
    }
}
