package github.nighter.smartspawner.api.data;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression tests proving the DTO owns a defensive Location copy and never leaks a mutable reference. */
class SpawnerDataDTOTest {

    private SpawnerDataDTO dto(Location location) {
        // entityType/material are irrelevant here and left null to avoid touching the registry-backed enum.
        return new SpawnerDataDTO("id", location, null, null, 1, 1000, 1, 1, 4, 1000L, 500L);
    }

    @Test
    void constructorClonesLocationAndGetterReturnsClone() {
        Location source = new Location(null, 1, 2, 3);
        SpawnerDataDTO dto = dto(source);

        Location got = dto.getLocation();
        assertNotSame(source, got, "getLocation must not return the source instance");

        // Mutating the returned clone must not affect the DTO's internal Location.
        got.setX(999);
        assertEquals(1.0, dto.getLocation().getX(), 0.0);

        // Mutating the source after construction must not affect the DTO (constructor cloned it).
        source.setX(777);
        assertEquals(1.0, dto.getLocation().getX(), 0.0);
    }

    @Test
    void getLocationReturnsAFreshCloneEachCall() {
        SpawnerDataDTO dto = dto(new Location(null, 5, 6, 7));
        assertNotSame(dto.getLocation(), dto.getLocation());
    }

    @Test
    void nullLocationIsSafe() {
        SpawnerDataDTO dto = dto(null);
        assertNull(dto.getLocation());
    }
}
