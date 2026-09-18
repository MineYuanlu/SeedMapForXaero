package bid.yuanlu.seedmap4xaero.client.datapack;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.structure.CustomStructureType;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureTypes;
import net.minecraft.resources.Identifier;

/**
 * 数据包结构集摄取 (路线 A: 结构位置预测, v1 仅单机)。
 * <p>
 * 世界切换时扫描集成服务器已启用数据包的 {@code worldgen/structure_set}:
 * <ol>
 * <li>全 {@code minecraft:} 原版结构的集合 → 跳过 (cubiomes 已预测; placement
 *     与原版不一致时 WARN 提示预测可能不准)</li>
 * <li>全自定义结构的 {@code random_spread} 集合 → 解析 placement + structures
 *     (保序, 加权掷骰依赖顺序), 经 biome tag 递归解析归类维度</li>
 * <li>{@code c:} 元数据: {@code worldgen/structure_icons.json} (图标)、
 *     {@code tags/worldgen/structure/hide_from_map.json} (隐藏)</li>
 * </ol>
 * 构建结果注入 C 侧 ({@code xsmSetCustomStructures}) 与 Java 注册表
 * ({@link StructureTypes}), 并清缓存。预测不做 biome tag 校验 (已知假阳性,
 * 见 doc/datapak.md)。
 * <p>
 * v1 不支持: frequency&lt;1 / exclusion_zone / 原版+自定义混排集合 / 同一结构
 * 多集合引用 (均 WARN 跳过); {@code /reload} 不热更新 (重进世界生效)。
 */
public final class DatapackStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/DatapackStructures");

    private DatapackStructures() {
    }

    // ─── 生命周期 (世界切换钩子) ───────────────────────────────

    /** 世界切换/激活: 扫描数据包并注入; 多人 (无集成服务器) 等价于清除。 */
    public static void reload() {
        DatapackSource source;
        try {
            source = ResourceManagerSource.ofIntegratedServer();
        } catch (Throwable t) {
            LOGGER.warn("failed to access integrated server resources", t);
            source = null;
        }
        if (source == null) {
            clear();
            return;
        }
        try {
            ScanResult result = scan(source);
            if (result.types().isEmpty()) {
                clearInternal();
                LOGGER.debug("no custom structure sets found in datapacks");
                return;
            }
            if (!Xsm.setCustomStructures(result.sets(), result.entries())) {
                LOGGER.error("C side rejected custom structure table; custom structures disabled");
                clearInternal();
                return;
            }
            StructureTypes.setCustomTypes(result.types());
            CacheHelper.invalidateAll();
            HighlightedStructures.clear();
            LOGGER.info("injected {} datapack structures ({} structure sets) — positions are "
                    + "predictions without biome validation", result.types().size(), result.setCount());
        } catch (Throwable t) {
            LOGGER.warn("datapack structure scan failed; custom structures disabled", t);
            clearInternal();
        }
    }

    /** 清除注入 (离开世界/断线/扫描失败)。幂等。 */
    public static void clear() {
        if (StructureTypes.customTypes().isEmpty())
            return;
        clearInternal();
    }

    private static void clearInternal() {
        try {
            Xsm.setCustomStructures(new int[0], new int[0]);
        } catch (Throwable ignored) {
        }
        StructureTypes.setCustomTypes(List.of());
        CacheHelper.invalidateAll();
        HighlightedStructures.clear();
    }

    // ─── 扫描与解析 ───────────────────────────────────────────

    /** 解析产物: Java 类型表 + C 侧扁平注入数组。 */
    record ScanResult(List<CustomStructureType> types, int[] sets, int[] entries, int setCount) {
    }

    /** 一个 structure_set 的解析中间态 (结构与集合的关联由最终 id 绑定)。 */
    private record PendingSet(String setId, int salt, int spacing, int separation, int spread,
            int dim, List<String> structureIds, List<Integer> weights) {
    }

    static ScanResult scan(DatapackSource source) {
        final Map<String, Identifier> icons = parseStructureIcons(source);
        final Set<String> hidden = parseHiddenStructures(source);

        // 1. 全部 structure_set (按 id 排序, 保证 id 分配确定)
        final Map<Identifier, ? extends Reader> setResources = source.readAllJson("worldgen/structure_set");
        final List<Identifier> setIds = new ArrayList<>(setResources.keySet());
        setIds.sort(Comparator.comparing(Identifier::toString));
        final Map<String, Identifier> structureIconMap = icons;
        final Set<String> hiddenStructures = hidden;

        final List<PendingSet> pending = new ArrayList<>();
        final Set<String> claimedStructures = new HashSet<>();
        final Map<String, Integer> structureDim = new HashMap<>();

        for (Identifier setId : setIds) {
            PendingSet parsed = null;
            try (Reader reader = setResources.get(setId)) {
                if (reader == null)
                    continue;
                parsed = parseSet(source, setId, reader, claimedStructures, structureDim);
            } catch (Exception e) {
                LOGGER.warn("failed to parse structure set {}: {}", setId, e.toString());
            }
            if (parsed == null)
                continue;
            claimedStructures.addAll(parsed.structureIds());
            pending.add(parsed);
        }

        if (pending.isEmpty())
            return new ScanResult(List.of(), new int[0], new int[0], 0);

        // 2. 结构 id 分配: 全部被引用结构排序后从 100 顺序分配 (重扫描稳定)
        final List<String> allStructures = new ArrayList<>(claimedStructures);
        allStructures.sort(Comparator.naturalOrder());
        final Map<String, Integer> idOf = new HashMap<>(allStructures.size());
        for (int i = 0; i < allStructures.size(); i++)
            idOf.put(allStructures.get(i), CustomStructureType.MIN_ID + i);

        // 3. 构建 Java 类型 + C 扁平数组
        final List<CustomStructureType> types = new ArrayList<>(allStructures.size());
        for (String structureId : allStructures) {
            PendingSet owner = findOwner(pending, structureId);
            final int id = idOf.get(structureId);
            final StructureType.Config config = new StructureType.Config(
                    owner.salt(), owner.spacing(), owner.spacing() - owner.separation(),
                    owner.dim(), 0f);
            final int weight = weightOf(owner, structureId);
            types.add(new CustomStructureType(id, structureId,
                    prettify(structureId), config, weight,
                    structureIconMap.get(structureId),
                    hiddenStructures.contains(structureId)));
        }

        final int[] sets = new int[pending.size() * 7];
        final List<Integer> entryList = new ArrayList<>(allStructures.size());
        for (int s = 0; s < pending.size(); s++) {
            PendingSet ps = pending.get(s);
            sets[s * 7 + 0] = ps.salt();
            sets[s * 7 + 1] = ps.spacing();
            sets[s * 7 + 2] = ps.separation();
            sets[s * 7 + 3] = ps.spread();
            sets[s * 7 + 4] = ps.dim();
            sets[s * 7 + 5] = entryList.size() / 2;
            sets[s * 7 + 6] = ps.structureIds().size();
            // 集合内条目保持 JSON 顺序 (加权掷骰走表依赖顺序)
            for (int e = 0; e < ps.structureIds().size(); e++) {
                entryList.add(idOf.get(ps.structureIds().get(e)));
                entryList.add(ps.weights().get(e));
            }
        }
        final int[] entries = entryList.stream().mapToInt(Integer::intValue).toArray();
        return new ScanResult(List.copyOf(types), sets, entries, pending.size());
    }

    private static @Nullable PendingSet parseSet(DatapackSource source, Identifier setId,
            Reader reader, Set<String> claimed, Map<String, Integer> structureDim) {
        final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        final String setKey = setId.toString();

        // ── placement 校验 ──
        final JsonObject placement = root.getAsJsonObject("placement");
        if (placement == null) {
            LOGGER.warn("structure set {}: missing placement, skipped", setKey);
            return null;
        }
        final String type = optString(placement, "type", "");
        if (!type.equals("minecraft:random_spread")) {
            LOGGER.debug("structure set {}: placement {} not random_spread, skipped", setKey, type);
            return null;
        }
        final double frequency = placement.has("frequency")
                ? placement.get("frequency").getAsDouble() : 1.0;
        if (frequency < 1.0) {
            LOGGER.warn("structure set {}: frequency {} < 1 unsupported, skipped (positions "
                    + "would be wrong)", setKey, frequency);
            return null;
        }
        if (placement.has("exclusion_zone")) {
            LOGGER.warn("structure set {}: exclusion_zone unsupported, skipped", setKey);
            return null;
        }
        final int salt = optInt(placement, "salt", 0);
        final int spacing = optInt(placement, "spacing", 1);
        final int separation = optInt(placement, "separation", 1);
        final String spreadType = optString(placement, "spread_type", "linear");
        final int spread;
        switch (spreadType) {
            case "linear", "minecraft:linear" -> spread = 0;
            case "triangular", "minecraft:triangular" -> spread = 1;
            default -> {
                LOGGER.warn("structure set {}: unknown spread_type '{}', skipped", setKey, spreadType);
                return null;
            }
        }
        if (spacing < 1 || separation < 0 || separation >= spacing) {
            LOGGER.warn("structure set {}: invalid spacing/separation ({}/{}), skipped",
                    setKey, spacing, separation);
            return null;
        }

        // ── structures 列表 (保序) ──
        final JsonArray structures = root.getAsJsonArray("structures");
        if (structures == null || structures.isEmpty()) {
            LOGGER.warn("structure set {}: empty structures, skipped", setKey);
            return null;
        }
        final List<String> structureIds = new ArrayList<>(structures.size());
        final List<Integer> weights = new ArrayList<>(structures.size());
        boolean allVanilla = true;
        boolean anyVanilla = false;
        for (JsonElement el : structures) {
            final JsonObject entry = el.getAsJsonObject();
            final String structureId = optString(entry, "structure", null);
            final int weight = Math.max(1, optInt(entry, "weight", 1));
            if (structureId == null)
                continue;
            structureIds.add(structureId);
            weights.add(weight);
            final boolean vanilla = structureId.startsWith("minecraft:");
            allVanilla &= vanilla;
            anyVanilla |= vanilla;
        }
        if (structureIds.isEmpty()) {
            LOGGER.warn("structure set {}: no valid structure entries, skipped", setKey);
            return null;
        }

        // ── 原版集合策略: 全 minecraft 结构 → cubiomes 已预测, 跳过 ──
        if (allVanilla) {
            warnIfVanillaPlacementChanged(setId, salt, spacing, separation, spread);
            return null;
        }
        if (anyVanilla) {
            LOGGER.warn("structure set {}: mixes vanilla and custom structures; selection RNG "
                    + "would be wrong, skipped", setKey);
            return null;
        }

        // ── 维度归类 (biomes → tag 递归展开) ──
        int dim = Integer.MIN_VALUE;
        for (String structureId : structureIds) {
            final Integer known = structureDim.get(structureId);
            final int d = known != null ? known : classifyStructureDimension(source, structureId);
            if (known == null)
                structureDim.put(structureId, d);
            if (dim == Integer.MIN_VALUE)
                dim = d;
            else if (dim != d) {
                LOGGER.warn("structure set {}: structures span multiple dimensions, skipped", setKey);
                return null;
            }
        }
        if (dim == Integer.MIN_VALUE)
            dim = 0;

        // ── 多集合引用检查: 同一结构被两个集合引用会破坏唯一 id 约束 ──
        for (String structureId : structureIds) {
            if (claimed.contains(structureId)) {
                LOGGER.warn("structure set {}: structure {} already claimed by another set, "
                        + "this set skipped", setKey, structureId);
                return null;
            }
        }

        return new PendingSet(setKey, salt, spacing, separation, spread, dim, structureIds, weights);
    }

    /** 原版集合被改 placement (如 YUNG's 改 salt): 与 cubiomes 配置比对, 不一致 WARN。 */
    private static void warnIfVanillaPlacementChanged(Identifier setId, int salt, int spacing,
            int separation, int spread) {
        final StructureType type = VANILLA_SETS.get(setId.getPath());
        if (type == null) {
            LOGGER.warn("structure set {}: unknown vanilla structure set (custom placement?), "
                    + "cubiomes prediction may be inaccurate", setId);
            return;
        }
        final StructureType.Config cfg;
        try {
            cfg = type.config();
        } catch (Throwable t) {
            // 无 native 的 JVM 测试环境: 无法比对, 直接跳过
            return;
        }
        if (cfg == null)
            return;
        final boolean same = cfg.salt() == salt && cfg.regionSize() == spacing
                && cfg.chunkRange() == spacing - separation;
        if (!same) {
            LOGGER.warn("structure set {}: placement modified (salt/spacing/separation "
                    + "{}/{}/{} vs vanilla {}/{}/{}); cubiomes keeps predicting the vanilla "
                    + "layout, positions may be inaccurate", setId, salt, spacing, separation,
                    cfg.salt(), cfg.regionSize(), cfg.regionSize() - cfg.chunkRange());
        }
        if (spread != 0)
            LOGGER.warn("structure set {}: spread_type {} on vanilla set may be inaccurate", setId, spread);
    }

    // ─── structure.json → biomes → 维度 ───────────────────────

    private static int classifyStructureDimension(DatapackSource source, String structureId) {
        final Identifier id = Identifier.parse(structureId);
        final Identifier res = Identifier.fromNamespaceAndPath(id.getNamespace(),
                "worldgen/structure/" + id.getPath() + ".json");
        try (Reader reader = source.readJson(res)) {
            if (reader == null)
                return 0; // 找不到定义按主世界 (tag 解析失败同)
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            final Set<String> biomes = new HashSet<>();
            collectBiomes(source, root.get("biomes"), biomes, 0);
            return classifyDimension(biomes);
        } catch (Exception e) {
            LOGGER.debug("failed to classify dimension of {}: {}", structureId, e.toString());
            return 0;
        }
    }

    /** biomes 字段: 单 id / tag / 数组混合; tag 递归展开 (深度限制防环)。 */
    private static void collectBiomes(DatapackSource source, @Nullable JsonElement biomes,
            Set<String> out, int depth) {
        if (biomes == null || depth > 8)
            return;
        if (biomes.isJsonArray()) {
            for (JsonElement el : biomes.getAsJsonArray())
                collectBiomes(source, el, out, depth);
            return;
        }
        if (!biomes.isJsonPrimitive())
            return;
        final String value = biomes.getAsString();
        if (value.startsWith("#")) {
            // tag 引用 → 读全部包层合并 (replace:false 语义)
            final Identifier tagId = Identifier.parse(value.substring(1));
            final Identifier res = Identifier.fromNamespaceAndPath(tagId.getNamespace(),
                    "tags/worldgen/biome/" + tagId.getPath() + ".json");
            final List<? extends Reader> stack;
            try {
                stack = source.readJsonStack(res);
            } catch (Exception e) {
                LOGGER.debug("failed to read biome tag {}: {}", value, e.toString());
                return;
            }
            if (stack == null)
                return;
            try {
                for (Reader r : stack) {
                    JsonObject tag;
                    try {
                        tag = JsonParser.parseReader(r).getAsJsonObject();
                    } catch (Exception ignored) {
                        continue;
                    }
                    // replace=true: 丢弃低优先级包已积累的值 (vanilla tag 合并语义)
                    if (tag.has("replace") && tag.get("replace").getAsBoolean())
                        out.clear();
                    collectBiomes(source, tag.get("values"), out, depth + 1);
                }
            } finally {
                stack.forEach(ResourceManagerSource::closeQuietly);
            }
            return;
        }
        // 直接 biome id; 无命名空间 → minecraft
        out.add(value.contains(":") ? value : "minecraft:" + value);
    }

    private static int classifyDimension(Set<String> biomeIds) {
        if (biomeIds.isEmpty())
            return 0;
        boolean allNether = true, allEnd = true;
        for (String id : biomeIds) {
            if (!NETHER_BIOMES.contains(id))
                allNether = false;
            if (!END_BIOMES.contains(id))
                allEnd = false;
        }
        if (allNether)
            return -1;
        if (allEnd)
            return 1;
        return 0;
    }

    // ─── c: 元数据 ────────────────────────────────────────────

    /** c:worldgen/structure_icons.json → 结构 id → 物品 id。 */
    private static Map<String, Identifier> parseStructureIcons(DatapackSource source) {
        final Map<String, Identifier> out = new HashMap<>();
        try (Reader reader = source.readJson(
                Identifier.fromNamespaceAndPath("c", "worldgen/structure_icons.json"))) {
            if (reader == null)
                return out;
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (var e : root.entrySet()) {
                try {
                    final String item = e.getValue().getAsJsonObject().get("item").getAsString();
                    out.put(e.getKey(), Identifier.parse(item));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.debug("no c:worldgen/structure_icons.json ({})", e.toString());
        }
        return out;
    }

    /** c:tags/worldgen/structure/hide_from_map.json → 霐藏的结构 id 集合。 */
    private static Set<String> parseHiddenStructures(DatapackSource source) {
        final Set<String> out = new HashSet<>();
        final List<? extends Reader> stack;
        try {
            stack = source.readJsonStack(Identifier.fromNamespaceAndPath("c",
                    "tags/worldgen/structure/hide_from_map.json"));
        } catch (Exception e) {
            return out;
        }
        if (stack == null)
            return out;
        try {
            for (Reader r : stack) {
                try {
                    final JsonArray values = JsonParser.parseReader(r).getAsJsonObject()
                            .getAsJsonArray("values");
                    if (values == null)
                        continue;
                    for (JsonElement v : values)
                        out.add(v.getAsString());
                } catch (Exception ignored) {
                }
            }
        } finally {
            stack.forEach(ResourceManagerSource::closeQuietly);
        }
        return out;
    }

    // ─── 工具 ─────────────────────────────────────────────────

    private static @Nullable PendingSet findOwner(List<PendingSet> pending, String structureId) {
        for (PendingSet ps : pending)
            if (ps.structureIds().contains(structureId))
                return ps;
        throw new IllegalStateException("no owner set for " + structureId);
    }

    private static int weightOf(PendingSet owner, String structureId) {
        for (int i = 0; i < owner.structureIds().size(); i++)
            if (owner.structureIds().get(i).equals(structureId))
                return owner.weights().get(i);
        return 1;
    }

    /** "terralith:fortified_village" → "Fortified Village" (去后缀, 分词大写)。 */
    static String prettify(String structureId) {
        final int slash = structureId.lastIndexOf(':');
        final String path = slash >= 0 ? structureId.substring(slash + 1) : structureId;
        final String[] words = path.split("[-_]");
        final StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty())
                continue;
            if (!sb.isEmpty())
                sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1)
                sb.append(w.substring(1));
        }
        return sb.isEmpty() ? structureId : sb.toString();
    }

    private static @Nullable String optString(JsonObject obj, String key, @Nullable String def) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive()
                    ? obj.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int optInt(JsonObject obj, String key, int def) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive()
                    ? obj.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 原版 structure_set 路径 → 预测类型 (placement 比对用)。 */
    private static final Map<String, StructureType> VANILLA_SETS = Map.ofEntries(
            Map.entry("villages", StructureType.VILLAGE),
            Map.entry("desert_pyramids", StructureType.DESERT_PYRAMID),
            Map.entry("jungle_temples", StructureType.JUNGLE_PYRAMID),
            Map.entry("swamp_huts", StructureType.SWAMP_HUT),
            Map.entry("igloos", StructureType.IGLOO),
            Map.entry("ocean_ruins", StructureType.OCEAN_RUIN),
            Map.entry("shipwrecks", StructureType.SHIPWRECK),
            Map.entry("ocean_monuments", StructureType.MONUMENT),
            Map.entry("woodland_mansions", StructureType.MANSION),
            Map.entry("pillager_outposts", StructureType.OUTPOST),
            Map.entry("ruined_portals", StructureType.RUINED_PORTAL),
            Map.entry("ruined_portals_nether", StructureType.RUINED_PORTAL_N),
            Map.entry("ancient_cities", StructureType.ANCIENT_CITY),
            Map.entry("buried_treasures", StructureType.TREASURE),
            Map.entry("mineshafts", StructureType.MINESHAFT),
            Map.entry("desert_wells", StructureType.DESERT_WELL),
            Map.entry("amethyst_geodes", StructureType.GEODE),
            Map.entry("nether_fortresses", StructureType.FORTRESS),
            Map.entry("bastion_remnants", StructureType.BASTION),
            Map.entry("end_cities", StructureType.END_CITY),
            Map.entry("trail_ruins", StructureType.TRAIL_RUINS),
            Map.entry("trial_chambers", StructureType.TRIAL_CHAMBERS),
            Map.entry("strongholds", StructureType.STRONGHOLD));

    private static final Set<String> NETHER_BIOMES = Set.of(
            "minecraft:nether_wastes", "minecraft:soul_sand_valley", "minecraft:warped_forest",
            "minecraft:crimson_forest", "minecraft:basalt_deltas");

    private static final Set<String> END_BIOMES = Set.of(
            "minecraft:the_end", "minecraft:end_highlands", "minecraft:end_midlands",
            "minecraft:small_end_islands", "minecraft:end_barrens");
}
