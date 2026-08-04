package bid.yuanlu.seedmap4xaero.client.structure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.TreeMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus.OverrideOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntImmutableList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import net.minecraft.resources.Identifier;

/**
 * 生成结构枚举表
 */
public enum StructureType {
    /** 地物 */
    FEATURE(0, "feature", false, Integer.MAX_VALUE),
    /** 沙漠神殿 */
    DESERT_PYRAMID(1, "desert_pyramid", true, Integer.MAX_VALUE),
    /** 丛林神庙 */
    JUNGLE_PYRAMID(2, "jungle_pyramid", true, Integer.MAX_VALUE),
    /** 沼泽小屋 */
    SWAMP_HUT(3, "swamp_hut", true, Integer.MAX_VALUE),
    /** 雪屋 */
    IGLOO(4, "igloo", true, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.igloo.basement";
            else
                return "xsm.structure.igloo.normal";
        }

    },
    /** 村庄 */
    VILLAGE(5, "village", true, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(
                new int[] { 0, 1, 2, 3, 4, 8, 9, 10, 11, 12 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            return switch (variant & 15) {
                default/* 0 */ -> "xsm.structure.village.plains";// 平原
                case 1 -> "xsm.structure.village.desert";// 沙漠
                case 2 -> "xsm.structure.village.savanna"; // 热带草原
                case 3 -> "xsm.structure.village.taiga"; // 针叶林
                case 4 -> "xsm.structure.village.snowy"; // 雪原
                case 8 -> "xsm.structure.village.zombie_plains";
                case 9 -> "xsm.structure.village.zombie_desert";
                case 10 -> "xsm.structure.village.zombie_savanna";
                case 11 -> "xsm.structure.village.zombie_taiga";
                case 12 -> "xsm.structure.village.zombie_snowy";
            };
        }
    },
    /** 海底废墟 */
    OCEAN_RUIN(6, "ocean_ruin", false, Integer.MAX_VALUE),
    /** 沉船 */
    SHIPWRECK(7, "shipwreck", true, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.shipwreck.beached";
            else
                return "xsm.structure.shipwreck.normal";
        }
    },
    /** 海底神殿 */
    MONUMENT(8, "monument", true, Integer.MAX_VALUE),
    /** 林地府邸 */
    MANSION(9, "mansion", true, Integer.MAX_VALUE),
    /** 掠夺者前哨站 */
    OUTPOST(10, "outpost", true, Integer.MAX_VALUE),
    /** 废弃传送门（主世界） */
    RUINED_PORTAL(11, "ruined_portal", false, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.ruined_portal.giant";
            return "xsm.structure.ruined_portal.normal";
        }
    },
    /** 废弃传送门（下界） */
    RUINED_PORTAL_N(12, "ruined_portal_nether", false, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.ruined_portal_nether.giant";
            return "xsm.structure.ruined_portal_nether.normal";
        }
    },
    /** 远古城市 */
    ANCIENT_CITY(13, "ancient_city", true, Integer.MAX_VALUE),
    /** 埋藏的宝藏 */
    TREASURE(14, "treasure", false, 2048, 0.01f * 0.0333f),
    /** 废弃矿井 */
    MINESHAFT(15, "mineshaft", false, 1024, 0.004f),
    /** 沙漠水井 */
    DESERT_WELL(16, "desert_well", false, 2048, 0.001f * 0.0213f),
    /** 紫晶洞 */
    GEODE(17, "geode", false, 2048, 1f / 24) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.geode.cracked";
            else
                return "xsm.structure.geode.normal";
        }
    },
    /** 下界要塞 */
    FORTRESS(18, "fortress", true, Integer.MAX_VALUE),
    /** 堡垒遗迹 */
    BASTION(19, "bastion", true, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1, 2, 3 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            return switch (variant & 3) {
                default -> "xsm.structure.bastion.units"; // 居住区
                case 1 -> "xsm.structure.bastion.hoglin_stable";// 疣猪兽棚
                case 2 -> "xsm.structure.bastion.treasure";// 藏宝室
                case 3 -> "xsm.structure.bastion.bridge";// 桥
            };
        }
    },
    /** 末地城 */
    END_CITY(20, "end_city", true, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }

        @Override
        public @NotNull String variantTranslationKey(int variant) {
            if ((variant & 1) != 0)
                return "xsm.structure.end_city.ship";
            else
                return "xsm.structure.end_city.normal";
        }
    },
    /** 末地折跃门 */
    END_GATEWAY(21, "end_gateway", false, Integer.MAX_VALUE, 1f / 700 * 0.0883f),
    /** 末地岛屿 */
    END_ISLAND(22, "end_island", false, Integer.MAX_VALUE, 1f / 14),
    /** 古迹废墟 */
    TRAIL_RUINS(23, "trail_ruins", false, Integer.MAX_VALUE),
    /** 试炼密室 */
    TRIAL_CHAMBERS(24, "trial_chambers", false, Integer.MAX_VALUE) {
        private static final IntImmutableList VARIANTS = new IntImmutableList(new int[] { 0, 1, 2, 3 });

        @OverrideOnly
        public IntList getVariants() {
            return VARIANTS;
        }
        // TODO: 2bit的 variant暂时没搞清楚对应关系
    },
    /** 要塞 */
    STRONGHOLD(25, "stronghold", true, Integer.MAX_VALUE);

    private static final class LoggerHolder {
        private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureType");
    }

    public static final int FEATURE_NUM = safeFeatureNum();

    /**
     * native 库不可用时的回退值: 由枚举定义的最大 id + 1 推导。
     * 生产环境 native 必定可用(启动时 setGameVersion 强制), 此回退仅服务于
     * 无 native 的单元测试 JVM。
     */
    private static int safeFeatureNum() {
        try {
            return Xsm.getStructFEATURE_NUM();
        } catch (Throwable t) {
            int max = -1;
            for (StructureType v : values())
                max = Math.max(max, v.id);
            LoggerHolder.LOGGER.warn(
                    "FEATURE_NUM from native unavailable, falling back to {}", max + 1);
            return max + 1;
        }
    }

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

    private volatile @Nullable Config lazyConfig;

    private StructureType(int id, @NotNull String key, boolean enableDefault, int maxRegionHide) {
        this(id, key, enableDefault, maxRegionHide, -1f);
    }

    private StructureType(int id, @NotNull String key, boolean enableDefault, int maxRegionHide, float prob) {
        this.id = id;
        this.key = key;
        this.enableDefault = enableDefault;
        this.maxRegionHide = Math.min(maxRegionHide, MAX_REGION_HIDE);
        this.prob = prob;
    }

    /**
     * 惰性加载 native 侧结构配置 (cubiomes getStructureConfig)。
     * 延迟到首次访问而非枚举类初始化, 使无 native 的测试 JVM 可以安全加载枚举。
     */
    public @Nullable Config config() {
        var c = lazyConfig;
        if (c != null)
            return c;
        synchronized (this) {
            c = lazyConfig;
            if (c == null) {
                c = Xsm.getStructureConfig(id);
                if (c == null && id != 25/* 要塞没有config */)
                    LoggerHolder.LOGGER.warn("Can't load StructureType config for {} ({})", id, key);
                lazyConfig = c;
            }
        }
        return c;
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

    /** 整体翻译 key */
    public @NotNull String translationKey() {
        return "xsm.structure." + key;
    }

    /** 变种码对应的翻译 key */
    public @NotNull String variantTranslationKey(int variant) {
        return "xsm.structure." + key;
    }

    /** 
     * 获取当前类型支持的variants码表
     * <p>
     * 仅用于UI显示时遍历
     */
    public IntList getVariants() {
        return IntLists.emptyList();
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
