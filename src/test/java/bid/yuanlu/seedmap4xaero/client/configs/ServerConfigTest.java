package bid.yuanlu.seedmap4xaero.client.configs.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

class ServerConfigTest {

    @TempDir
    Path tmp;

    private ConfigData sample(ConfigData cfg) {
        var wc = cfg.getOrCreateWorld("Multiplayer_127.0.0.1");
        wc.seed(123456789L);
        wc.setStructureEnabled(StructureType.VILLAGE.id, true);
        wc.setBiomeDisabled(4, true);
        cfg.setTheme("Vanilla");
        cfg.setInvisibleBiomes(true);
        cfg.setInvisibleStructures(false);
        cfg.setStructureIconSize(1.5f);
        cfg.useSeed(42L);
        cfg.useSeed(999L);
        return cfg;
    }

    private void assertWorldEq(WorldConfig expect, WorldConfig actual) {
        assertEquals(expect.seed(), actual.seed());
        assertEquals(expect.getDisabledStructures(), actual.getDisabledStructures());
        assertEquals(expect.getDisabledBiomes(), actual.getDisabledBiomes());
        assertEquals(expect.mcVersion(), actual.mcVersion());
    }

    @Test
    void binaryRoundTrip() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path file = tmp.resolve("sub/server_config.sm4x");
        Files.createDirectories(file.getParent());

        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);

        assertEquals("Vanilla", read.getTheme());
        assertTrue(read.isInvisibleBiomes());
        assertFalse(read.isInvisibleStructures());
        assertEquals(1.5f, read.getStructureIconSize());
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), read.getWorld("Multiplayer_127.0.0.1"));
    }

    @Test
    void emptyConfigRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        Path file = tmp.resolve("empty.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
        assertNull(read.getTheme());
        assertFalse(read.isInvisibleBiomes());
        assertEquals(1.0f, read.getStructureIconSize());
        assertNull(read.getWorld("nope"));
    }

    @Test
    void corruptMagicWordRejected() throws IOException {
        Path file = tmp.resolve("bad.sm4x");
        Files.writeString(file, "THIS IS NOT A CONFIG FILE");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Sm4xFile.readFrame(file, ConfigData.CODEC));
    }

    @Test
    void truncatedFileRejected() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path file = tmp.resolve("trunc.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        byte[] all = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(all, all.length / 2));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Sm4xFile.readFrame(file, ConfigData.CODEC));
    }

    @Test
    void loadMissingFileReturnsFresh() {
        ConfigData cfg = ServerConfig.loadConfig(tmp, "nonexistent");
        assertNotNull(cfg);
        assertNull(cfg.getWorld("whatever"));
    }

    @Test
    void saveThenLoadRoundTrip() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        ServerConfig.saveConfig(tmp, "srvA", cfg);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvA");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        assertEquals("Vanilla", loaded.getTheme());
    }

    @Test
    void corruptMainFallsBackToOld() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        ServerConfig.saveConfig(tmp, "srvB", cfg);
        // 第二次保存使 .old 包含 cfg 的内容 (第一次的 main 被轮替为 .old)
        ServerConfig.saveConfig(tmp, "srvB", sample(new ConfigData()));

        // 覆盖主文件为垃圾
        Path main = tmp.resolve("srvB/server_config.sm4x");
        Files.writeString(main, "CORRUPTED");

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvB");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        // 主文件被删除，.old 保留
        assertFalse(Files.exists(main), "corrupt main should be deleted");
        assertTrue(Files.exists(tmp.resolve("srvB/server_config.sm4x.old")));
    }

    @Test
    void corruptMainAndOldReturnsFresh() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        ServerConfig.saveConfig(tmp, "srvC", cfg);

        Files.writeString(tmp.resolve("srvC/server_config.sm4x"), "CORRUPTED");
        Files.writeString(tmp.resolve("srvC/server_config.sm4x.old"), "ALSO BAD");

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvC");
        assertNotNull(loaded);
        assertNull(loaded.getWorld("Multiplayer_127.0.0.1"));
    }

    @Test
    void worldsAreIsolatedPerMainId() throws IOException {
        var a = new ConfigData();
        a.getOrCreateWorld("w1").seed(111L);
        ServerConfig.saveConfig(tmp, "srv1", a);

        var b = new ConfigData();
        b.getOrCreateWorld("w2").seed(222L);
        ServerConfig.saveConfig(tmp, "srv2", b);

        ConfigData la = ServerConfig.loadConfig(tmp, "srv1");
        ConfigData lb = ServerConfig.loadConfig(tmp, "srv2");
        assertEquals(111L, la.getWorld("w1").seed());
        assertNull(la.getWorld("w2"));
        assertEquals(222L, lb.getWorld("w2").seed());
        assertNull(lb.getWorld("w1"));
    }

    @Test
    void bitsetsSurviveRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setStructureEnabled(StructureType.STRONGHOLD.id, false);
        wc.setStructureEnabled(StructureType.VILLAGE.id, true);
        for (int id = 0; id < 10; id++)
            wc.setBiomeDisabled(id, true);
        wc.setVariantEnabled(StructureType.VILLAGE.id, 8, false); // 僵尸平原村禁用

        Path file = tmp.resolve("bits.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);

        var flags = read.getWorld("w").getDisabledStructures();
        assertFalse(flags.isStructureSet(StructureType.VILLAGE.id), "village visible");
        assertTrue(flags.isStructureSet(StructureType.STRONGHOLD.id), "stronghold hidden");
        assertTrue(flags.isVariantSet(StructureType.VILLAGE.id, 8), "zombie village disabled");
        assertFalse(flags.isVariantSet(StructureType.VILLAGE.id, 0), "plains village stays visible");

        BitSetView dis = read.getWorld("w").getDisabledBiomes();
        for (int id = 0; id < 10; id++)
            assertTrue(dis.get(id));
    }

    @Test
    void freshWorldDefaultsToAllVisible() {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        var flags = wc.getDisabledStructures();
        for (StructureType t : StructureType.values())
            assertFalse(flags.isStructureSet(t.id), t.key + " should be visible by default");
        assertTrue(wc.getStructureTypeSet().get(StructureType.VILLAGE.id));
    }

    @Test
    void v0WorldConfigMigratesDefaults() throws IOException {
        // 旧版 (version 0) WorldConfig: seed + enabledStructures(可空) + disabledBiomes(可空)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(0); // version 0
            out.writeBoolean(true); // hasSeed
            out.writeLong(42L);
            out.writeBoolean(false); // no enabledStructures → 用默认
            out.writeBoolean(false); // no disabledBiomes
        }
        ConfigData main = new ConfigData();
        WorldConfig wc = WorldConfig.read(main,
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        assertEquals(42L, wc.seed());
        var flags = wc.getDisabledStructures();
        assertTrue(flags.isStructureSet(StructureType.FEATURE.id), "feature default hidden");
        assertFalse(flags.isStructureSet(StructureType.VILLAGE.id), "village default visible");
        assertFalse(flags.isVariantSet(StructureType.IGLOO.id, 1), "variants default visible");
    }

    @Test
    void v0EnabledStructuresFlipsToDisabled() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(0);
            out.writeBoolean(false); // no seed
            out.writeBoolean(true); // has enabledStructures
            // 仅 STRONGHOLD 开启 → 迁移后其余类型整类禁用
            BitSet en = new BitSet();
            en.set(StructureType.STRONGHOLD.id);
            byte[] bits = en.toByteArray();
            out.writeInt(bits.length);
            out.write(bits);
            out.writeBoolean(false); // no disabledBiomes
        }
        ConfigData main = new ConfigData();
        WorldConfig wc = WorldConfig.read(main,
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        var flags = wc.getDisabledStructures();
        assertFalse(flags.isStructureSet(StructureType.STRONGHOLD.id), "stronghold visible");
        assertTrue(flags.isStructureSet(StructureType.VILLAGE.id), "village hidden");
        assertTrue(flags.isStructureSet(StructureType.IGLOO.id), "igloo hidden");
    }

    @Test
    void seedUseMarksDirty() {
        ConfigData cfg = new ConfigData();
        assertFalse(cfg.dirty.get());
        cfg.useSeed(1L);
        assertTrue(cfg.dirty.get());
    }

    @Test
    void mcVersionDefaultsToNull() {
        assertNull(new ConfigData().getOrCreateWorld("w").mcVersion(), "default follows client version");
    }

    @Test
    void mcVersionRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        cfg.getOrCreateWorld("w").mcVersion("1.21.9");
        Path file = tmp.resolve("mcv.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        assertEquals("1.21.9", Sm4xFile.readFrame(file, ConfigData.CODEC).getWorld("w").mcVersion());

        // null（跟随客户端）也要能往返
        ConfigData auto = new ConfigData();
        auto.getOrCreateWorld("w").mcVersion(null);
        Path file2 = tmp.resolve("mcv_auto.sm4x");
        Sm4xFile.writeFrame(file2, auto, ConfigData.CODEC);
        assertNull(Sm4xFile.readFrame(file2, ConfigData.CODEC).getWorld("w").mcVersion());
    }

    @Test
    void v1WorldConfigWithoutMcVersionLoads() throws IOException {
        // WorldConfig record version 2 与 1 前缀兼容: 尾部仅多一个 mcVersion 字段
        // (null 时恰为 1 字节 false)。去掉该字节即得合法的 v1 记录。
        ConfigData main = new ConfigData();
        var wc = main.getOrCreateWorld("w");
        wc.seed(9L);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            wc.write(out);
        }
        byte[] all = bos.toByteArray();
        // 头部 int 改回 1 + 去掉尾部 mcVersion 字节 → 合法的 v1 记录
        byte[] v1Bytes = java.util.Arrays.copyOf(all, all.length - 1);
        v1Bytes[0] = 0;
        v1Bytes[1] = 0;
        v1Bytes[2] = 0;
        v1Bytes[3] = 1;

        WorldConfig read = WorldConfig.read(main,
                new DataInputStream(new ByteArrayInputStream(v1Bytes)));
        assertEquals(9L, read.seed());
        assertNull(read.mcVersion(), "v1 record has no mcVersion → follow client");
    }

    @Test
    void overwriteMovesPreviousToOld() throws IOException {
        ConfigData first = new ConfigData();
        first.getOrCreateWorld("w").seed(1L);
        ServerConfig.saveConfig(tmp, "srvD", first);

        ConfigData second = new ConfigData();
        second.getOrCreateWorld("w").seed(2L);
        ServerConfig.saveConfig(tmp, "srvD", second);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvD");
        assertEquals(2L, loaded.getWorld("w").seed());
        // 老文件应为第一次的内容
        ConfigData old = Sm4xFile.readFrame(tmp.resolve("srvD/server_config.sm4x.old"), ConfigData.CODEC);
        assertEquals(1L, old.getWorld("w").seed());
    }

    @Test
    void lootPreviewRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        cfg.setLootPreview(true);
        Path file = tmp.resolve("loot.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
        assertTrue(read.isLootPreview());
    }

    @Test
    void lootPreviewDefaultOff() {
        assertFalse(new ConfigData().isLootPreview());
    }

    @Test
    void oldV0FileWithoutLootPreviewLoads() throws IOException {
        // 模拟旧版 (v0) 文件: seeds 后直接跟结尾 MAGIC_WORD, 无 lootPreview/mode 字节
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.write("SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8));
            out.writeInt(0); // version 0
            out.writeInt(0); // worlds 为空
            out.writeBoolean(false); // no theme
            out.writeBoolean(false); // invisibleBiomes
            out.writeBoolean(false); // invisibleStructures
            out.writeFloat(1.0f); // structureIconSize
            out.writeInt(0); // seeds 为空
            out.write("SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8)); // 结尾 MAGIC, 无 lootPreview
        }
        Path file = tmp.resolve("oldv0.sm4x");
        Files.write(file, bos.toByteArray());

        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
        assertFalse(read.isLootPreview(), "old v0 file without lootPreview byte defaults false");
        assertEquals(LootDisplayMode.QUICK_PEEK, read.getLootDisplayMode());
    }

    @Test
    void v1FileLoadsLootPreviewAndMode() throws IOException {
        // 显式构造 v1 文件: seeds 后是 lootPreview(1字节) + mode(1字节), 再结尾 MAGIC
        for (LootDisplayMode mode : LootDisplayMode.values()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                out.write("SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8));
                out.writeInt(1); // version 1
                out.writeInt(0); // worlds 为空
                out.writeBoolean(false); // no theme
                out.writeBoolean(false); // invisibleBiomes
                out.writeBoolean(false); // invisibleStructures
                out.writeFloat(1.0f); // structureIconSize
                out.writeInt(0); // seeds 为空
                out.writeBoolean(true); // lootPreview = true
                out.writeByte(mode.ordinal()); // mode
                out.write("SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8)); // 结尾 MAGIC
            }
            Path file = tmp.resolve("v1_mode_" + mode.ordinal() + ".sm4x");
            Files.write(file, bos.toByteArray());

            ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
            assertEquals(mode, read.getLootDisplayMode(), "v1 file should restore mode");
            assertTrue(read.isLootPreview(), "v1 file should restore lootPreview");
        }
    }

    @Test
    void v1WriteProducedByConfig() throws IOException {
        // cfg.write() 应产出 version 1 文件, 且首字节探测不到任何旧格式歧义
        ConfigData cfg = new ConfigData();
        cfg.setLootPreview(true);
        cfg.setLootDisplayMode(LootDisplayMode.TILED_DETAIL);
        Path file = tmp.resolve("v1write.sm4x");
        Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);

        byte[] bytes = Files.readAllBytes(file);
        String header = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(header.startsWith("SEEDMAP4XAERO"), "magic word header");
        // 版本号 4 字节紧跟 MAGIC (13 字节)
        int version = ((bytes[13] & 0xFF) << 24) | ((bytes[14] & 0xFF) << 16)
                | ((bytes[15] & 0xFF) << 8) | (bytes[16] & 0xFF);
        assertEquals(1, version, "cfg.write should emit version 1");

        ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
        assertEquals(LootDisplayMode.TILED_DETAIL, read.getLootDisplayMode());
        assertTrue(read.isLootPreview());
    }

    @Test
    void lootDisplayModeRoundTrip() throws IOException {
        for (LootDisplayMode mode : LootDisplayMode.values()) {
            ConfigData cfg = new ConfigData();
            cfg.setLootPreview(true);
            cfg.setLootDisplayMode(mode);
            Path file = tmp.resolve("mode_" + mode.ordinal() + ".sm4x");
            Sm4xFile.writeFrame(file, cfg, ConfigData.CODEC);
            ConfigData read = Sm4xFile.readFrame(file, ConfigData.CODEC);
            assertEquals(mode, read.getLootDisplayMode());
            assertTrue(read.isLootPreview(), "lootPreview should survive round trip");
        }
    }
}
