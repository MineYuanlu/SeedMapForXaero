package bid.yuanlu.seedmap4xaero.client.configs;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

/**
 * 单个 (mainId, dim, mwId) 的世界配置。
 */
public class WorldConfig {
    private final ConfigData main;
    private Long seed; // null ↔ 未设置
    private @Nullable BitSet enabledStructures; // null ↔ 使用默认
    private @Nullable BitSetView enabledStructuresView;

    WorldConfig(ConfigData main) {
        this.main = Objects.requireNonNull(main, "main");
    }

    public @Nullable Long seed() {
        return seed;
    }

    public void seed(@Nullable Long s) {
        if (Objects.equals(seed, s))
            return;
        if (s != null)
            main.useSeed(s);
        this.seed = s;
    }

    public void setStructureEnabled(int type, boolean enabled) {
        StructureType.byId(type);// check
        if (enabledStructures == null) {
            enabledStructures = StructureType.defaultEnabled().cloneBitSet();
            enabledStructuresView = new BitSetView(enabledStructures);
        }
        enabledStructures.set(type, enabled);
        main.makeDirty();
    }

    public BitSetView getEnabledStructures() {
        if (enabledStructuresView != null)
            return enabledStructuresView;
        return StructureType.defaultEnabled();
    }

    /** 写入到 DataOutput（由调用者实现）。 */
    void write(DataOutput out) throws IOException {
        out.writeInt(0);
        out.writeBoolean(this.seed != null);
        if (this.seed != null)
            out.writeLong(this.seed);
        out.writeBoolean(enabledStructures != null);
        if (enabledStructures != null) {
            byte[] bits = enabledStructures.toByteArray();
            out.writeInt(bits.length);
            out.write(bits);
        }
    }

    /** 从 DataInput 读取（由调用者实现）。 */
    static WorldConfig read(ConfigData main, DataInput in) throws IOException {
        final var wc = new WorldConfig(main);
        final int version = in.readInt();
        if (version == 0) {
            final boolean hasSeed = in.readBoolean();
            if (hasSeed)
                wc.seed = in.readLong();
            if (in.readBoolean()) {
                int len = in.readInt();
                byte[] bits = new byte[len];
                in.readFully(bits);
                wc.enabledStructures = BitSet.valueOf(bits);
                wc.enabledStructuresView = new BitSetView(wc.enabledStructures);
            }
        } else {
            throw new IOException("Unsupported WorldConfig version: " + version);
        }
        return wc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof WorldConfig that))
            return false;
        return Objects.equals(main, that.main)
                && Objects.equals(seed, that.seed)
                && Objects.equals(enabledStructures, that.enabledStructures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, seed, enabledStructures);
    }
}
