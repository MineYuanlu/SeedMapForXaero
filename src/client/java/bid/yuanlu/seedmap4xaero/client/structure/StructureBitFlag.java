package bid.yuanlu.seedmap4xaero.client.structure;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * 生成结构特有的bit flag
 * <p>
 * 本质为BitSet[]的优化版
 */
public class StructureBitFlag implements StructureBitFlagView {
    /**
     * key: structure id
     * value, bit 0: 整体标记
     * value, bit 1+: 每个variant标记
     * <p>
     * variant 码有效范围 0..30（bit 1..bit 31；31 会使 {@code 1 << (variant+1)}
     * 回卷污染 bit 0，setter 必须拒绝）；目前 variant 最大为 12, 小于 int 的 32 位,
     * 故使用 int[] 存储;
     * 如果后续演进>=31且<63, 可以使用long[]存储;
     * 最后可以使用BitSet[]/boolean[][]存储
     */
    private volatile int flags[];

    public StructureBitFlag() {
        flags = new int[StructureType.FEATURE_NUM];
    }

    public StructureBitFlag(int[] flags) {
        this.flags = Arrays.copyOf(flags, flags.length);
    }

    /** 读取结构整体的标记 */
    @Override
    public boolean isStructureSet(int structureId) {
        if (structureId < 0 || structureId >= flags.length)
            return false; // structure id out of range
        return (flags[structureId] & 1) != 0; // bit 0
    }

    /** 翻转结构整体的标记 */
    public synchronized void flipStructure(int structureId) {
        ensureCapacity(structureId);
        flags[structureId] ^= 1;
    }

    /** 设置结构整体的标记 */
    public synchronized void setStructure(int structureId, boolean enabled) {
        ensureCapacity(structureId);
        if (enabled) {
            flags[structureId] |= 1;
        } else {
            flags[structureId] &= ~1;
        }
    }

    /** 读取结构某个variant的标记 (只判变种位, 是否整体禁用的组合由调用方决定) */
    @Override
    public boolean isVariantSet(int structureId, int variant) {
        if (structureId < 0 || structureId >= flags.length)
            return false; // structure id out of range
        if (variant < 0 || variant > 30)
            return false; // variant out of range (31 会使位移回卷到 bit 0)
        return (flags[structureId] & (1 << (variant + 1))) != 0; // bit 1+ variant
    }

    /** 翻转结构某个variant的标记 */
    public synchronized void flipVariant(int structureId, int variant) {
        ensureCapacity(structureId);
        if (variant < 0 || variant > 30)
            throw new IllegalArgumentException("variant must be non-negative and less than 31: " + variant);
        flags[structureId] ^= 1 << (variant + 1);
    }

    /** 设置结构某个variant的标记 */
    public synchronized void setVariant(int structureId, int variant, boolean enabled) {
        ensureCapacity(structureId);
        if (variant < 0 || variant > 30)
            throw new IllegalArgumentException("variant must be non-negative and less than 31: " + variant);
        if (enabled) {
            flags[structureId] |= 1 << (variant + 1);
        } else {
            flags[structureId] &= ~(1 << (variant + 1));
        }
    }

    /** 用另一个 flag 的位覆盖本对象 (用于反序列化) */
    public synchronized void setAll(StructureBitFlag other) {
        flags = Arrays.copyOf(other.flags, other.flags.length);
    }

    /** 容量（可安全下标的 id 上界；JSON 导出/诊断遍历用）。 */
    public synchronized int capacity() {
        return flags.length;
    }

    /**
     * 原始位布局：bit 0 = 整类位，bit (1+v) = 变种 v 位（导出/诊断用）。
     * 越界 id 返回 0。
     */
    public synchronized int rawFlags(int structureId) {
        if (structureId < 0 || structureId >= flags.length)
            return 0;
        return flags[structureId];
    }

    /** 按原始位布局写入（导入用，位含义同 {@link #rawFlags}）；0 为无操作。 */
    public synchronized void setRawFlags(int structureId, int raw) {
        if (raw == 0)
            return;
        ensureCapacity(structureId);
        flags[structureId] = raw;
    }

    private void ensureCapacity(int structureId) {
        if (structureId < 0)
            throw new IllegalArgumentException("structureId must be non-negative: " + structureId);
        if (structureId >= flags.length) {
            final int[] newFlags = new int[structureId + 1];
            System.arraycopy(flags, 0, newFlags, 0, flags.length);
            flags = newFlags;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof StructureBitFlag that))
            return false;
        return Arrays.equals(flags, that.flags);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(flags);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(0);
        out.writeInt(flags.length);
        for (int flag : flags) {
            out.writeInt(flag);
        }
    }

    public static StructureBitFlag read(DataInput in) throws IOException {
        final int version = in.readInt();
        if (version == 0) {
            final int length = in.readInt();
            final int[] flags = new int[length];
            for (int i = 0; i < length; i++) {
                flags[i] = in.readInt();
            }
            return new StructureBitFlag(flags);// 一次拷贝, 懒得优化了
        } else {
            throw new IOException("Unsupported StructureBitFlag version: " + version);
        }
    }
}
