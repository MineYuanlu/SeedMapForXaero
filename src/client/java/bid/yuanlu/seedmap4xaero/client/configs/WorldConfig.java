package bid.yuanlu.seedmap4xaero.client.configs;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlag;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlagView;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

/**
 * 单个 (mainId, dim, mwId) 的世界配置。
 */
public class WorldConfig {
    private final ConfigData main;
    private Long seed; // null ↔ 未设置
    private final StructureBitFlag disabledStructure; // 位1+为变种位; 默认全 0 = 全部可见
    private @Nullable BitSet disabledBiomes; // null ↔ 全部启用
    private @Nullable BitSetView disabledBiomesView;

    WorldConfig(ConfigData main) {
        this.main = Objects.requireNonNull(main, "main");
        this.disabledStructure = new StructureBitFlag();
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

    /** 设置结构整体的可见性 (false=整类禁用) */
    public void setStructureEnabled(int type, boolean visible) {
        StructureType.byId(type);// check
        disabledStructure.setStructure(type, !visible);
        main.makeDirty();
    }

    /** 设置某个变种的可见性 (false=该变种禁用) */
    public void setVariantEnabled(int type, int variant, boolean visible) {
        StructureType.byId(type);// check
        disabledStructure.setVariant(type, variant, !visible);
        main.makeDirty();
    }

    public StructureBitFlagView getDisabledStructures() {
        return disabledStructure;
    }

    /** 结构整体可见的类型集合, 供生成/渲染层按类型过滤 */
    public BitSetView getStructureTypeSet() {
        BitSet set = new BitSet(StructureType.FEATURE_NUM);
        for (StructureType t : StructureType.values()) {
            if (!disabledStructure.isStructureSet(t.id))
                set.set(t.id);
        }
        return new BitSetView(set);
    }

    public void setBiomeDisabled(int id, boolean enabled) {
        if (disabledBiomes == null) {
            disabledBiomes = new BitSet();
            disabledBiomesView = new BitSetView(disabledBiomes);
        }
        disabledBiomes.set(id, enabled);
        main.makeDirty();
    }

    public BitSetView getDisabledBiomes() {
        if (disabledBiomesView != null)
            return disabledBiomesView;
        return BitSetView.EMPTY;
    }

    /** 写入到 DataOutput（由调用者实现）。 */
    void write(DataOutput out) throws IOException {
        out.writeInt(1);
        out.writeBoolean(this.seed != null);
        if (this.seed != null)
            out.writeLong(this.seed);
        disabledStructure.write(out); // 内部自带长度
        out.writeBoolean(disabledBiomes != null);
        if (disabledBiomes != null) {
            byte[] bits = disabledBiomes.toByteArray();
            out.writeInt(bits.length);
            out.write(bits);
        }
    }

    /** 从 DataInput 读取（由调用者实现）。 */
    static WorldConfig read(ConfigData main, DataInput in) throws IOException {
        final var wc = new WorldConfig(main);
        final int version = in.readInt();
        if (version == 1) {
            final boolean hasSeed = in.readBoolean();
            if (hasSeed)
                wc.seed = in.readLong();
            StructureBitFlag disabled = StructureBitFlag.read(in);
            wc.disabledStructure.setAll(disabled);
            if (in.readBoolean()) {
                int len = in.readInt();
                byte[] bits = new byte[len];
                in.readFully(bits);
                wc.disabledBiomes = BitSet.valueOf(bits);
                wc.disabledBiomesView = new BitSetView(wc.disabledBiomes);
            }
        } else if (version == 0) {
            // 旧布局: seed + enabledStructures(BitSet,可空) + disabledBiomes(BitSet,可空)
            final boolean hasSeed = in.readBoolean();
            if (hasSeed)
                wc.seed = in.readLong();
            BitSet enabledStructures = null;
            if (in.readBoolean()) {
                int len = in.readInt();
                byte[] bits = new byte[len];
                in.readFully(bits);
                enabledStructures = BitSet.valueOf(bits);
            }
            if (in.readBoolean()) {
                int len = in.readInt();
                byte[] bits = new byte[len];
                in.readFully(bits);
                wc.disabledBiomes = BitSet.valueOf(bits);
                wc.disabledBiomesView = new BitSetView(wc.disabledBiomes);
            }
            // enabledStructures 翻转成 disabledStructures; 变种位默认全 0 = 全部可见
            for (int id = 0; id < StructureType.FEATURE_NUM; id++) {
                boolean enabled = enabledStructures != null
                        ? enabledStructures.get(id)
                        : StructureType.defaultEnabled().get(id);
                if (!enabled)
                    wc.disabledStructure.setStructure(id, true);
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
                && Objects.equals(disabledStructure, that.disabledStructure)
                && Objects.equals(disabledBiomes, that.disabledBiomes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, seed, disabledStructure, disabledBiomes);
    }
}
