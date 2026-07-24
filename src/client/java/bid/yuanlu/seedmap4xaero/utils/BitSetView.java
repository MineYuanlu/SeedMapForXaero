package bid.yuanlu.seedmap4xaero.utils;

import java.util.BitSet;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

public final class BitSetView {
    private final @NotNull BitSet bitSet;

    public BitSetView(@NotNull BitSet bitSet) {
        this.bitSet = Objects.requireNonNull(bitSet);
    }

    public @NotNull BitSet cloneBitSet(){
        return (BitSet) this.bitSet.clone();
    }

    /** @see BitSet#isEmpty() */
    public boolean isEmpty(){
        return this.bitSet.isEmpty();
    }

    /** @see BitSet#nextSetBit(int) */
    public int nextSetBit(int fromIndex){
        return this.bitSet.nextSetBit(fromIndex);
    }

}
