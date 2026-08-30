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

import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xCodec;

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

    /** basic 文档的编解码器，配合 {@code Sm4xFile} 使用；版本号是内部细节。 */
    public static final Sm4xCodec<ConfigData> CODEC = new Sm4xCodec<>(){
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
