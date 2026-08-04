package bid.yuanlu.seedmap4xaero.client.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

class StructureBitFlagTest {

    @Test
    void defaultsAllVisible() {
        StructureBitFlag f = new StructureBitFlag();
        assertFalse(f.isStructureSet(0));
        assertFalse(f.isStructureSet(StructureType.VILLAGE.id));
        assertFalse(f.isVariantSet(StructureType.VILLAGE.id, 0));
        assertFalse(f.isVariantSet(StructureType.VILLAGE.id, 8));
    }

    @Test
    void structureBitIndependentFromVariantBit() {
        StructureBitFlag f = new StructureBitFlag();
        f.setVariant(StructureType.VILLAGE.id, 8, true); // 只关变种位
        assertTrue(f.isVariantSet(StructureType.VILLAGE.id, 8));
        assertFalse(f.isStructureSet(StructureType.VILLAGE.id), "variant toggle must not set structure bit");

        StructureBitFlag g = new StructureBitFlag();
        g.setStructure(StructureType.VILLAGE.id, true); // 只关整体位
        assertTrue(g.isStructureSet(StructureType.VILLAGE.id));
        assertFalse(g.isVariantSet(StructureType.VILLAGE.id, 8), "structure toggle must not set variant bit");
    }

    @Test
    void disablingStructureHidesTypeNotVariant() {
        StructureBitFlag f = new StructureBitFlag();
        f.setStructure(StructureType.VILLAGE.id, true);
        assertTrue(f.isStructureSet(StructureType.VILLAGE.id));
        // 可见性 = !isStructureSet && !isVariantSet (调用方组合)
        assertFalse(!f.isStructureSet(StructureType.VILLAGE.id));
    }

    @Test
    void disablingSingleVariantHidesOnlyIt() {
        StructureBitFlag f = new StructureBitFlag();
        f.setVariant(StructureType.VILLAGE.id, 8, true);
        assertTrue(f.isVariantSet(StructureType.VILLAGE.id, 8));
        assertFalse(f.isVariantSet(StructureType.VILLAGE.id, 0));
        assertFalse(f.isVariantSet(StructureType.VILLAGE.id, 9));
    }

    @Test
    void flipToggles() {
        StructureBitFlag f = new StructureBitFlag();
        f.flipStructure(StructureType.IGLOO.id);
        assertTrue(f.isStructureSet(StructureType.IGLOO.id));
        f.flipStructure(StructureType.IGLOO.id);
        assertFalse(f.isStructureSet(StructureType.IGLOO.id));

        f.flipVariant(StructureType.IGLOO.id, 1);
        assertTrue(f.isVariantSet(StructureType.IGLOO.id, 1));
        f.flipVariant(StructureType.IGLOO.id, 1);
        assertFalse(f.isVariantSet(StructureType.IGLOO.id, 1));
    }

    @Test
    void writeReadRoundTrip() throws IOException {
        StructureBitFlag f = new StructureBitFlag();
        f.setStructure(StructureType.STRONGHOLD.id, true);
        f.setStructure(StructureType.VILLAGE.id, true);
        f.setVariant(StructureType.VILLAGE.id, 8, true);
        f.setVariant(StructureType.IGLOO.id, 1, true);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            f.write(out);
        }
        StructureBitFlag read = StructureBitFlag.read(
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        assertEquals(f, read);
        assertTrue(read.isStructureSet(StructureType.STRONGHOLD.id));
        assertTrue(read.isVariantSet(StructureType.VILLAGE.id, 8));
        assertFalse(read.isVariantSet(StructureType.VILLAGE.id, 0));
    }

    @Test
    void emptyRoundTrip() throws IOException {
        StructureBitFlag f = new StructureBitFlag();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            f.write(out);
        }
        StructureBitFlag read = StructureBitFlag.read(
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        assertEquals(f, read);
    }

    @Test
    void equalsHashCode() {
        StructureBitFlag a = new StructureBitFlag();
        StructureBitFlag b = new StructureBitFlag();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        a.setVariant(StructureType.GEODE.id, 1, true);
        assertNotEquals(a, b);
        StructureBitFlag copy = new StructureBitFlag();
        copy.setVariant(StructureType.GEODE.id, 1, true);
        assertEquals(a, copy);
        assertNotEquals(null, a);
    }

    @Test
    void outOfRangeReadsFalse() {
        StructureBitFlag f = new StructureBitFlag();
        assertFalse(f.isStructureSet(-1));
        assertFalse(f.isStructureSet(StructureType.FEATURE_NUM + 100));
        assertFalse(f.isVariantSet(0, -1));
        assertFalse(f.isVariantSet(0, 32));
    }

    @Test
    void badVariantThrowsOnWrite() {
        StructureBitFlag f = new StructureBitFlag();
        assertThrows(IllegalArgumentException.class, () -> f.setVariant(0, -1, true));
        assertThrows(IllegalArgumentException.class, () -> f.setVariant(0, 32, true));
        assertThrows(IllegalArgumentException.class, () -> f.flipVariant(0, 33));
    }
}
