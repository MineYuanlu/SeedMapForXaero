package bid.yuanlu.seedmap4xaero.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;

import org.junit.jupiter.api.Test;

class BitSetViewTest {

    @Test
    void emptyIsAlwaysEmpty() {
        assertTrue(BitSetView.EMPTY.isEmpty());
        assertEquals(0, BitSetView.EMPTY.toByteArray().length);
        assertEquals(-1, BitSetView.EMPTY.nextSetBit(0));
        assertFalse(BitSetView.EMPTY.get(0));
    }

    @Test
    void viewReflectsBackingBitSet() {
        BitSet bits = new BitSet();
        BitSetView view = new BitSetView(bits);
        bits.set(3);
        bits.set(10);
        assertTrue(view.get(3));
        assertTrue(view.get(10));
        assertEquals(3, view.nextSetBit(0));
        assertEquals(10, view.nextSetBit(4));
        assertEquals(-1, view.nextSetBit(11));
        assertFalse(view.isEmpty());
    }

    @Test
    void cloneIsDetached() {
        BitSet bits = new BitSet();
        bits.set(5);
        BitSetView view = new BitSetView(bits);
        BitSet copy = view.cloneBitSet();
        assertNotSame(bits, copy);
        assertEquals(bits, copy);
        copy.clear(5);
        assertTrue(bits.get(5), "clone must not mutate backing set");
    }

    @Test
    void equalsAndHashCode() {
        BitSet a = new BitSet();
        BitSet b = new BitSet();
        a.set(1, 5);
        b.set(1, 5);
        assertEquals(new BitSetView(a), new BitSetView(b));
        assertEquals(new BitSetView(a).hashCode(), new BitSetView(b).hashCode());
        b.set(9);
        assertFalse(new BitSetView(a).equals(new BitSetView(b)));
    }

    @Test
    void toByteArrayRoundTrip() {
        BitSet bits = new BitSet();
        for (int i : new int[] {0, 7, 8, 63, 64, 255})
            bits.set(i);
        byte[] bytes = new BitSetView(bits).toByteArray();
        assertEquals(bits, BitSet.valueOf(bytes));
    }

    @Test
    void nullRejected() {
        assertThrows(NullPointerException.class, () -> new BitSetView(null));
    }
}
