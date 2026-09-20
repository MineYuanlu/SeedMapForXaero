package bid.yuanlu.seedmap4xaero.client.configs.basic;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * basic 配置文档（{@code server_config.sm4x}）的数据体：服务器级设置 + worlds 表 + 种子历史。
 * <p>
 * 只负责数据与自身序列化；magic word 帧包装、原子写、损坏回退在
 * {@link bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile}。
 * 
 * <pre>
 * worlds : { mwId → WorldConfig }
 * all_seeds : [ SeedEntry … ]
 * theme : String|null
 * invisibleBiomes : Boolean
 * invisibleStructures : Boolean
 * structureIconSize : float
 * lootPreview + lootDisplayMode (version 1+)
 * </pre>
 */
public class ConfigData {
    private static final int MAX_SEEDS = 1000;
    // mwId → 世界配置
    private final ConcurrentHashMap<String, WorldConfig> worlds = new ConcurrentHashMap<>();
    private final ArrayList<SeedEntry> allSeeds = new ArrayList<>();

    @Nullable
    String theme;
    boolean invisibleBiomes = false;
    boolean invisibleStructures = false;
    float structureIconSize = 1.0f;
    boolean lootPreview = false;
    LootDisplayMode lootDisplayMode = LootDisplayMode.QUICK_PEEK;

    AtomicBoolean dirty = new AtomicBoolean(false);

    ConfigData() {
    }

    void makeDirty() {
        this.dirty.set(true);
    }

    @Nullable
    public WorldConfig getWorld(String mwId) {
        return worlds.get(mwId);
    }

    @NotNull
    public WorldConfig getOrCreateWorld(String mwId) {
        return worlds.computeIfAbsent(mwId, k -> new WorldConfig(this));
    }

    public @Nullable String getTheme() {
        return theme;
    }

    public boolean isInvisibleBiomes() {
        return invisibleBiomes;
    }

    public boolean isInvisibleStructures() {
        return invisibleStructures;
    }

    public float getStructureIconSize() {
        return structureIconSize;
    }

    public boolean isLootPreview() {
        return lootPreview;
    }

    public LootDisplayMode getLootDisplayMode() {
        return lootDisplayMode;
    }

    /** 种子历史的一条只读快照（/sm4x history seed 列表用）。 */
    public record SeedHistoryEntry(long seed, String lastUsed) {
    }

    /** 种子历史（MRU 序）只读快照。 */
    public java.util.List<SeedHistoryEntry> getSeedHistory() {
        synchronized (allSeeds) {
            var out = new ArrayList<SeedHistoryEntry>(allSeeds.size());
            for (SeedEntry e : allSeeds)
                out.add(new SeedHistoryEntry(e.seed, e.lastUsed()));
            return java.util.List.copyOf(out);
        }
    }

    public synchronized void setTheme(@Nullable String theme) {
        if (Objects.equals(this.theme, theme))
            return;
        this.theme = theme;
        makeDirty();
    }

    public synchronized void setInvisibleBiomes(boolean invisibleBiomes) {
        if (this.invisibleBiomes == invisibleBiomes)
            return;
        this.invisibleBiomes = invisibleBiomes;
        makeDirty();
    }

    public synchronized void setInvisibleStructures(boolean invisibleStructures) {
        if (this.invisibleStructures == invisibleStructures)
            return;
        this.invisibleStructures = invisibleStructures;
        makeDirty();
    }

    public synchronized void setStructureIconSize(float size) {
        size = Math.max(0.05f, Math.min(2.0f, size));
        if (this.structureIconSize == size)
            return;
        this.structureIconSize = size;
        makeDirty();
    }

    public synchronized void setLootPreview(boolean lootPreview) {
        if (this.lootPreview == lootPreview)
            return;
        this.lootPreview = lootPreview;
        makeDirty();
    }

    public synchronized void setLootDisplayMode(LootDisplayMode mode) {
        if (this.lootDisplayMode == mode)
            return;
        this.lootDisplayMode = mode;
        makeDirty();
    }

    /** 通知使用了某个种子, 更新种子列表 */
    void useSeed(long seed) {
        try {
            synchronized (allSeeds) {
                for (int i = 0; i < allSeeds.size(); i++) {
                    SeedEntry e = allSeeds.get(i);
                    if (seed == e.seed) {
                        e.update();
                        allSeeds.remove(i);
                        allSeeds.addFirst(e);
                        return;
                    }
                }
                allSeeds.addFirst(SeedEntry.now(seed));

                if (allSeeds.size() > MAX_SEEDS) {
                    allSeeds.removeLast();
                }
            }
        } finally {
            makeDirty();
        }
    }

    // ─── JSON 持久化 (现行格式) ─────────────────────────────────

    /** 写出文档体（不含文件级布局；{@code null} 字段省略）。 */
    private synchronized JsonObject writeJson() {
        var json = new JsonObject();
        json.addProperty("version", 1);
        if (theme != null)
            json.addProperty("theme", theme);
        json.addProperty("invisibleBiomes", invisibleBiomes);
        json.addProperty("invisibleStructures", invisibleStructures);
        json.addProperty("structureIconSize", structureIconSize);
        json.addProperty("lootPreview", lootPreview);
        json.addProperty("lootDisplayMode", lootDisplayMode.name());

        var worldsJson = new JsonObject();
        for (final var worldEntry : worlds.entrySet()) {
            worldsJson.add(worldEntry.getKey(), worldEntry.getValue().writeJson());
        }
        json.add("worlds", worldsJson);

        synchronized (allSeeds) {
            var history = new JsonArray();
            for (final var seedEntry : allSeeds) {
                var o = new JsonObject();
                o.addProperty("seed", seedEntry.seed);
                o.addProperty("lastUsed", seedEntry.lastUsed());
                history.add(o);
            }
            json.add("seedHistory", history);
        }
        return json;
    }

    /** 从文档体读取：缺失字段落默认值，未知字段忽略（双向前向兼容）。 */
    private static ConfigData readJson(JsonObject json) throws IOException {
        try {
            final var config = new ConfigData();
            if (json.has("theme") && !json.get("theme").isJsonNull())
                config.theme = json.get("theme").getAsString();
            if (json.has("invisibleBiomes"))
                config.invisibleBiomes = json.get("invisibleBiomes").getAsBoolean();
            if (json.has("invisibleStructures"))
                config.invisibleStructures = json.get("invisibleStructures").getAsBoolean();
            if (json.has("structureIconSize"))
                config.structureIconSize = json.get("structureIconSize").getAsFloat();
            if (json.has("lootPreview"))
                config.lootPreview = json.get("lootPreview").getAsBoolean();
            if (json.has("lootDisplayMode")) {
                try {
                    config.lootDisplayMode = LootDisplayMode.valueOf(json.get("lootDisplayMode").getAsString());
                } catch (IllegalArgumentException unknownName) {
                    config.lootDisplayMode = LootDisplayMode.QUICK_PEEK; // 单字段容错, 不整体回退
                }
            }

            if (json.has("worlds")) {
                var worldsJson = json.getAsJsonObject("worlds");
                for (final var e : worldsJson.entrySet()) {
                    config.worlds.put(e.getKey(), WorldConfig.readJson(config, e.getValue().getAsJsonObject()));
                }
            }

            if (json.has("seedHistory")) {
                synchronized (config.allSeeds) {
                    for (JsonElement el : json.getAsJsonArray("seedHistory")) {
                        var o = el.getAsJsonObject();
                        long seed = o.get("seed").getAsLong();
                        String lastUsed = o.has("lastUsed") && !o.get("lastUsed").isJsonNull()
                                ? o.get("lastUsed").getAsString()
                                : Instant.now().toString();
                        config.allSeeds.add(new SeedEntry(seed, lastUsed));
                    }
                    while (config.allSeeds.size() > MAX_SEEDS)
                        config.allSeeds.removeLast();
                }
            }
            return config;
        } catch (RuntimeException e) {
            throw new IOException("Malformed server config", e);
        }
    }

    /** basic 配置文档的 JSON 编解码器，配合 {@code JsonConfigFile} 使用。 */
    public static final JsonCodec<ConfigData> JSON_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(ConfigData data) {
            return data.writeJson();
        }

        @Override
        public ConfigData read(JsonObject json) throws IOException {
            return ConfigData.readJson(json);
        }
    };

    // ─── legacy .sm4x 二进制 (frozen: 仅旧格式迁移读取与测试 fixture) ───

    /** basic 文档的 legacy 二进制编解码器；仅旧格式迁移读取与测试 fixture 使用。 */
    public static final Sm4xCodec<ConfigData> LEGACY_CODEC = new Sm4xCodec<>(){
        @Override
        public void write(ConfigData data, DataOutputStream out) throws IOException {
            data.write(out);
        }

        @Override
        public ConfigData read(DataInputStream in) throws IOException {
            return ConfigData.read(in);
        }
    };

    /** 写入数据体（不含 magic/version 帧包装）。 */
    private synchronized void write(DataOutputStream out) throws IOException {
        out.writeInt(1);
        out.writeInt(worlds.size());
        for (final var worldEntry : worlds.entrySet()) {
            out.writeUTF(worldEntry.getKey());
            worldEntry.getValue().write(out);
        }

        out.writeBoolean(theme != null);
        if (theme != null)
            out.writeUTF(theme);

        out.writeBoolean(invisibleBiomes);
        out.writeBoolean(invisibleStructures);
        out.writeFloat(structureIconSize);

        synchronized (allSeeds) {
            out.writeInt(allSeeds.size());
            for (final var seedEntry : allSeeds) {
                seedEntry.write(out);
            }
        }
        out.writeBoolean(lootPreview);
        out.writeByte(lootDisplayMode.ordinal());
    }

    /** 从 DataInput 读取数据体（不含 magic/version 帧包装）。 */
    private static ConfigData read(DataInputStream in) throws IOException {
        final var version = in.readInt();
        final var config = new ConfigData();
        if (version == 0 || version == 1) {
            final var dimSize = in.readInt();
            for (int i = 0; i < dimSize; i++) {
                final var mwId = in.readUTF();
                config.worlds.put(mwId, WorldConfig.read(config, in));
            }

            config.theme = in.readBoolean() ? in.readUTF() : null;
            config.invisibleBiomes = in.readBoolean();
            config.invisibleStructures = in.readBoolean();
            config.structureIconSize = in.readFloat();

            final var seedSize = in.readInt();
            for (int i = 0; i < seedSize; i++) {
                config.allSeeds.add(SeedEntry.read(in));
            }
            
            if (version == 1){
                config.lootPreview = in.readBoolean();
                config.lootDisplayMode = LootDisplayMode.values()[in.readByte()];
            }

        } else {
            throw new IOException("Unsupported ConfigData version: " + version);
        }
        return config;
    }

    /**
     * all_seeds 历史中的一条种子记录。
     * <p>
     * 二进制序列化由调用者通过 {@link #write(DataOutput)} / {@link #read(DataInput)} 完成。
     */
    private static class SeedEntry {

        /** 种子值 */
        final long seed;
        /** ISO-8601 时间戳 */
        @NotNull
        private String lastUsed;

        SeedEntry(long seed, @NotNull String lastUsed) {
            this.seed = seed;
            this.lastUsed = Objects.requireNonNull(lastUsed, "lastUsed");
        }

        /** 便捷构造：自动以当前时间填充 lastUsed。 */
        static SeedEntry now(long seed) {
            return new SeedEntry(seed, Instant.now().toString());
        }

        void update() {
            this.lastUsed = Instant.now().toString();
        }

        @NotNull
        String lastUsed() {
            return lastUsed;
        }

        /** 写入到 DataOutput（由调用者实现）。 */
        void write(DataOutput out) throws IOException {
            out.writeLong(seed);
            out.writeUTF(lastUsed);
        }

        /** 从 DataInput 读取（由调用者实现）。 */
        static SeedEntry read(DataInput in) throws IOException {
            long seed = in.readLong();
            String lastUsed = in.readUTF();
            return new SeedEntry(seed, lastUsed);
        }

        @Override
        public String toString() {
            return "SeedEntry{" + seed + " @ " + lastUsed + '}';
        }
    }

}
