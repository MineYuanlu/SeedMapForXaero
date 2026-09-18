package bid.yuanlu.seedmap4xaero.client.configs.basic;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeKeys;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlag;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlagView;
import bid.yuanlu.seedmap4xaero.client.structure.StructureInfo;
import bid.yuanlu.seedmap4xaero.client.structure.StructureKeys;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureTypes;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

/**
 * 单个 (mainId, dim, mwId) 的世界配置。
 * <p>
 * 持久化 key 约定：结构/生物群系一律存稳定字符串 key
 * （{@code minecraft:Village} / {@code minecraft:plains}，见 {@link StructureKeys}
 * 与 {@link BiomeKeys}）；运行时仍以 int id 索引（{@link StructureBitFlag} /
 * {@link BitSet}），key↔id 转换只发生在 JSON 读写边界。无法识别的 key 以
 * orphan 形式原样保留（数据包结构移除等场景下数据不丢，恢复后自动重挂）。
 */
public class WorldConfig {
    private final ConfigData main;
    private Long seed; // null ↔ 未设置
    private final StructureBitFlag disabledStructure; // 位1+为变种位; 默认全 0 = 全部可见
    private @Nullable BitSet disabledBiomes; // null ↔ 全部启用
    private @Nullable BitSetView disabledBiomesView;
    private @Nullable String mcVersion; // null ↔ 跟随客户端版本

    /** JSON 中无法解析回 id 的结构禁用条目（key → 原样条目），写出时原样并回。 */
    private @Nullable JsonObject orphanStructureFlags;
    /** JSON 中无法解析回 id 的禁用生物群系 key，写出时原样并回。 */
    private @Nullable List<String> orphanBiomeKeys;

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
        Objects.requireNonNull(StructureTypes.byId(type), "unknown structure id: " + type);
        disabledStructure.setStructure(type, !visible);
        main.makeDirty();
    }

    /** 设置某个变种的可见性 (false=该变种禁用) */
    public void setVariantEnabled(int type, int variant, boolean visible) {
        Objects.requireNonNull(StructureTypes.byId(type), "unknown structure id: " + type);
        disabledStructure.setVariant(type, variant, !visible);
        main.makeDirty();
    }

    public StructureBitFlagView getDisabledStructures() {
        return disabledStructure;
    }

    /** 结构整体可见的类型集合, 供生成/渲染层按类型过滤 */
    public BitSetView getStructureTypeSet() {
        BitSet set = new BitSet(StructureTypes.capacity());
        for (StructureInfo t : StructureTypes.all()) {
            if (!disabledStructure.isStructureSet(t.id()))
                set.set(t.id());
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

    /** 世界生成的 MC 版本 (如 "1.21.9"), null 表示跟随客户端。 */
    public @Nullable String mcVersion() {
        return mcVersion;
    }

    public void mcVersion(@Nullable String v) {
        if (Objects.equals(mcVersion, v))
            return;
        this.mcVersion = v;
        main.makeDirty();
    }

    // ─── JSON 持久化 (现行格式) ─────────────────────────────────

    JsonObject writeJson() {
        var json = new JsonObject();
        if (seed != null)
            json.addProperty("seed", seed);
        if (mcVersion != null)
            json.addProperty("mcVersion", mcVersion);

        var disabled = new JsonObject();
        for (int id = 0; id < disabledStructure.capacity(); id++) {
            int raw = disabledStructure.rawFlags(id);
            if (raw == 0)
                continue;
            var entry = new JsonObject();
            if ((raw & 1) != 0)
                entry.addProperty("whole", true);
            var variants = new JsonArray();
            for (int v = 0; v <= 30; v++)
                if ((raw & (1 << (v + 1))) != 0)
                    variants.add(v);
            if (variants.size() > 0)
                entry.add("variants", variants);
            disabled.add(StructureKeys.persistedKey(id), entry);
        }
        if (orphanStructureFlags != null) {
            for (var e : orphanStructureFlags.entrySet())
                disabled.add(e.getKey(), e.getValue());
        }
        if (disabled.size() > 0)
            json.add("disabledStructures", disabled);

        var biomes = new JsonArray();
        if (disabledBiomes != null) {
            for (int id = disabledBiomes.nextSetBit(0); id >= 0; id = disabledBiomes.nextSetBit(id + 1))
                biomes.add(BiomeKeys.idToKey(id));
        }
        if (orphanBiomeKeys != null) {
            for (String key : orphanBiomeKeys)
                biomes.add(key);
        }
        if (biomes.size() > 0)
            json.add("disabledBiomes", biomes);
        return json;
    }

    static WorldConfig readJson(ConfigData main, JsonObject json) throws IOException {
        try {
            var wc = new WorldConfig(main);
            if (json.has("seed") && !json.get("seed").isJsonNull())
                wc.seed = json.get("seed").getAsLong();
            if (json.has("mcVersion") && !json.get("mcVersion").isJsonNull())
                wc.mcVersion = json.get("mcVersion").getAsString();

            if (json.has("disabledStructures")) {
                var disabled = json.getAsJsonObject("disabledStructures");
                for (var e : disabled.entrySet()) {
                    int id = StructureKeys.resolveId(e.getKey());
                    if (id < 0 || !(e.getValue() instanceof JsonObject entry)) {
                        // 未知结构 key → orphan 原样保留 (数据包结构移除等场景)
                        wc.orphan(e.getKey(), e.getValue());
                        continue;
                    }
                    if (entry.has("whole") && entry.get("whole").getAsBoolean())
                        wc.disabledStructure.setStructure(id, true);
                    if (entry.has("variants")) {
                        for (JsonElement v : entry.getAsJsonArray("variants")) {
                            int variant = v.getAsInt();
                            if (variant < 0 || variant > 30)
                                continue; // 非法变种码容错跳过 (手改文件宽容)
                            wc.disabledStructure.setVariant(id, variant, true);
                        }
                    }
                }
            }

            if (json.has("disabledBiomes")) {
                wc.disabledBiomes = new BitSet();
                wc.disabledBiomesView = new BitSetView(wc.disabledBiomes);
                for (JsonElement el : json.getAsJsonArray("disabledBiomes")) {
                    String key = el.getAsString();
                    int id = BiomeKeys.keyToId(key);
                    if (id >= 0)
                        wc.disabledBiomes.set(id);
                    else
                        wc.orphanBiome(key);
                }
            }
            return wc;
        } catch (RuntimeException e) {
            throw new IOException("Malformed world config", e);
        }
    }

    private void orphan(String key, JsonElement value) {
        if (orphanStructureFlags == null)
            orphanStructureFlags = new JsonObject();
        orphanStructureFlags.add(key, value);
    }

    private void orphanBiome(String key) {
        if (orphanBiomeKeys == null)
            orphanBiomeKeys = new ArrayList<>();
        orphanBiomeKeys.add(key);
    }

    // ─── legacy .sm4x 二进制 (frozen: 仅旧格式迁移读取与测试 fixture) ───

    /** 写入到 DataOutput（由调用者实现）。 */
    void write(DataOutput out) throws IOException {
        out.writeInt(2);
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
        out.writeBoolean(mcVersion != null);
        if (mcVersion != null)
            out.writeUTF(mcVersion);
    }

    /** 从 DataInput 读取（由调用者实现）。 */
    static WorldConfig read(ConfigData main, DataInput in) throws IOException {
        final var wc = new WorldConfig(main);
        final int version = in.readInt();
        if (version == 1 || version == 2) {
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
            if (version >= 2) {
                if (in.readBoolean())
                    wc.mcVersion = in.readUTF();
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
                && Objects.equals(disabledBiomes, that.disabledBiomes)
                && Objects.equals(mcVersion, that.mcVersion)
                && Objects.equals(orphanStructureFlags, that.orphanStructureFlags)
                && Objects.equals(orphanBiomeKeys, that.orphanBiomeKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, seed, disabledStructure, disabledBiomes, mcVersion,
                orphanStructureFlags, orphanBiomeKeys);
    }

    /** 世界配置文档的 JSON 编解码器，配合 {@code JsonConfigFile} 使用。 */
    public static final JsonCodec<WorldConfig> JSON_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(WorldConfig data) {
            return data.writeJson();
        }

        @Override
        public WorldConfig read(JsonObject json) throws IOException {
            // 独立使用 (单测) 时挂一个空宿主; 生产路径经 ConfigData.readJson 传入真实宿主
            return WorldConfig.readJson(new ConfigData(), json);
        }
    };
}
