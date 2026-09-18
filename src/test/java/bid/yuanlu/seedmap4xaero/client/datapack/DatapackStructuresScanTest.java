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
 * c: 元数据 (图标 + 隐藏) / C 扁平数组布局。
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
        assertEquals(14, sets.length); // 2 sets × 7
        // alpha: salt/spacing/separation/spread/dim/firstEntry/entryCount
        assertEquals(2358902, sets[0]);
        assertEquals(27, sets[1]);
        assertEquals(15, sets[2]);
        assertEquals(0, sets[3]); // linear
        assertEquals(0, sets[4]); // overworld
        assertEquals(0, sets[5]); // firstEntry
        assertEquals(2, sets[6]); // entryCount
        // beta
        assertEquals(1, sets[7]);
        assertEquals(40, sets[8]);
        assertEquals(10, sets[9]);
        assertEquals(1, sets[10]); // triangular
        assertEquals(0, sets[11]);
        assertEquals(2, sets[12]); // firstEntry = 2
        assertEquals(1, sets[13]);
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
        putStructure(src, "d", "[\"minecraft:plains\"]");
        putSet(src, "freq", setJson(
                "{\"type\": \"minecraft:random_spread\", \"salt\": 1, \"spacing\": 30, "
                        + "\"separation\": 5, \"frequency\": 0.5}",
                "terralith:a"));
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
                "frequency/exclusion/非法 separation/concentric_rings 全部跳过");
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

    // ─── 工具 ─────────────────────────────────────────────────

    @Test
    void prettifyNames() {
        assertEquals("Fortified Village", DatapackStructures.prettify("terralith:fortified_village"));
        assertEquals("Mage Tower Summer", DatapackStructures.prettify("terralith:mage-tower_summer"));
        assertEquals("X", DatapackStructures.prettify("ns:x"));
        assertEquals("Ab", DatapackStructures.prettify("ab"));
    }
}
