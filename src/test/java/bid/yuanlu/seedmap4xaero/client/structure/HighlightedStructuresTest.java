package bid.yuanlu.seedmap4xaero.client.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.world.level.Level;

class HighlightedStructuresTest {

    @AfterEach
    void tearDown() {
        HighlightedStructures.clear();
    }

    @Test
    void toggleAddsThenRemoves() {
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0));
        HighlightedStructures.toggle(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0);
        assertTrue(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0));
        HighlightedStructures.toggle(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0);
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0));
    }

    @Test
    void keyedByDimension() {
        HighlightedStructures.toggle(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0);
        assertTrue(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0));
        assertFalse(HighlightedStructures.contains(Level.NETHER, 10, 20, StructureType.VILLAGE, 0));
    }

    @Test
    void keyedByCoordinate() {
        HighlightedStructures.toggle(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0);
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 11, 20, StructureType.VILLAGE, 0));
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 10, 21, StructureType.VILLAGE, 0));
    }

    @Test
    void keyedByType() {
        HighlightedStructures.toggle(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 0);
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.VILLAGE, 1),
                "different variant must not match");
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 10, 20, StructureType.DESERT_PYRAMID, 0),
                "different type must not match");
    }

    @Test
    void clearRemovesAll() {
        HighlightedStructures.toggle(Level.OVERWORLD, 1, 2, StructureType.VILLAGE, 0);
        HighlightedStructures.toggle(Level.NETHER, 3, 4, StructureType.FORTRESS, 0);
        HighlightedStructures.clear();
        assertEquals(0, HighlightedStructures.all().size());
        assertFalse(HighlightedStructures.contains(Level.OVERWORLD, 1, 2, StructureType.VILLAGE, 0));
    }
}
