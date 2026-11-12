package me.roviczzz.marp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void locationSnapshotKeepsFacing() {
        LocationSnapshot location = new LocationSnapshot("world", 1.25, 64.0, -3.5, 90.0f, -10.0f);
        assertEquals("world", location.world());
        assertEquals(90.0f, location.yaw());
        assertEquals(-10.0f, location.pitch());
    }

    @Test
    void iconSpecComparesByteContent() {
        byte[] bytes = {1, 2, 3};
        IconSpec first = new IconSpec("STONE", bytes);
        IconSpec second = new IconSpec("STONE", bytes.clone());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new IconSpec("STONE", new byte[] {9}));
        assertTrue(first.hasItemBytes());
        assertFalse(IconSpec.material("STONE").hasItemBytes());
    }

    @Test
    void homeEqualityIncludesOwnershipAndMetadata() {
        UUID owner = UUID.randomUUID();
        LocationSnapshot location = new LocationSnapshot("world", 0, 64, 0, 0, 0);
        Home home = new Home(
                1L, owner, "base", "base", location, IconSpec.material("GRASS_BLOCK"), "Base", List.of("line"), 0, 10, 20);
        Home same = new Home(
                1L, owner, "base", "base", location, IconSpec.material("GRASS_BLOCK"), "Base", List.of("line"), 0, 10, 20);

        assertEquals(home, same);
        assertEquals(List.of("line"), home.lore());
        assertNotEquals(home, home.withMetadata(IconSpec.material("STONE"), "Base", List.of(), 1, 30));
    }
}
