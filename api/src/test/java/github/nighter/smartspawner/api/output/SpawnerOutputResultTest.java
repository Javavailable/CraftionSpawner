package github.nighter.smartspawner.api.output;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the null-rejection footgun fixes and full-consumption factory. */
class SpawnerOutputResultTest {

    @Test
    void passThroughRejectsNull() {
        assertThrows(NullPointerException.class, () -> SpawnerOutputResult.passThrough(null));
    }

    @Test
    void remainingRejectsNull() {
        assertThrows(NullPointerException.class, () -> SpawnerOutputResult.remaining(null));
    }

    @Test
    void consumeAllIsEmptyAndUnmodifiable() {
        SpawnerOutputResult result = SpawnerOutputResult.consumeAll();
        assertNotNull(result.getRemainingItems());
        assertTrue(result.getRemainingItems().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.getRemainingItems().add(null));
    }

    @Test
    void remainingEmptyListIsAllowed() {
        SpawnerOutputResult result = SpawnerOutputResult.remaining(Collections.emptyList());
        assertTrue(result.getRemainingItems().isEmpty());
    }
}
