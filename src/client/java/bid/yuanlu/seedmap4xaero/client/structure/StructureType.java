package bid.yuanlu.seedmap4xaero.client.structure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.TreeMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import net.minecraft.resources.Identifier;

/**
 * 生成结构枚举表
 */
public enum StructureType {
    FEATURE(0, "feature", false, Integer.MAX_VALUE), // 地物
    DESERT_PYRAMID(1, "desert_pyramid", true, Integer.MAX_VALUE), // 沙漠神殿
    JUNGLE_TEMPLE(2, "jungle_temple", true, Integer.MAX_VALUE), // 丛林神庙
    JUNGLE_PYRAMID(2, "jungle_pyramid", true, Integer.MAX_VALUE), // 丛林神庙 (1.13+改名)
    SWAMP_HUT(3, "swamp_hut", true, Integer.MAX_VALUE), // 沼泽小屋
    IGLOO(4, "igloo", true, Integer.MAX_VALUE), // 雪屋
    VILLAGE(5, "village", true, Integer.MAX_VALUE), // 村庄
    OCEAN_RUIN(6, "ocean_ruin", false, Integer.MAX_VALUE), // 海底废墟
    SHIPWRECK(7, "shipwreck", true, Integer.MAX_VALUE), // 沉船
    MONUMENT(8, "monument", true, Integer.MAX_VALUE), // 海底神殿
    MANSION(9, "mansion", true, Integer.MAX_VALUE), // 林地府邸
    OUTPOST(10, "outpost", true, Integer.MAX_VALUE), // 掠夺者前哨站
    RUINED_PORTAL(11, "ruined_portal", false, Integer.MAX_VALUE), // 废弃传送门（主世界）
    RUINED_PORTAL_N(12, "ruined_portal_nether", false, Integer.MAX_VALUE), // 废弃传送门（下界）
    ANCIENT_CITY(13, "ancient_city", true, Integer.MAX_VALUE), // 远古城市
    TREASURE(14, "treasure", false, 2048, 0.01f * 0.0333f), // 埋藏的宝藏
    MINESHAFT(15, "mineshaft", false, 1024, 0.004f), // 废弃矿井
    DESERT_WELL(16, "desert_well", false, 2048, 0.001f * 0.0213f), // 沙漠水井
    GEODE(17, "geode", false, 2048, 1f / 24), // 紫晶洞
    FORTRESS(18, "fortress", true, Integer.MAX_VALUE), // 下界要塞
    BASTION(19, "bastion", true, Integer.MAX_VALUE), // 堡垒遗迹
    END_CITY(20, "end_city", true, Integer.MAX_VALUE), // 末地城
    END_GATEWAY(21, "end_gateway", false, Integer.MAX_VALUE, 1f / 700 * 0.0883f), // 末地折跃门
    END_ISLAND(22, "end_island", false, Integer.MAX_VALUE, 1f / 14), // 末地岛屿
    TRAIL_RUINS(23, "trail_ruins", false, Integer.MAX_VALUE), // 古迹废墟
    TRIAL_CHAMBERS(24, "trial_chambers", false, Integer.MAX_VALUE), // 试炼密室
    STRONGHOLD(25, "stronghold", true, Integer.MAX_VALUE); // 要塞

    private static final class LoggerHolder {
        private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureType");
    }

    public static final int FEATURE_NUM = Xsm.getStructFEATURE_NUM();
    /** 普通结构专用上限: 视口内 region 数超过则整类跳过 (region 级缓存容量) */
    public static final int MAX_REGION_HIDE = 16384;
    /**
     * 稀疏结构专用上限: 期望放置命中量 (regionCount×prob) 超过则整类跳过;
     * 等于 C 端 querySparseStructures 单轮扫描 cap
     */
    public static final int MAX_SPARSE_HITS = 8192;
    /** 精灵图横向图标数量; 由 structures.ini 推导, {@link #init()} 赋值 */
    public static int SHEET_SIZE;
    /** 精灵图宽度 (20px/图标); 由 {@link #init()} 赋值 */
    public static int SPRITESHEET_WIDTH;
    /** 设置面板用的无描边精灵图宽度 (16px/图标); 由 {@link #init()} 赋值 */
    public static int PLAIN_SPRITESHEET_WIDTH;
    public static final Identifier STRUCTURES_TEXTURE = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/icons/structures.png");
    public static final Identifier STRUCTURES_PLAIN_TEXTURE = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/icons/structures_plain.png");

    private static final StructureType[] BY_ID = new StructureType[FEATURE_NUM];

    static {
        for (StructureType t : values()) {
            BY_ID[t.id] = t;// 同ID alias取第后一个
        }
    }

    public final int id;
    public final String key;
    public final boolean enableDefault;
    public final int maxRegionHide;
    /**
     * 变种码 -> 精灵图索引 映射; 下标 = 变种码 (与 C 端 XSM_VAR_* 位码一致),
     * 值为精灵图 index, -1 = 该变种无专属贴图 (回退 {@link #getSpriteIndex(int)}).
     * 由 {@link #init()} 从 structures.ini 解析赋值; 变种 0 必有贴图。
     */
    public volatile int[] variant;
    /**
     * 每区块实际放置概率 (raw RNG 命中率 × 群系约束通过率); 仅稀疏类型
     * (regionSize=1) 大于0, 用于期望放置命中量过滤.
     * 群系通过率为 MC_26.1 实测 (见 tmp/struct-prob-test):
     * Treasure 0.01×0.0333, Desert_Well 0.001×0.0213, End_Gateway 1/700×0.0883
     * -1表示未定义
     */
    public final float prob;
    public final @Nullable Config config;

    private StructureType(int id, @NotNull String key, boolean enableDefault, int maxRegionHide) {
        this(id, key, enableDefault, maxRegionHide, -1f);
    }

    private StructureType(int id, @NotNull String key, boolean enableDefault, int maxRegionHide, float prob) {
        this.id = id;
        this.key = key;
        this.enableDefault = enableDefault;
        this.maxRegionHide = Math.min(maxRegionHide, MAX_REGION_HIDE);
        this.prob = prob;
        this.config = Xsm.getStructureConfig(id);
        if (this.config == null && id != 25/* 要塞没有config */)
            LoggerHolder.LOGGER.warn("Can't load StructureType config for {} ({})", id, key);
    }

    public static StructureType byId(int id) {
        if (id < 0 || id >= FEATURE_NUM) {
            throw new IllegalArgumentException("Invalid StructureType id: " + id);
        }
        return BY_ID[id];
    }

    /**
     * 获取变种码对应的精灵图索引; 变种不存在/无专属贴图时回退到变种 0 (默认图标)。
     * 需先执行 {@link #init()}。
     */
    public int getSpriteIndex(int variant) {
        int[] arr = this.variant;
        if (arr == null)
            throw new IllegalStateException("StructureType.init() not called");
        if (variant >= 0 && variant < arr.length && arr[variant] >= 0)
            return arr[variant];
        return arr[0];
    }

    /**
     * 变种码对应的 tooltip 翻译 key; 无变种/无文案返回 null。
     * key 语义与 C 端 XSM_VAR_* 位码一致。
     */
    public @Nullable String variantTranslationKey(int variant) {
        if (variant <= 0)
            return null;
        switch (this) {
            case END_CITY:
                return (variant & 1) != 0 ? "xsm.structure.end_city.variant.ship" : null;
            case VILLAGE: {
                if ((variant & 8) != 0)
                    return "xsm.structure.village.variant.zombie";
                return switch (variant & 7) {
                    case 1 -> "xsm.structure.village.variant.desert";
                    case 2 -> "xsm.structure.village.variant.savanna";
                    case 3 -> "xsm.structure.village.variant.taiga";
                    case 4 -> "xsm.structure.village.variant.snowy";
                    default -> null;
                };
            }
            case BASTION: {
                return switch (variant & 3) {
                    case 1 -> "xsm.structure.bastion.variant.hoglin_stable";
                    case 2 -> "xsm.structure.bastion.variant.treasure";
                    case 3 -> "xsm.structure.bastion.variant.bridge";
                    default -> null;
                };
            }
            case IGLOO:
                return (variant & 1) != 0 ? "xsm.structure.igloo.variant.basement" : null;
            case SHIPWRECK:
                return (variant & 1) != 0 ? "xsm.structure.shipwreck.variant.beached" : null;
            case RUINED_PORTAL:
            case RUINED_PORTAL_N:
                if ((variant & 1) != 0)
                    return "xsm.structure.ruined_portal.variant.giant";
                if ((variant & 2) != 0)
                    return "xsm.structure.ruined_portal.variant.underground";
                if ((variant & 4) != 0)
                    return "xsm.structure.ruined_portal.variant.airpocket";
                return null;
            case GEODE:
                return (variant & 1) != 0 ? "xsm.structure.geode.variant.cracked" : null;
            case TRIAL_CHAMBERS:
                if ((variant & 3) == 1)
                    return "xsm.structure.trial_chambers.variant.end";
                else
                    return "xsm.structure.trial_chambers.variant.corridor";
            default:
                return null;
        }
    }

    public record Config(int salt, int regionSize, int chunkRange, int dim, float rarity) {
    }

    private static @Nullable BitSetView defaultEnabled;

    public static @NotNull BitSetView defaultEnabled() {
        if (defaultEnabled != null)
            return defaultEnabled;
        // 如果是多线程, 这里可能多次构造, 不过没关系
        BitSet set = new BitSet(FEATURE_NUM);
        for (StructureType t : values()) {
            if (t.enableDefault) {
                set.set(t.id);
            }
        }
        return defaultEnabled = new BitSetView(set);
    }

    public String translationKey() {
        return "xsm.structure." + key;
    }

    /**
     * 从 structures.ini 加载变种->贴图映射并校验。
     * 格式: {@code <id>=<variant>:<index>;<variant>:<index>;...}
     * (variant 为 C 端变种码, index 为精灵图序号)。
     * 任何结构缺少默认图标 (variant 0) 时直接抛异常 — 启动阶段失败而非运行时黑图。
     */
    public static void init() {
        EnumMap<@NotNull StructureType, int[]> spriteMap = new EnumMap<>(StructureType.class);
        int maxIndex = 0;
        try (var in = StructureType.class.getResourceAsStream(
                "/assets/seed-map-for-xaero/textures/icons/structures.ini")) {
            if (in == null) {
                throw new IllegalStateException(
                        "structures.ini not found: /assets/seed-map-for-xaero/textures/icons/structures.ini");
            }
            try (var reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#"))
                        continue;
                    String[] kv = line.split("=", 2);
                    if (kv.length != 2)
                        throw new IllegalStateException(
                                "structures.ini:" + lineNo + ": expected '<id>=<variant>:<index>;...'");
                    int id;
                    try {
                        id = Integer.parseInt(kv[0].trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException("structures.ini:" + lineNo + ": bad structure id", e);
                    }
                    final StructureType type = byId(id);
                    TreeMap<Integer, Integer> pairs = new TreeMap<>();
                    for (String entry : kv[1].trim().split(";")) {
                        if (entry.isEmpty())
                            continue;
                        String[] vk = entry.split(":");
                        if (vk.length != 2)
                            throw new IllegalStateException(
                                    "structures.ini:" + lineNo + ": bad variant entry '" + entry + "'");
                        int variant = Integer.parseInt(vk[0].trim());
                        int index = Integer.parseInt(vk[1].trim());
                        if (index < 0)
                            throw new IllegalStateException(
                                    "structures.ini:" + lineNo + ": negative sprite index for " + type.key);
                        pairs.put(variant, index);
                        maxIndex = Math.max(maxIndex, index);
                    }
                    int[] arr = new int[(pairs.isEmpty() ? 0 : pairs.lastKey()) + 1];
                    Arrays.fill(arr, -1);
                    pairs.forEach((v, i) -> arr[v] = i);
                    spriteMap.put(type, arr);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load structures.ini", e);
        }

        // 校验: 每个结构必须拥有默认图标 (变种 0), 否则启动即失败
        for (StructureType t : values()) {
            // 同 ID alias (如 jungle_temple/jungle_pyramid) 共享 byId 解析到的数组
            int[] arr = spriteMap.get(byId(t.id));
            if (arr == null || arr.length == 0 || arr[0] < 0) {
                throw new IllegalStateException(
                        "No default icon (variant 0) for structure '" + t.key + "' in structures.ini");
            }
            t.variant = arr;
        }
        SHEET_SIZE = maxIndex + 1;
        SPRITESHEET_WIDTH = SHEET_SIZE * 20;
        PLAIN_SPRITESHEET_WIDTH = SHEET_SIZE * 16;
    }
}
