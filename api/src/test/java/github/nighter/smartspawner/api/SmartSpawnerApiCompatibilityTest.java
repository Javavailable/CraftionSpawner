package github.nighter.smartspawner.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartSpawnerApiCompatibilityTest {

    @Test
    void outputRouterRegistryMethodRemainsBinaryCompatibleForLegacyImplementations() throws Exception {
        assertTrue(SmartSpawnerAPI.class
                .getMethod("getOutputRouterRegistry")
                .isDefault());
    }
}
