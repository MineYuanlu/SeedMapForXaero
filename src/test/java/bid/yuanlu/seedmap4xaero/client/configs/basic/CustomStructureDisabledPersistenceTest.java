package bid.yuanlu.seedmap4xaero.client.configs.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.structure.CustomStructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureKeys;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureTypes;

/**
 * 自定义（数据包）结构 disabled 可见性状态的 JSON v2 持久化往返。
 * <p>
 * 存储位置：{@link WorldConfig#setStructureEnabled}（位布局 {@code StructureBitFlag}），
 * 持久化于 {@code server_config.json} 的 {@code worlds.<mwId>.disabledStructures}；
 * 持久层只存稳定 key 字符串（如 {@code terralith:volcanic_peak}），key↔id 转换
 * 只在读写边界（{@link StructureKeys}），id 未分配（未注入）时以 orphan 原样保留。
 * <p>
 * 注意 {@link StructureTypes} 的自定义表是全局静态（volatile 快照），测试后必须还原。
 */
class CustomStructureDisabledPersistenceTest {

    @TempDir
    Path tmp;

    private static final String KEY_PEAK = "terralith:volcanic_peak";
    private static final String KEY_SLASH = "terralith:alpha/beta";

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        StructureTypes.setCustomTypes(List.of());
    }

    private static JsonConfigFile.Paths paths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, "server_config.json", "server_config.sm4x");
    }

    private static CustomStructureType custom(int id, String key) {
        return new CustomStructureType(id, key, "Name",
                new StructureType.Config(1, 20, 8, 0, 0f), 1, null, false);
    }

    /** 当前注册容量内被整体禁用 id 的持久化 key 集合（顺序无关断言用）。 */
    private static Set<String> disabledKeySet(WorldConfig wc) {
        var flags = wc.getDisabledStructures();
        Set<String> out = new HashSet<>();
        for (int id = 0; id < StructureTypes.capacity(); id++)
            if (flags.isStructureSet(id))
                out.add(StructureKeys.persistedKey(id));
        return out;
    }

    // ─── 往返: 注入状态下禁用 → save → 新实例 load ──────────────

    @Test
    void customDisabledRoundTripWhenInjected() throws IOException {
        StructureTypes.setCustomTypes(List.of(custom(100, KEY_PEAK), custom(101, KEY_SLASH)));

        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.seed(7L);
        wc.setStructureEnabled(100, false);
        wc.setStructureEnabled(101, false);
        wc.setStructureEnabled(StructureType.VILLAGE.id, false);

        ServerConfig.saveConfig(tmp, "srv", cfg, true);

        // 持久层存 key 字符串本身（含带斜杠路径的 key），不降级成 id:N 占位
        String text = Files.readString(paths(tmp, "srv").target(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"" + KEY_PEAK + "\""), text);
        assertTrue(text.contains("\"" + KEY_SLASH + "\""), text);
        assertTrue(text.contains("\"minecraft:village\""), text);
        assertFalse(text.contains("\"id:100\""), text);

        // 新实例 load: disabled 集合一致（Set 断言, 顺序无关）
        ConfigData loaded = ServerConfig.loadConfig(tmp, "srv");
        var read = loaded.getWorld("w");
        assertNotNull(read);
        assertEquals(7L, read.seed());
        assertEquals(Set.of(KEY_PEAK, KEY_SLASH, "minecraft:village"), disabledKeySet(read));
        // 未禁用者保持可见
        assertFalse(read.getDisabledStructures().isStructureSet(StructureType.MANSION.id));
        assertFalse(read.getDisabledStructures().isVariantSet(StructureType.VILLAGE.id, 0));
    }

    // ─── key 独立于 id 分配 (含 orphan 保留 + 注入后自动重挂) ────

    @Test
    void customKeyIndependentOfIdAllocation() throws IOException {
        // 会话 1: 注入 {100: peak, 101: alpha/beta}, 禁用后落盘
        StructureTypes.setCustomTypes(List.of(custom(100, KEY_PEAK), custom(101, KEY_SLASH)));
        ConfigData cfg = new ConfigData();
        var wc = cfg.getOrCreateWorld("w");
        wc.setStructureEnabled(100, false);
        wc.setStructureEnabled(101, false);
        ServerConfig.saveConfig(tmp, "srv", cfg, true);

        // 会话 2: 结构未注入 (id 未分配) → 无可归位 id, 但数据不丢
        StructureTypes.setCustomTypes(List.of());
        ConfigData orphaned = ServerConfig.loadConfig(tmp, "srv");
        assertTrue(disabledKeySet(orphaned.getWorld("w")).isEmpty(),
                "未注入时无 id 可归位, disabled 集为空");
        ServerConfig.saveConfig(tmp, "srv", orphaned, true);
        String text = Files.readString(paths(tmp, "srv").target(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"" + KEY_PEAK + "\""), "orphan key 原样并回:\n" + text);
        assertTrue(text.contains("\"" + KEY_SLASH + "\""), text);
        assertFalse(text.contains("\"id:100\""), "不得把自定义 key 降级成 id:N 占位:\n" + text);

        // 会话 3: 数据包组合变化 → id 重新分配 (105 / 999) → load 后重挂到新 id
        StructureTypes.setCustomTypes(List.of(custom(105, KEY_PEAK), custom(999, KEY_SLASH)));
        ConfigData reattached = ServerConfig.loadConfig(tmp, "srv");
        var flags = reattached.getWorld("w").getDisabledStructures();
        assertEquals(Set.of(KEY_PEAK, KEY_SLASH), disabledKeySet(reattached.getWorld("w")));
        assertTrue(flags.isStructureSet(105));
        assertTrue(flags.isStructureSet(999));
        assertFalse(flags.isStructureSet(100), "旧 id 分配不应残留");
        assertFalse(flags.isStructureSet(101));
    }

    // ─── 损坏容错: disabledStructures 缺失 / 类型错误 ────────────

    @Test
    void missingDisabledStructuresFallsBackToEmpty() throws IOException {
        var p = paths(tmp, "miss");
        Files.createDirectories(p.target().getParent());
        Files.writeString(p.target(), """
                {"version": 1, "worlds": {"w": {"seed": 7}}}
                """);

        ConfigData loaded = assertDoesNotThrow(() -> ServerConfig.loadConfig(tmp, "miss"));
        var wc = loaded.getWorld("w");
        assertNotNull(wc, "字段缺失不影响文档其余部分");
        assertEquals(7L, wc.seed());
        assertEquals(Set.of(), disabledKeySet(wc), "缺失 → 默认空集 (全部可见)");
        for (StructureType t : StructureType.values())
            assertFalse(wc.getDisabledStructures().isStructureSet(t.id),
                    t.key + " 默认可见");
    }

    @Test
    void corruptDisabledStructuresTypeFallsBackToFreshDoc() throws IOException {
        // 顶层字段类型错误 → WorldConfig.readJson 判文档损坏 (RuntimeException → IOException)
        // → JsonConfigFile 回退链: 主文件删除 → 无 .old → 全新默认文档; loadConfig 不抛异常
        List.of("[]", "\"nope\"").forEach(bad -> {
            String mainId = bad.equals("[]") ? "bad_arr" : "bad_str";
            try {
                var p = paths(tmp, mainId);
                Files.createDirectories(p.target().getParent());
                Files.writeString(p.target(),
                        "{\"version\": 1, \"worlds\": {\"w\": {\"disabledStructures\": "
                                + bad + "}}}");
                ConfigData loaded = assertDoesNotThrow(() -> ServerConfig.loadConfig(tmp, mainId));
                // 语义注记: 该损坏是文档级回退 (非单字段容错) → 世界条目不保留,
                // disabled 状态落默认空集
                assertNull(loaded.getWorld(mainId),
                        "文档级回退 → 世界条目不保留, disabled 落默认空集");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void perEntryCorruptionIsTolerated() throws IOException {
        // 单条目损坏 (key 可归位时): 非法变种码跳过、非对象条目按 orphan 原样保留,
        // 其余条目与世界其余字段不受影响; 注册表为空 → terralith:* 天然走 orphan
        var p = paths(tmp, "entry");
        Files.createDirectories(p.target().getParent());
        Files.writeString(p.target(), """
                {
                  "version": 1,
                  "worlds": {
                    "w": {
                      "seed": 9,
                      "disabledStructures": {
                        "minecraft:village": {"variants": [-1, 31, 99]},
                        "minecraft:mansion": "not-an-object",
                        "terralith:volcanic_peak": {"whole": true}
                      }
                    }
                  }
                }
                """);

        ConfigData loaded = assertDoesNotThrow(() -> ServerConfig.loadConfig(tmp, "entry"));
        var wc = loaded.getWorld("w");
        assertNotNull(wc, "条目级损坏不整体判损坏");
        assertEquals(9L, wc.seed());
        assertFalse(wc.getDisabledStructures().isStructureSet(StructureType.VILLAGE.id),
                "非法变种码 (负/31/99) 全部丢弃, 整类位不受影响");

        // 重新写出: 非法条目消失 (全部位无效), orphan 条目原值并回
        ServerConfig.saveConfig(tmp, "entry", loaded, true);
        String out = Files.readString(p.target(), StandardCharsets.UTF_8);
        assertFalse(out.contains("\"minecraft:village\""), "全无效条目不再写出:\n" + out);
        assertTrue(out.contains("\"minecraft:mansion\": \"not-an-object\""), out);
        assertTrue(out.contains("\"terralith:volcanic_peak\""), out);
    }
}
