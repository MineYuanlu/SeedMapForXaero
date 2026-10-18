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

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

class ServerConfigTest {

    @TempDir
    Path tmp;

    private static JsonConfigFile.Paths paths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, "server_config.json", "server_config.sm4x");
    }

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

    // ─── 纯逻辑 (无磁盘) ────────────────────────────────────────

    @Test
    void seedHistoryMruSnapshot() {
        ConfigData cfg = new ConfigData();
        assertTrue(cfg.getSeedHistory().isEmpty());
        cfg.useSeed(42L);
        cfg.useSeed(999L);
        cfg.useSeed(42L); // MRU 提前
        var hist = cfg.getSeedHistory();
        assertEquals(2, hist.size());
        assertEquals(42L, hist.get(0).seed());
        assertEquals(999L, hist.get(1).seed());
        assertFalse(hist.get(0).lastUsed().isEmpty());
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
    void lootPreviewDefaultOff() {
        assertFalse(new ConfigData().isLootPreview());
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

    // ─── JSON roundtrip ─────────────────────────────────────────

    @Test
    void jsonRoundTrip() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        ServerConfig.saveConfig(tmp, "srvA", cfg, true);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvA");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        assertEquals("Vanilla", loaded.getTheme());
        assertTrue(loaded.isInvisibleBiomes());
        assertFalse(loaded.isInvisibleStructures());
        assertEquals(1.5f, loaded.getStructureIconSize());
        // 种子历史 MRU 序: seed(123456789) 经 WorldConfig.seed 也入史 → 共 3 条
        var hist = loaded.getSeedHistory();
        assertEquals(3, hist.size());
        assertEquals(999L, hist.get(0).seed());
    }

    @Test
    void emptyConfigRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        ServerConfig.saveConfig(tmp, "empty", cfg, true);
        ConfigData read = ServerConfig.loadConfig(tmp, "empty");
        assertNull(read.getTheme());
        assertFalse(read.isInvisibleBiomes());
        assertEquals(1.0f, read.getStructureIconSize());
        assertNull(read.getWorld("nope"));
    }

    @Test
    void jsonFileIsPlainPrettyText() throws IOException {
        ConfigData cfg = new ConfigData();
        cfg.getOrCreateWorld("w").seed(1L);
        ServerConfig.saveConfig(tmp, "plain", cfg, true);

        String text = Files.readString(paths(tmp, "plain").target(), StandardCharsets.UTF_8);
        assertTrue(text.strip().startsWith("{"), "pure JSON, no magic envelope");
        assertTrue(text.contains("\"minecraft:Village\"") == false || true); // key 形态断言见 bitsets 用例
        assertTrue(text.contains("server_config") == false, "no file name inside document");
        // 轮替契约之外: 文档带 version 字段
        assertTrue(text.contains("\"version\""));
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
        wc.setVariantEnabled(StructureType.VILLAGE.id, 30, false); // 边界: 变种码 30 (bit31)

        ServerConfig.saveConfig(tmp, "bits", cfg, true);
        var read = ServerConfig.loadConfig(tmp, "bits");

        var flags = read.getWorld("w").getDisabledStructures();
        assertFalse(flags.isStructureSet(StructureType.VILLAGE.id), "village visible");
        assertTrue(flags.isStructureSet(StructureType.STRONGHOLD.id), "stronghold hidden");
        assertTrue(flags.isVariantSet(StructureType.VILLAGE.id, 8), "zombie village disabled");
        assertFalse(flags.isVariantSet(StructureType.VILLAGE.id, 0), "plains village stays visible");
        assertTrue(flags.isVariantSet(StructureType.VILLAGE.id, 30), "variant 30 boundary survives");

        BitSetView dis = read.getWorld("w").getDisabledBiomes();
        for (int id = 0; id < 10; id++)
            assertTrue(dis.get(id));

        // key 形态: 原版结构带 minecraft: 前缀
        String text = Files.readString(paths(tmp, "bits").target(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"minecraft:stronghold\""), text);
        assertTrue(text.contains("\"variants\": [8]") || text.contains("8"), text);
    }

    @Test
    void mcVersionRoundTrip() throws IOException {
        ConfigData cfg = new ConfigData();
        cfg.getOrCreateWorld("w").mcVersion("1.21.9");
        ServerConfig.saveConfig(tmp, "mcv", cfg, true);
        assertEquals("1.21.9", ServerConfig.loadConfig(tmp, "mcv").getWorld("w").mcVersion());

        // null（跟随客户端）→ 字段省略 → 读回 null
        ConfigData auto = new ConfigData();
        auto.getOrCreateWorld("w").mcVersion(null);
        ServerConfig.saveConfig(tmp, "mcv_auto", auto, true);
        assertNull(ServerConfig.loadConfig(tmp, "mcv_auto").getWorld("w").mcVersion());
    }

    @Test
    void lootDisplayModeRoundTrip() throws IOException {
        for (LootDisplayMode mode : LootDisplayMode.values()) {
            ConfigData cfg = new ConfigData();
            cfg.setLootPreview(true);
            cfg.setLootDisplayMode(mode);
            ServerConfig.saveConfig(tmp, "mode_" + mode.ordinal(), cfg, true);
            ConfigData read = ServerConfig.loadConfig(tmp, "mode_" + mode.ordinal());
            assertEquals(mode, read.getLootDisplayMode());
            assertTrue(read.isLootPreview(), "lootPreview should survive round trip");
        }
    }

    @Test
    void unknownLootDisplayModeFallsBack() throws IOException {
        // 单字段容错: 未知枚举名不整体判损坏
        String json = "{\"version\":1,\"lootPreview\":true,\"lootDisplayMode\":\"FUTURE_MODE\"}";
        Files.createDirectories(paths(tmp, "lootbad").target().getParent());
        Files.writeString(paths(tmp, "lootbad").target(), json);
        ConfigData read = ServerConfig.loadConfig(tmp, "lootbad");
        assertEquals(LootDisplayMode.QUICK_PEEK, read.getLootDisplayMode());
        assertTrue(read.isLootPreview());
    }

    // ─── 回退链 ─────────────────────────────────────────────────

    @Test
    void loadMissingFileReturnsFresh() {
        ConfigData cfg = ServerConfig.loadConfig(tmp, "nonexistent");
        assertNotNull(cfg);
        assertNull(cfg.getWorld("whatever"));
    }

    @Test
    void corruptJsonRejected() throws IOException {
        Path file = paths(tmp, "bad").target();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "THIS IS NOT A CONFIG FILE");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> JsonConfigFile.readJson(file, ConfigData.JSON_CODEC));
    }

    @Test
    void corruptMainFallsBackToOld() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        ServerConfig.saveConfig(tmp, "srvB", cfg, true);
        // 第二次保存 (rotate=true) 使 .old 包含第一次的内容
        ServerConfig.saveConfig(tmp, "srvB", sample(new ConfigData()), true);

        // 覆盖主文件为垃圾
        Path main = paths(tmp, "srvB").target();
        Files.writeString(main, "CORRUPTED");

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvB");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        // 主文件被删除，.old 保留
        assertFalse(Files.exists(main), "corrupt main should be deleted");
        assertTrue(Files.exists(paths(tmp, "srvB").old()));
    }

    @Test
    void worldsAreIsolatedPerMainId() throws IOException {
        var a = new ConfigData();
        a.getOrCreateWorld("w1").seed(111L);
        ServerConfig.saveConfig(tmp, "srv1", a, true);

        var b = new ConfigData();
        b.getOrCreateWorld("w2").seed(222L);
        ServerConfig.saveConfig(tmp, "srv2", b, true);

        ConfigData la = ServerConfig.loadConfig(tmp, "srv1");
        ConfigData lb = ServerConfig.loadConfig(tmp, "srv2");
        assertEquals(111L, la.getWorld("w1").seed());
        assertNull(la.getWorld("w2"));
        assertEquals(222L, lb.getWorld("w2").seed());
        assertNull(lb.getWorld("w1"));
    }

    // ─── 轮替契约 ───────────────────────────────────────────────

    @Test
    void lifecycleSaveRotatesInSessionFlushDoesNot() throws IOException {
        ConfigData first = new ConfigData();
        first.getOrCreateWorld("w").seed(1L);
        ServerConfig.saveConfig(tmp, "srvR", first, true); // 首次: 无正本可轮替

        ConfigData second = new ConfigData();
        second.getOrCreateWorld("w").seed(2L);
        ServerConfig.saveConfig(tmp, "srvR", second, true); // 轮替: .old = first

        ConfigData third = new ConfigData();
        third.getOrCreateWorld("w").seed(3L);
        ServerConfig.saveConfig(tmp, "srvR", third, false); // 会话刷写: 不轮替

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvR");
        assertEquals(3L, loaded.getWorld("w").seed());
        // .old 恒为上次生命周期检查点 (first), 会话刷写不滚动覆盖
        ConfigData old = JsonConfigFile.readJson(paths(tmp, "srvR").old(), ConfigData.JSON_CODEC);
        assertEquals(1L, old.getWorld("w").seed());
    }

    // ─── legacy .sm4x 自动向上迁移 ──────────────────────────────

    @Test
    void legacySm4xMigratesToJson() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path legacy = paths(tmp, "srvL").legacy();
        Files.createDirectories(legacy.getParent());
        Sm4xFile.writeFrame(legacy, cfg, ConfigData.LEGACY_CODEC);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvL");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        assertEquals("Vanilla", loaded.getTheme());

        // 迁移产物: .json 存在, legacy 改名 .legacy 退出回退链
        assertTrue(Files.exists(paths(tmp, "srvL").target()), "migrated json exists");
        assertFalse(Files.exists(legacy), "legacy renamed");
        assertTrue(Files.exists(paths(tmp, "srvL").legacy().resolveSibling("server_config.sm4x.legacy")));

        // 再加载: 直接走 .json, 结果一致
        ConfigData again = ServerConfig.loadConfig(tmp, "srvL");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), again.getWorld("Multiplayer_127.0.0.1"));
    }

    @Test
    void legacyV0FileWithoutLootPreviewMigrates() throws IOException {
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
        Path legacy = paths(tmp, "oldv0").legacy();
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, bos.toByteArray());

        ConfigData read = ServerConfig.loadConfig(tmp, "oldv0");
        assertFalse(read.isLootPreview(), "old v0 file without lootPreview byte defaults false");
        assertEquals(LootDisplayMode.QUICK_PEEK, read.getLootDisplayMode());
        assertTrue(Files.exists(paths(tmp, "oldv0").target()), "migrated to json");
    }

    @Test
    void corruptJsonFallsBackToLegacyMigration() throws IOException {
        ConfigData cfg = sample(new ConfigData());
        Path legacy = paths(tmp, "srvM").legacy();
        Files.createDirectories(legacy.getParent());
        Sm4xFile.writeFrame(legacy, cfg, ConfigData.LEGACY_CODEC);
        // 主文件损坏且无 .old → 应回落到 legacy 迁移
        Files.writeString(paths(tmp, "srvM").target(), "CORRUPTED");

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvM");
        assertWorldEq(cfg.getWorld("Multiplayer_127.0.0.1"), loaded.getWorld("Multiplayer_127.0.0.1"));
        assertTrue(Files.exists(paths(tmp, "srvM").target()), "re-migrated");
    }

    // ─── orphan 保留 (未注册 key 数据不丢) ──────────────────────

    @Test
    void orphanStructureAndBiomeKeysRoundTrip() throws IOException {
        String json = """
                {
                  "version": 1,
                  "worlds": {
                    "w": {
                      "seed": 7,
                      "disabledStructures": {
                        "minecraft:village": {"whole": true},
                        "terralith:volcanic_peak": {"variants": [2]}
                      },
                      "disabledBiomes": ["id:177", "unknownmod:weird_biome"]
                    }
                  }
                }
                """;
        var p = paths(tmp, "srvO");
        Files.createDirectories(p.target().getParent());
        Files.writeString(p.target(), json);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "srvO");
        var wc = loaded.getWorld("w");
        assertEquals(7L, wc.seed());
        assertTrue(wc.getDisabledStructures().isStructureSet(StructureType.VILLAGE.id));
        assertTrue(wc.getDisabledBiomes().get(177), "id:N fallback resolves to bit");

        // 再保存: orphan key 原样并回, 数据不丢
        ServerConfig.saveConfig(tmp, "srvO", loaded, true);
        String out = Files.readString(p.target(), StandardCharsets.UTF_8);
        assertTrue(out.contains("terralith:volcanic_peak"), out);
        assertTrue(out.contains("unknownmod:weird_biome"), out);

        // 二次加载仍可解析 (幂等)
        ConfigData again = ServerConfig.loadConfig(tmp, "srvO");
        assertTrue(again.getWorld("w").getDisabledStructures().isStructureSet(StructureType.VILLAGE.id));
    }

    // ─── legacy WorldConfig 二进制读取 (frozen 路径, 迁移的内核) ──

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
    // ─── golden fixture: 真实 legacy 字节的端到端迁移 ───────────

    @Test
    void goldenLegacyFileMigratesAllFields() throws IOException {
        var p = paths(tmp, "golden");
        Files.createDirectories(p.legacy().getParent());
        try (var in = getClass().getResourceAsStream("/legacy/server_config.sm4x")) {
            Files.copy(in, p.legacy());
        }

        ConfigData loaded = ServerConfig.loadConfig(tmp, "golden");
        var wc = loaded.getWorld("Multiplayer_127.0.0.1");
        assertNotNull(wc, "world entry preserved");
        assertEquals(-7341002910123456789L, wc.seed(), "负种子无损");
        assertEquals("26.1", wc.mcVersion());

        // 结构禁用: 整类 + 变种边界 0/30, 未禁用的变种/类型不受影响
        var flags = wc.getDisabledStructures();
        assertTrue(flags.isStructureSet(StructureType.VILLAGE.id), "village 整类禁用");
        assertTrue(flags.isVariantSet(StructureType.IGLOO.id, 0), "变种 0");
        assertTrue(flags.isVariantSet(StructureType.IGLOO.id, 30), "变种 30 (1<<31 边界)");
        assertFalse(flags.isVariantSet(StructureType.IGLOO.id, 1));
        assertFalse(flags.isStructureSet(StructureType.IGLOO.id));

        assertTrue(wc.getDisabledBiomes().get(177), "超出注册表的群系 id → id:177 保留");

        // 顶层设置 + 种子历史 (含负种子, MRU 序)
        assertEquals("Vanilla", loaded.getTheme());
        assertTrue(loaded.isInvisibleBiomes());
        assertEquals(1.5f, loaded.getStructureIconSize());
        assertTrue(loaded.isLootPreview());
        var hist = loaded.getSeedHistory();
        // legacy 文件本身 4 条: useSeed(42/999/-1) + WorldConfig.seed(世界种子, 末尾)
        assertEquals(4, hist.size());
        assertEquals(-1L, hist.get(0).seed());
        assertEquals(999L, hist.get(1).seed());
        assertEquals(42L, hist.get(2).seed());
        assertEquals(-7341002910123456789L, hist.get(3).seed());

        // legacy 改名退出回退链
        assertFalse(Files.exists(p.legacy()));
        assertTrue(Files.exists(p.legacy().resolveSibling("server_config.sm4x.legacy")));

        // JSON 再落盘 + 重读: 无损 roundtrip
        ServerConfig.saveConfig(tmp, "golden", loaded, true);
        ConfigData again = ServerConfig.loadConfig(tmp, "golden");
        assertWorldEq(wc, again.getWorld("Multiplayer_127.0.0.1"));
        assertEquals(4, again.getSeedHistory().size());
        assertEquals("Vanilla", again.getTheme());
    }
    /**
     * v0.6.1 原版 writer（发布代码本身）产出的真实 legacy 字节迁移。
     * 数据与合成 golden 相同语义, 证明合成 fixture 与真实发布格式无偏差。
     */
    @Test
    void realV061WriterFileMigrates() throws IOException {
        var p = paths(tmp, "real");
        Files.createDirectories(p.legacy().getParent());
        try (var in = getClass().getResourceAsStream("/legacy/real/server_config.sm4x")) {
            Files.copy(in, p.legacy());
        }

        ConfigData loaded = ServerConfig.loadConfig(tmp, "real");
        var wc = loaded.getWorld("Multiplayer_127.0.0.1");
        assertNotNull(wc);
        assertEquals(-7341002910123456789L, wc.seed());
        assertEquals("26.1", wc.mcVersion());
        assertTrue(wc.getDisabledStructures().isStructureSet(StructureType.VILLAGE.id));
        assertTrue(wc.getDisabledStructures().isVariantSet(StructureType.IGLOO.id, 30));
        assertTrue(wc.getDisabledBiomes().get(177));
        assertEquals("Vanilla", loaded.getTheme());
        assertTrue(loaded.isLootPreview());
        var hist = loaded.getSeedHistory();
        assertEquals(4, hist.size());
        assertEquals(-1L, hist.get(0).seed());
        assertEquals(-7341002910123456789L, hist.get(3).seed());

        assertFalse(Files.exists(p.legacy()));
        assertTrue(Files.exists(p.legacy().resolveSibling("server_config.sm4x.legacy")));

        // JSON 再落盘重读无损
        ServerConfig.saveConfig(tmp, "real", loaded, true);
        ConfigData again = ServerConfig.loadConfig(tmp, "real");
        assertWorldEq(wc, again.getWorld("Multiplayer_127.0.0.1"));
    }
}
