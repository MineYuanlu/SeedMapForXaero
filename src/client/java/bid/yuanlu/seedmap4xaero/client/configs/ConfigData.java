package bid.yuanlu.seedmap4xaero.client.configs;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 种子地图的完整配置文件，对应 {@code server_config.json} 的存储格式。
 * <p>
 * 组织方式：
 * 
 * <pre>
 * worlds : { dimKey → { mwId → WorldConfig } }
 * all_seeds : [ SeedEntry … ]
 * theme : String|null
 * invisible : Boolean|null
 * </pre>
 */
public class ConfigData {
    private static final byte[] MAGIC_WORD = "SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_SEEDS = 1000;
    // dimKey → mwId → 世界配置
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WorldConfig>> worlds = new ConcurrentHashMap<>();
    private final ArrayList<SeedEntry> allSeeds = new ArrayList<>();

    @Nullable
    String theme;
    boolean invisible = false;

    AtomicBoolean dirty = new AtomicBoolean(false);

    ConfigData() {
    }

    void makeDirty() {
        this.dirty.set(true);
    }

    @Nullable
    public WorldConfig getWorld(String dimKey, String mwId) {
        var dimMap = worlds.get(dimKey);
        return dimMap != null ? dimMap.get(mwId) : null;
    }

    @NotNull
    public WorldConfig getOrCreateWorld(String dimKey, String mwId) {
        return worlds.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mwId, k -> new WorldConfig(this));
    }

    public @Nullable String getTheme() {
        return theme;
    }

    public boolean isInvisible() {
        return invisible;
    }

    public synchronized void setTheme(@Nullable String theme) {
        if (Objects.equals(this.theme, theme))
            return;
        this.theme = theme;
        makeDirty();
    }

    public synchronized void setInvisible(boolean invisible) {
        if (this.invisible == invisible)
            return;
        this.invisible = invisible;
        makeDirty();
    }

    /** 通知使用了某个种子, 更新种子列表 */
    void useSeed(long seed) {
        try {
            synchronized (allSeeds) {
                for (int i = 0; i < allSeeds.size(); i++) {
                    SeedEntry e = allSeeds.get(i);
                    if (seed==e.seed) {
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

    /** 写入到 DataOutput。 */
    synchronized void write(DataOutputStream out) throws IOException {
        out.write(MAGIC_WORD);
        out.writeInt(0); // version
        out.writeInt(worlds.size());
        for (final var dimEntry : worlds.entrySet()) {
            out.writeUTF(dimEntry.getKey());
            out.writeInt(dimEntry.getValue().size());
            for (final var worldEntry : dimEntry.getValue().entrySet()) {
                out.writeUTF(worldEntry.getKey());
                worldEntry.getValue().write(out);
            }
        }

        out.writeBoolean(theme != null);
        if (theme != null)
            out.writeUTF(theme);

        out.writeBoolean(invisible);

        synchronized (allSeeds) {
            out.writeInt(allSeeds.size());
            for (final var seedEntry : allSeeds) {
                seedEntry.write(out);
            }
        }
        out.write(MAGIC_WORD);
    }

    /** 从 DataInput 读取。 */
    static ConfigData read(DataInputStream in) throws IOException {
        final byte[] magicWord = new byte[MAGIC_WORD.length];
        in.readFully(magicWord);
        if (!Arrays.equals(magicWord, MAGIC_WORD))
            throw new IOException("Invalid magic word at start");
        final var config = new ConfigData();
        final var version = in.readInt();
        if (version == 0) {
            final var dimSize = in.readInt();
            for (int i = 0; i < dimSize; i++) {
                final var dimKey = in.readUTF();
                final var dims = config.worlds.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());
                final var worldSize = in.readInt();
                for (int j = 0; j < worldSize; j++) {
                    final var mwId = in.readUTF();
                    dims.put(mwId, WorldConfig.read(config, in));
                }
            }

            config.theme = in.readBoolean() ? in.readUTF() : null;
            config.invisible = in.readBoolean();

            final var seedSize = in.readInt();
            for (int i = 0; i < seedSize; i++) {
                config.allSeeds.add(SeedEntry.read(in));
            }

        } else {
            throw new IOException("Unsupported ConfigData version: " + version);
        }
        in.readFully(magicWord);
        if (!Arrays.equals(magicWord, MAGIC_WORD))
            throw new IOException("Invalid magic word at end");
        return config;
    }

    /** 写入到指定文件。 */
    void write(Path file) throws IOException {
        try (final var out = new DataOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            write(out);
        }
    }

    /** 从指定文件读取。 */
    static ConfigData read(Path file) throws IOException {
        try (final var in = new DataInputStream(Files.newInputStream(file, StandardOpenOption.READ))) {
            return read(in);
        }
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
            this.seed =seed;
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
