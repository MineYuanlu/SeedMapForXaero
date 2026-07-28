package bid.yuanlu.seedmap4xaero.utils;

import java.util.BitSet;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

public final class BitSetView {
    public static final BitSetView EMPTY = new BitSetView(new BitSet());
    private final @NotNull BitSet bitSet;

    public BitSetView(@NotNull BitSet bitSet) {
        this.bitSet = Objects.requireNonNull(bitSet);
    }

    public @NotNull BitSet cloneBitSet() {
        return (BitSet) this.bitSet.clone();
    }

    /** @see BitSet#isEmpty() */
    public boolean isEmpty() {
        return this.bitSet.isEmpty();
    }

    /** @see BitSet#size() */
    public int size() {
        return this.bitSet.size();
    }

    /** @see BitSet#nextSetBit(int) */
    public int nextSetBit(int fromIndex) {
        return this.bitSet.nextSetBit(fromIndex);
    }

    /** @see BitSet#get(int) */
    public boolean get(int bitIndex) {
        return this.bitSet.get(bitIndex);
    }

    /** @see BitSet#toByteArray() */
    public byte[] toByteArray() {
        return this.bitSet.toByteArray();
    }

    @Override
    public String toString() {
        return this.bitSet.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        BitSetView that = (BitSetView) o;
        return Objects.equals(bitSet, that.bitSet);
    }

    @Override
    public int hashCode() {
        return this.bitSet.hashCode();
    }

}
