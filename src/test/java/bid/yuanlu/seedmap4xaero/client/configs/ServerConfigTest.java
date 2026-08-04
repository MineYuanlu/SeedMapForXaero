package bid.yuanlu.seedmap4xaero.client.configs;

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
    }

    @Test
    void binaryRoundTrip() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path file = tmp.resolve("sub/server_config.sm4x");
        Files.createDirectories(file.getParent());

        cfg.write(file);
        ConfigData read = ConfigData.read(file);

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
        cfg.write(file);
        ConfigData read = ConfigData.read(file);
        assertNull(read.getTheme());
        assertFalse(read.isInvisibleBiomes());
        assertEquals(1.0f, read.getStructureIconSize());
        assertNull(read.getWorld("nope"));
    }

    @Test
    void corruptMagicWordRejected() throws IOException {
        Path file = tmp.resolve("bad.sm4x");
        Files.writeString(file, "THIS IS NOT A CONFIG FILE");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> ConfigData.read(file));
    }

    @Test
    void truncatedFileRejected() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path file = tmp.resolve("trunc.sm4x");
        cfg.write(file);
        byte[] all = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(all, all.length / 2));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> ConfigData.read(file));
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
        cfg.write(file);
        ConfigData read = ConfigData.read(file);

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
        ConfigData old = ConfigData.read(tmp.resolve("srvD/server_config.sm4x.old"));
        assertEquals(1L, old.getWorld("w").seed());
    }
}
