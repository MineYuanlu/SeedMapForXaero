package bid.yuanlu.seedmap4xaero.client.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.client.structure.CustomStructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureInfo;
import bid.yuanlu.seedmap4xaero.client.structure.StructureTypes;
import net.minecraft.resources.Identifier;

/**
 * {@link DatapackStructures#scan} 的纯 JVM 解析测试 (内存数据源, 不依赖 native)。
 * 覆盖: 注入构建 / 维度归类 (biome tag 递归) / 原版集合跳过 / 非法集合跳过 /
 * c: 元数据 (图标 + 隐藏) / C 扁平数组布局 / 容错分支 (损坏 JSON / 缺字段 /
 * weight 钳制 / 跨维度 / tag 环 / 坏图标条目 / 显式 frequency=1.0)。
 * <p>
 * 注意 scan() 不触碰 Xsm/StructureTypes 全局状态, 可安全并行其它套件。
 */
class DatapackStructuresScanTest {

    /** 内存数据源: folder/path → JSON 文本; 支持多层栈模拟 tag 合并。 */
    private static final class MemorySource implements DatapackSource {
        /** id → 单层 JSON (readAllJson/readJson 用)。 */
        final Map<Identifier, String> resources = new LinkedHashMap<>();
        /** id → 多层 JSON (低优先级在前, readJsonStack 用)。 */
        final Map<Identifier, List<String>> stacks = new HashMap<>();

        void put(String id, String json) {
            resources.put(Identifier.parse(id), json);
        }

        void putStack(String id, String... layersLowFirst) {
            stacks.put(Identifier.parse(id), List.of(layersLowFirst));
        }

        @Override
        public Map<Identifier, ? extends Reader> readAllJson(String folder) {
            Map<Identifier, Reader> out = new LinkedHashMap<>();
            for (var e : resources.entrySet()) {
                if (e.getKey().getPath().startsWith(folder + "/"))
                    out.put(e.getKey(), new StringReader(e.getValue()));
            }
            return out;
        }

        @Override
        public List<? extends Reader> readJsonStack(Identifier id) {
            var layers = stacks.get(id);
            if (layers == null)
                return null;
            List<Reader> out = new ArrayList<>(layers.size());
            for (String s : layers)
                out.add(new StringReader(s));
            return out;
        }

        @Override
        public Reader readJson(Identifier id) {
            String json = resources.get(id);
            return json == null ? null : new StringReader(json);
        }
    }

    private static final String SET_PREFIX = "terralith:worldgen/structure_set/";
    private static final String STRUCT_PREFIX = "terralith:worldgen/structure/";

    private static void putSet(MemorySource src, String name, String json) {
        src.put(SET_PREFIX + name + ".json", json);
    }

    private static void putStructure(MemorySource src, String name, String biomes) {
        src.put(STRUCT_PREFIX + name + ".json",
                "{\"type\": \"minecraft:jigsaw\", \"biomes\": " + biomes + "}");
    }

    private static String setJson(String placement, String... entries) {
        StringBuilder sb = new StringBuilder("{\"placement\": ").append(placement)
                .append(", \"structures\": [");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append("{\"structure\": \"").append(entries[i]).append("\", \"weight\": 1}");
        }
        return sb.append("]}").toString();
    }

    private static final String RANDOM_SPREAD =
            "{\"type\": \"minecraft:random_spread\", \"salt\": 2358902, \"spacing\": 27, \"separation\": 15}";

    // ─── 基本注入 ─────────────────────────────────────────────

    @Test
    void scanBuildsTypesWithStableSortedIds() {
        MemorySource src = new MemorySource();
        putStructure(src, "spire", "[\"minecraft:plains\"]");
        putStructure(src, "mage_tower", "[\"minecraft:desert\"]");
        putSet(src, "regular", setJson(RANDOM_SPREAD, "terralith:mage_tower", "terralith:spire"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(2, r.types().size());
        assertEquals(1, r.setCount());
        // id 按结构 id 字典序分配: mage_tower(100) < spire(101)
        assertEquals(100, r.types().get(0).id());
        assertEquals("terralith:mage_tower", r.types().get(0).key());
        assertEquals(101, r.types().get(1).id());
        assertEquals("terralith:spire", r.types().get(1).key());
        // config: spacing=27, chunkRange=spacing-separation=12, dim=overworld
        var cfg = r.types().get(0).config();
        assertEquals(2358902, cfg.salt());
        assertEquals(27, cfg.regionSize());
        assertEquals(12, cfg.chunkRange());
        assertEquals(0, cfg.dim());
        // 显示名 prettify
        assertEquals("Mage Tower", r.types().get(0).localizedName());
    }

    @Test
    void cArraysLayoutMatchesContract() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putStructure(src, "b", "[\"minecraft:plains\"]");
        putStructure(src, "c", "[\"minecraft:plains\"]");
        // 集合排序后: alpha (a,b) → beta (c); 条目按集合分组连续
        putSet(src, "alpha", setJson(RANDOM_SPREAD, "terralith:a", "terralith:b"));
        putSet(src, "beta", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 40, "
                        + "\"separation\": 10, \"spread_type\": \"triangular\"}",
                "terralith:c"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        // id 分配: a=100, b=101, c=102 (字典序)
        // sets: [alpha(0)] [beta(1)]
        int[] sets = r.sets();
        assertEquals(18, sets.length); // 2 sets × 9
        // alpha: salt/spacing/separation/spread/dim/firstEntry/entryCount/freqBits/method
        assertEquals(2358902, sets[0]);
        assertEquals(27, sets[1]);
        assertEquals(15, sets[2]);
        assertEquals(0, sets[3]); // linear
        assertEquals(0, sets[4]); // overworld
        assertEquals(0, sets[5]); // firstEntry
        assertEquals(2, sets[6]); // entryCount
        assertEquals(Float.floatToIntBits(1.0f), sets[7]); // frequency 默认 1.0 (无频率门)
        assertEquals(0, sets[8]); // method = default
        // beta
        assertEquals(1, sets[9]);
        assertEquals(40, sets[10]);
        assertEquals(10, sets[11]);
        assertEquals(1, sets[12]); // triangular
        assertEquals(0, sets[13]);
        assertEquals(2, sets[14]); // firstEntry = 2
        assertEquals(1, sets[15]);
        assertEquals(Float.floatToIntBits(1.0f), sets[16]);
        assertEquals(0, sets[17]);
        // entries: [a=100 w1, b=101 w1, c=102 w1]
        int[] entries = r.entries();
        assertEquals(6, entries.length);
        assertEquals(100, entries[0]);
        assertEquals(1, entries[1]);
        assertEquals(101, entries[2]);
        assertEquals(1, entries[3]);
        assertEquals(102, entries[4]);
        assertEquals(1, entries[5]);
    }

    // ─── 维度归类 ─────────────────────────────────────────────

    @Test
    void netherClassificationViaTag() {
        MemorySource src = new MemorySource();
        putStructure(src, "citadel", "\"#terralith:has_structure/citadel\"");
        src.putStack("terralith:tags/worldgen/biome/has_structure/citadel.json",
                "{\"replace\": false, \"values\": [\"minecraft:nether_wastes\", "
                        + "\"minecraft:basalt_deltas\"]}");
        putSet(src, "nether", setJson(RANDOM_SPREAD, "terralith:citadel"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(-1, r.types().get(0).config().dim());
    }

    @Test
    void endClassificationViaDirectBiome() {
        MemorySource src = new MemorySource();
        putStructure(src, "end_thing", "[\"minecraft:end_highlands\"]");
        putSet(src, "end", setJson(RANDOM_SPREAD, "terralith:end_thing"));

        assertEquals(1, DatapackStructures.scan(src).types().get(0).config().dim());
    }

    @Test
    void mixedBiomesClassifyAsOverworld() {
        MemorySource src = new MemorySource();
        putStructure(src, "mixed", "[\"minecraft:plains\", \"minecraft:the_end\"]");
        putSet(src, "mixed", setJson(RANDOM_SPREAD, "terralith:mixed"));

        assertEquals(0, DatapackStructures.scan(src).types().get(0).config().dim());
    }

    @Test
    void tagStackMergesLayersAndHonorsReplace() {
        MemorySource src = new MemorySource();
        putStructure(src, "s", "\"#t:tag\"");
        // 低优先级层: nether 群系; 高优先级层 replace=true: 只留 plains
        src.putStack("t:tags/worldgen/biome/tag.json",
                "{\"replace\": false, \"values\": [\"minecraft:nether_wastes\"]}",
                "{\"replace\": true, \"values\": [\"minecraft:plains\"]}");
        putSet(src, "set", setJson(RANDOM_SPREAD, "terralith:s"));

        assertEquals(0, DatapackStructures.scan(src).types().get(0).config().dim(),
                "replace=true 高优先级层应清空低层值 → 主世界");
    }

    @Test
    void nestedTagResolution() {
        MemorySource src = new MemorySource();
        putStructure(src, "s", "\"#t:outer\"");
        src.putStack("t:tags/worldgen/biome/outer.json",
                "{\"values\": [\"#t:inner\"]}");
        src.putStack("t:tags/worldgen/biome/inner.json",
                "{\"values\": [\"minecraft:crimson_forest\"]}");
        putSet(src, "set", setJson(RANDOM_SPREAD, "terralith:s"));

        assertEquals(-1, DatapackStructures.scan(src).types().get(0).config().dim(),
                "嵌套 tag 应展开到底 → 下界");
    }

    // ─── 跳过规则 ─────────────────────────────────────────────

    @Test
    void vanillaOnlySetSkipped() {
        MemorySource src = new MemorySource();
        putSet(src, "villages", setJson(RANDOM_SPREAD, "minecraft:village"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertTrue(r.types().isEmpty());
        assertEquals(0, r.setCount());
    }

    @Test
    void mixedVanillaCustomSetSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "custom", "[\"minecraft:plains\"]");
        putSet(src, "mixed", setJson(RANDOM_SPREAD, "minecraft:village", "terralith:custom"));

        assertTrue(DatapackStructures.scan(src).types().isEmpty());
    }

    @Test
    void unsupportedPlacementsSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putStructure(src, "b", "[\"minecraft:plains\"]");
        putStructure(src, "c", "[\"minecraft:plains\"]");
        putSet(src, "excl", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 30, "
                        + "\"separation\": 5, \"exclusion_zone\": {\"other_set\": \"x:y\", \"chunk_count\": 3}}",
                "terralith:b"));
        putSet(src, "badsep", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 5, "
                        + "\"separation\": 5}",
                "terralith:c"));
        putSet(src, "rings", setJson(
                "{\"type\": \"minecraft:concentric_rings\", \"salt\": 1, \"distance\": 32, "
                        + "\"spread\": 3, \"count\": 128}",
                "terralith:d"));

        assertTrue(DatapackStructures.scan(src).types().isEmpty(),
                "exclusion/非法 separation/concentric_rings 全部跳过");
    }

    @Test
    void frequencyBelowOneKeptWithMethod() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putStructure(src, "b", "[\"minecraft:plains\"]");
        // YUNG's Better Mineshafts 形状: spacing=1, frequency=0.003, legacy_type_3
        putSet(src, "yungs", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 593751784, \"spacing\": 1, "
                        + "\"separation\": 0, \"frequency\": 0.003, "
                        + "\"frequency_reduction_method\": \"legacy_type_3\"}",
                "terralith:a"));
        // default method (缺省字段)
        putSet(src, "def", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 7, \"spacing\": 20, "
                        + "\"separation\": 4, \"frequency\": 0.5}",
                "terralith:b"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(2, r.types().size());
        int[] sets = r.sets();
        assertEquals(18, sets.length);
        // 集合按 id 字典序: def 在前, yungs 在后
        assertEquals(Float.floatToIntBits(0.5f), sets[7]);
        assertEquals(0, sets[8]); // default
        assertEquals(593751784, sets[9]);
        assertEquals(Float.floatToIntBits(0.003f), sets[16]);
        assertEquals(3, sets[17]); // legacy_type_3
    }

    @Test
    void invalidFrequencyOrMethodSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putStructure(src, "b", "[\"minecraft:plains\"]");
        putSet(src, "zero", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 10, "
                        + "\"separation\": 2, \"frequency\": 0.0}",
                "terralith:a"));
        putSet(src, "bigfreq", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 10, "
                        + "\"separation\": 2, \"frequency\": 1.5}",
                "terralith:b"));
        assertTrue(DatapackStructures.scan(src).types().isEmpty());

        putSet(src, "zero", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 10, "
                        + "\"separation\": 2, \"frequency_reduction_method\": \"bogus\"}",
                "terralith:a"));
        assertTrue(DatapackStructures.scan(src).types().isEmpty(),
                "未知 frequency_reduction_method 跳过");
    }

    @Test
    void duplicateStructureAcrossSetsSkipsLaterSet() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putStructure(src, "b", "[\"minecraft:plains\"]");
        putSet(src, "first", setJson(RANDOM_SPREAD, "terralith:a"));
        // second 引用 a (已被 first 声明) + b → second 整体跳过
        putSet(src, "second", setJson(RANDOM_SPREAD, "terralith:a", "terralith:b"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(1, r.types().size());
        assertEquals("terralith:a", r.types().get(0).key());
    }

    @Test
    void missingStructureDefinitionDefaultsOverworld() {
        MemorySource src = new MemorySource();
        // 不放 structure JSON
        putSet(src, "set", setJson(RANDOM_SPREAD, "terralith:ghost"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(1, r.types().size());
        assertEquals(0, r.types().get(0).config().dim(), "缺失定义按主世界");
    }

    // ─── c: 元数据 ────────────────────────────────────────────

    @Test
    void iconsAndHideFromMap() {
        MemorySource src = new MemorySource();
        putStructure(src, "spire", "[\"minecraft:plains\"]");
        putStructure(src, "hidden_thing", "[\"minecraft:plains\"]");
        putSet(src, "set", setJson(RANDOM_SPREAD, "terralith:spire", "terralith:hidden_thing"));
        src.put("c:worldgen/structure_icons.json",
                "{\"terralith:spire\": {\"item\": \"minecraft:blue_ice\"}}");
        src.putStack("c:tags/worldgen/structure/hide_from_map.json",
                "{\"values\": [\"terralith:hidden_thing\"]}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(2, r.types().size());
        // spire (101, 字典序 hidden_thing < spire) 有图标, hidden_thing 无且隐藏
        CustomStructureType spire = r.types().get(1);
        CustomStructureType hidden = r.types().get(0);
        assertEquals("terralith:hidden_thing", hidden.key());
        assertTrue(hidden.isHidden());
        assertNull(hidden.iconItem());
        assertEquals("terralith:spire", spire.key());
        assertFalse(spire.isHidden());
        assertNotNull(spire.iconItem());
        assertEquals("minecraft:blue_ice", spire.iconItem().toString());
        // 隐藏结构仍在 C 表 (保证同集合掷骰正确)
        assertEquals(4, r.entries().length);
    }

    @Test
    void hiddenStructuresExcludedFromRegistryList() {
        // StructureTypes.all() 过滤 hidden — 通过注册表间接验证 (测试后还原)
        MemorySource src = new MemorySource();
        putStructure(src, "hidden_thing", "[\"minecraft:plains\"]");
        putSet(src, "set", setJson(RANDOM_SPREAD, "terralith:hidden_thing"));
        src.putStack("c:tags/worldgen/structure/hide_from_map.json",
                "{\"values\": [\"terralith:hidden_thing\"]}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        try {
            StructureTypes.setCustomTypes(r.types());
            assertEquals(1, StructureTypes.customTypes().size(), "customTypes 含隐藏项");
            assertTrue(StructureTypes.all().stream().noneMatch(StructureInfo::isHidden),
                    "all() 不含隐藏结构");
            assertEquals(bid.yuanlu.seedmap4xaero.client.structure.StructureType.values().length,
                    StructureTypes.all().size(), "全部隐藏时 all() 仅剩原版");
        } finally {
            StructureTypes.setCustomTypes(List.of());
        }
    }

    // ─── 容错分支 ─────────────────────────────────────────────

    @Test
    void corruptSetJsonSkippedOthersStillInjected() {
        MemorySource src = new MemorySource();
        putStructure(src, "ok", "[\"minecraft:plains\"]");
        // 截断 JSON → 解析抛异常
        putSet(src, "a_corrupt", "{\"placement\": ");
        // 合法 JSON 但根不是对象 → getAsJsonObject 抛异常
        putSet(src, "b_notobj", "[1, 2, 3]");
        putSet(src, "c_ok", setJson(RANDOM_SPREAD, "terralith:ok"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(1, r.types().size(), "损坏集合 warn+跳过, 正常集合仍注入");
        assertEquals("terralith:ok", r.types().get(0).key());
        assertEquals(1, r.setCount());
    }

    @Test
    void missingPlacementSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putSet(src, "no_placement", "{\"structures\": [{\"structure\": \"terralith:a\", \"weight\": 1}]}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertTrue(r.types().isEmpty(), "缺 placement 的集合跳过");
        assertEquals(0, r.setCount());
    }

    @Test
    void emptyStructuresSkipped() {
        MemorySource src = new MemorySource();
        putSet(src, "empty_arr", "{\"placement\": " + RANDOM_SPREAD + ", \"structures\": []}");
        putSet(src, "missing_field", "{\"placement\": " + RANDOM_SPREAD + "}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertTrue(r.types().isEmpty(), "structures 空数组/字段缺失均跳过");
        assertEquals(0, r.setCount());
    }

    @Test
    void spreadTypePrefixedAcceptedUnknownSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "lin_a", "[\"minecraft:plains\"]");
        putStructure(src, "tri_a", "[\"minecraft:plains\"]");
        putStructure(src, "ring_a", "[\"minecraft:plains\"]");
        putStructure(src, "bog_a", "[\"minecraft:plains\"]");
        // 带 minecraft: 前缀的合法值应接受
        putSet(src, "lin1", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 20, "
                        + "\"separation\": 5, \"spread_type\": \"minecraft:linear\"}",
                "terralith:lin_a"));
        putSet(src, "tri1", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 2, \"spacing\": 20, "
                        + "\"separation\": 5, \"spread_type\": \"minecraft:triangular\"}",
                "terralith:tri_a"));
        // 真正未知的值跳过
        putSet(src, "rings", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 3, \"spacing\": 20, "
                        + "\"separation\": 5, \"spread_type\": \"minecraft:concentric_rings\"}",
                "terralith:ring_a"));
        putSet(src, "bogus", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 4, \"spacing\": 20, "
                        + "\"separation\": 5, \"spread_type\": \"space:zigzag\"}",
                "terralith:bog_a"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(2, r.types().size(), "仅前缀合法值的两集合注入");
        assertEquals(2, r.setCount());
        // 集合按 id 字典序: bogus < lin1 < rings < tri1 → 注入顺序 lin1, tri1
        assertEquals(1, r.sets()[0]);
        assertEquals(0, r.sets()[3]); // minecraft:linear → 0
        assertEquals(2, r.sets()[9]);
        assertEquals(1, r.sets()[12]); // minecraft:triangular → 1
    }

    @Test
    void weightClampedAndMissingStructureEntrySkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "neg", "[\"minecraft:plains\"]");
        putStructure(src, "zero", "[\"minecraft:plains\"]");
        putStructure(src, "implicit", "[\"minecraft:plains\"]");
        putStructure(src, "heavy", "[\"minecraft:plains\"]");
        // 首条目缺 structure 字段 → 静默跳过该条目; weight 缺失/0/负钳制为 1
        putSet(src, "weights", "{\"placement\": " + RANDOM_SPREAD + ", \"structures\": ["
                + "{\"weight\": 3},"
                + "{\"structure\": \"terralith:neg\", \"weight\": -5},"
                + "{\"structure\": \"terralith:zero\", \"weight\": 0},"
                + "{\"structure\": \"terralith:implicit\"},"
                + "{\"structure\": \"terralith:heavy\", \"weight\": 3}"
                + "]}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(4, r.types().size());
        assertEquals(4, r.sets()[6], "entryCount 不含缺 structure 的条目");
        // 条目保持 JSON 顺序; id 按字典序 heavy(100) < implicit(101) < neg(102) < zero(103)
        int[] entries = r.entries();
        assertEquals(8, entries.length);
        assertEquals(102, entries[0]);
        assertEquals(1, entries[1], "weight -5 钳制为 1");
        assertEquals(103, entries[2]);
        assertEquals(1, entries[3], "weight 0 钳制为 1");
        assertEquals(101, entries[4]);
        assertEquals(1, entries[5], "weight 缺省为 1");
        assertEquals(100, entries[6]);
        assertEquals(3, entries[7], "合法正权重原样保留");
        // Java 侧类型权重同钳制
        assertEquals(3, r.types().get(0).weight());
        assertEquals(1, r.types().get(2).weight());
    }

    @Test
    void crossDimensionSetSkipped() {
        MemorySource src = new MemorySource();
        putStructure(src, "nether_thing", "[\"minecraft:nether_wastes\"]");
        putStructure(src, "over_thing", "[\"minecraft:plains\"]");
        putSet(src, "cross", setJson(RANDOM_SPREAD, "terralith:nether_thing", "terralith:over_thing"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertTrue(r.types().isEmpty(), "同集合结构归类到不同维度 → 整集合跳过");
        assertEquals(0, r.setCount());
    }

    @Test
    void modifiedVanillaPlacementSetSkipped() {
        MemorySource src = new MemorySource();
        // 全 minecraft: 结构 (villages 集合在实现对照表内), 但 salt/spacing 与 vanilla 真值不同
        putSet(src, "villages", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 999999, \"spacing\": 48, "
                        + "\"separation\": 4}",
                "minecraft:village", "minecraft:pillager_outpost"));
        putStructure(src, "custom", "[\"minecraft:plains\"]");
        putSet(src, "zcustom", setJson(RANDOM_SPREAD, "terralith:custom"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        // 原版集合保持原版预测 (仍跳过不注入), 自定义集合正常注入
        assertEquals(1, r.types().size());
        assertEquals("terralith:custom", r.types().get(0).key());
        assertEquals(1, r.setCount());
    }

    @Test
    void cyclicBiomeTagTerminatesGracefully() {
        MemorySource src = new MemorySource();
        putStructure(src, "cyclic", "\"#t:a\"");
        // tag 环: a → b → a; 递归深度超限 (depth > 8) 处截断
        src.putStack("t:tags/worldgen/biome/a.json", "{\"values\": [\"#t:b\"]}");
        src.putStack("t:tags/worldgen/biome/b.json",
                "{\"values\": [\"#t:a\", \"minecraft:soul_sand_valley\"]}");
        putSet(src, "cycle", setJson(RANDOM_SPREAD, "terralith:cyclic"));

        // 不死循环/不抛异常; 归类用截断前已收集的群系 → 下界
        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(1, r.types().size());
        assertEquals(-1, r.types().get(0).config().dim());
    }

    @Test
    void hideStackMergesAndBadIconEntriesIgnored() {
        MemorySource src = new MemorySource();
        putStructure(src, "good_struct", "[\"minecraft:plains\"]");
        putStructure(src, "hide_a", "[\"minecraft:plains\"]");
        putStructure(src, "hide_b", "[\"minecraft:plains\"]");
        putStructure(src, "noitem_struct", "[\"minecraft:plains\"]");
        putSet(src, "meta", setJson(RANDOM_SPREAD,
                "terralith:good_struct", "terralith:hide_a", "terralith:hide_b",
                "terralith:noitem_struct"));
        // hide_from_map 两包层 (replace:false) → 两层 values 合并生效
        src.putStack("c:tags/worldgen/structure/hide_from_map.json",
                "{\"replace\": false, \"values\": [\"terralith:hide_a\"]}",
                "{\"replace\": false, \"values\": [\"terralith:hide_b\"]}");
        // 坏图标条目 (非对象/缺 item/非法 id) 逐条忽略, 好条目仍解析
        src.put("c:worldgen/structure_icons.json",
                "{\"terralith:good_struct\": {\"item\": \"minecraft:diamond\"}, "
                        + "\"terralith:hide_a\": \"not-an-object\", "
                        + "\"terralith:noitem_struct\": {\"other\": true}, "
                        + "\"terralith:hide_b\": {\"item\": \"!!!\"}}");

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(4, r.types().size());
        // 字典序: good_struct(100) < hide_a(101) < hide_b(102) < noitem_struct(103)
        CustomStructureType good = r.types().get(0);
        CustomStructureType hideA = r.types().get(1);
        CustomStructureType hideB = r.types().get(2);
        CustomStructureType noitem = r.types().get(3);
        assertTrue(hideA.isHidden());
        assertTrue(hideB.isHidden(), "两层 hide 值合并生效");
        assertFalse(good.isHidden());
        assertEquals("minecraft:diamond", good.iconItem().toString());
        assertNull(noitem.iconItem(), "缺 item 字段的图标条目被忽略");
        // 隐藏结构仍进 C 表 (保证同集合掷骰正确)
        assertEquals(8, r.entries().length);
    }

    @Test
    void explicitFrequencyOneKept() {
        MemorySource src = new MemorySource();
        putStructure(src, "a", "[\"minecraft:plains\"]");
        putSet(src, "freq_one", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 42, \"spacing\": 30, "
                        + "\"separation\": 8, \"frequency\": 1.0}",
                "terralith:a"));

        DatapackStructures.ScanResult r = DatapackStructures.scan(src);
        assertEquals(1, r.types().size(), "显式 frequency=1.0 在 (0,1] 内, 集合保留");
        assertEquals(1, r.setCount());
        assertEquals(0x3F800000, r.sets()[7]); // 1.0f 位型
        assertEquals(0, r.sets()[8]); // method = default
    }

    // ─── 工具 ─────────────────────────────────────────────────

    @Test
    void prettifyNames() {
        assertEquals("Fortified Village", DatapackStructures.prettify("terralith:fortified_village"));
        assertEquals("Mage Tower Summer", DatapackStructures.prettify("terralith:mage-tower_summer"));
        assertEquals("X", DatapackStructures.prettify("ns:x"));
        assertEquals("Ab", DatapackStructures.prettify("ab"));
    }
}
