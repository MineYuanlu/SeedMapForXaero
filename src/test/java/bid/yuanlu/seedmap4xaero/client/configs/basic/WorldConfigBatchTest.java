package bid.yuanlu.seedmap4xaero.client.configs.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeKeys;
import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.test.McBootstrap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * WorldConfig 批量操作语义 (面板 全选/全不选/反选/只显搜索 的状态层):
 * null 哨兵恢复 / 只动已知 id / orphan 保留 / 变种位语义 / JSON 往返。
 * 批量作用于 {@link BiomeType} 集合, 故需 native (biome2str) — 无 native 的 JVM 跳过。
 */
class WorldConfigBatchTest extends McBootstrap {

    @TempDir
    java.nio.file.Path tmp;

    @BeforeAll
    static void initTypes() {
        try {
            // biome2str 需要 native 库已加载; 无 native 的 JVM 跳过本套件
            Xsm.getStructFEATURE_NUM();
        } catch (Throwable t) {
            Assumptions.abort("native library unavailable, skipping WorldConfig batch tests");
        }
        BiomeType.init();
    }

    private static int knownEnabled(WorldConfig wc) {
        int n = 0;
        var disabled = wc.getDisabledBiomes();
        for (BiomeType b : BiomeType.values())
            if (!disabled.get(b.id))
                n++;
        return n;
    }

    private JsonConfigFile.Paths paths(String name) {
        return JsonConfigFile.pathsFor(tmp, name, "server_config.json", "server_config.sm4x");
    }

    private String saveAndRead(ConfigData cfg, String name) throws IOException {
        ServerConfig.saveConfig(tmp, name, cfg, true);
        return Files.readString(paths(name).target(), StandardCharsets.UTF_8);
    }

    // ─── 生物群系批量 ──────────────────────────────────────────

    @Test
    void enableAllRestoresNullSentinel() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setBiomeDisabled(1, true);
        wc.setBiomeDisabled(4, true);
        assertEquals(BiomeType.values().length - 2, knownEnabled(wc));

        wc.enableAllBiomes();
        assertEquals(BiomeType.values().length, knownEnabled(wc));
        // null 哨兵: getDisabledBiomes 回 EMPTY 视图, JSON 省略字段
        assertTrue(wc.getDisabledBiomes().isEmpty());
        assertFalse(saveAndRead(cfg, "sentinel").contains("disabledBiomes"),
                "全选后不应落盘 disabledBiomes 字段");
    }

    @Test
    void disableAllOnlyKnownIdsNoIdPlaceholders() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.disableAllBiomes();
        assertEquals(0, knownEnabled(wc));

        String json = saveAndRead(cfg, "none");
        assertFalse(json.contains("\"id:"), "只动已知 id, 不应产生 id:N 占位 key 膨胀");

        var read = ServerConfig.loadConfig(tmp, "none").getWorld("w");
        assertEquals(0, knownEnabled(read), "往返后仍全部禁用");
    }

    @Test
    void invertTwiceReturnsToAllEnabled() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.invertBiomes();
        assertEquals(0, knownEnabled(wc));
        wc.invertBiomes();
        assertEquals(BiomeType.values().length, knownEnabled(wc));
        // 翻转两次后 BitSet 空 → JSON 省略, 读回归 null 哨兵
        assertFalse(saveAndRead(cfg, "invert2").contains("disabledBiomes"));
        assertTrue(ServerConfig.loadConfig(tmp, "invert2").getWorld("w").getDisabledBiomes().isEmpty());
    }

    @Test
    void enableOnlyBiomesFreezesFilterResult() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.disableAllBiomes();

        var only = new IntArrayList();
        only.add(1); // plains
        only.add(4); // forest
        wc.enableOnlyBiomes(only);

        assertEquals(2, knownEnabled(wc));
        assertFalse(wc.getDisabledBiomes().get(1));
        assertFalse(wc.getDisabledBiomes().get(4));
        assertTrue(wc.getDisabledBiomes().get(3));

        // 往返: 仅命中的两个 id 保持启用
        saveAndRead(cfg, "only");
        var read = ServerConfig.loadConfig(tmp, "only").getWorld("w");
        assertEquals(2, knownEnabled(read));
    }

    @Test
    void orphanBiomeKeysSurviveBatchOps() throws IOException {
        // 已知 id 的 key 动态生成 (测试 JVM 内 biome2str 可能走 unknown_N 占位名)
        String knownKey = BiomeKeys.idToKey(1);
        // 手写注入 orphan key (未知群系) 的 world 配置
        String json = """
                {
                  "version": 1,
                  "worlds": {
                    "w": {
                      "seed": 7,
                      "disabledBiomes": ["%s", "unknownmod:weird_biome"]
                    }
                  }
                }
                """.formatted(knownKey);
        var p = paths("orphan");
        Files.createDirectories(p.target().getParent());
        Files.writeString(p.target(), json);

        ConfigData loaded = ServerConfig.loadConfig(tmp, "orphan");
        var wc = loaded.getWorld("w");
        assertEquals(BiomeType.values().length - 1, knownEnabled(wc), "仅 id=1 禁用");

        // 全选: 已知 id 禁用位清空, orphan key 原样保留 (数据不丢)
        wc.enableAllBiomes();
        String out = saveAndRead(loaded, "orphan");
        assertTrue(out.contains("unknownmod:weird_biome"), "orphan 原样并回");
        assertFalse(out.contains("\"" + knownKey + "\""), "已知 id 的禁用位被清空");
    }

    // ─── 结构批量 ─────────────────────────────────────────────

    @Test
    void enableAllStructuresClearsTypeAndVariantBits() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setStructureEnabled(StructureType.VILLAGE.id, false);
        wc.setVariantEnabled(StructureType.VILLAGE.id, 8, false);
        wc.setStructureEnabled(StructureType.STRONGHOLD.id, false);

        wc.enableAllStructures();

        var flags = wc.getDisabledStructures();
        for (StructureType t : StructureType.values())
            assertFalse(flags.isStructureSet(t.id), "全部类型可见: " + t.id);
        assertFalse(flags.isVariantSet(StructureType.VILLAGE.id, 8), "全选清全部变种位");

        // 往返: 全可见态 (空 flags) 落盘后字段省略
        assertFalse(saveAndRead(cfg, "struct-all").contains("disabledStructures"));
        var read = ServerConfig.loadConfig(tmp, "struct-all").getWorld("w");
        assertFalse(read.getDisabledStructures().isStructureSet(StructureType.VILLAGE.id));
    }

    @Test
    void disableAllKeepsVariantBits() throws IOException {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setVariantEnabled(StructureType.VILLAGE.id, 8, false);

        wc.disableAllStructures();

        var flags = wc.getDisabledStructures();
        for (StructureType t : StructureType.values())
            assertTrue(flags.isStructureSet(t.id), "全部类型禁用: " + t.id);
        assertTrue(flags.isVariantSet(StructureType.VILLAGE.id, 8), "变种位不动 (被禁用类型遮蔽)");

        // 全选 = 全可见: 类型位清空 + 变种位清空
        wc.enableAllStructures();
        assertFalse(flags.isStructureSet(StructureType.VILLAGE.id));
        assertFalse(flags.isVariantSet(StructureType.VILLAGE.id, 8), "全选清变种位");
    }

    @Test
    void invertStructuresFlipsTypeBitsOnly() {
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setStructureEnabled(StructureType.VILLAGE.id, false); // village 整类禁用
        wc.setVariantEnabled(StructureType.VILLAGE.id, 8, false);

        wc.invertStructures();

        var flags = wc.getDisabledStructures();
        assertFalse(flags.isStructureSet(StructureType.VILLAGE.id), "原本禁用 → 翻转为可见");
        for (StructureType t : StructureType.values()) {
            if (t.id == StructureType.VILLAGE.id)
                continue;
            assertTrue(flags.isStructureSet(t.id), "原本可见 → 翻转为禁用: " + t.id);
        }
        assertTrue(flags.isVariantSet(StructureType.VILLAGE.id, 8), "反选不动变种位 (保持初始禁用态)");
        assertFalse(flags.isVariantSet(StructureType.STRONGHOLD.id, 0), "无变种位产生");
    }
}
