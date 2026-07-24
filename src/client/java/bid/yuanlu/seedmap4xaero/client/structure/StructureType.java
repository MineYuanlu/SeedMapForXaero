package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.BitSet;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

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
    TREASURE(14, "treasure", false, 2048), // 埋藏的宝藏
    MINESHAFT(15, "mineshaft", false, 1024), // 废弃矿井
    DESERT_WELL(16, "desert_well", false, 2048), // 沙漠水井
    GEODE(17, "geode", false, 2048), // 紫晶洞
    FORTRESS(18, "fortress", true, Integer.MAX_VALUE), // 下界要塞
    BASTION(19, "bastion", true, Integer.MAX_VALUE), // 堡垒遗迹
    END_CITY(20, "end_city", true, Integer.MAX_VALUE), // 末地城
    END_GATEWAY(21, "end_gateway", false, Integer.MAX_VALUE), // 末地折跃门
    END_ISLAND(22, "end_island", false, Integer.MAX_VALUE), // 末地岛屿
    TRAIL_RUINS(23, "trail_ruins", false, Integer.MAX_VALUE), // 古迹废墟
    TRIAL_CHAMBERS(24, "trial_chambers", false, Integer.MAX_VALUE), // 试炼密室
    STRONGHOLD(25, "stronghold", true, Integer.MAX_VALUE); // 要塞

    private static final class LoggerHolder {
        private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureType");
    }

    public static final int FEATURE_NUM = Xsm.getStructFEATURE_NUM();
    public static final int MAX_REGION_HIDE = 2048;// 全局最大上限

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
    public final @Nullable Config config;

    private StructureType(int id, @NotNull String key, boolean enableDefault, int maxRegionHide) {
        this.id = id;
        this.key = key;
        this.enableDefault = enableDefault;
        this.maxRegionHide = Math.min(maxRegionHide, MAX_REGION_HIDE);
        this.config = Xsm.getStructureConfig(id);
        if (this.config == null)
            LoggerHolder.LOGGER.warn("Can't load StructureType config for {} ({})", id, key);

        // Objects.requireNonNull(, ()->"Can't load StructureType config for id: "+id);
    }

    public static StructureType byId(int id) {
        if (id < 0 || id >= FEATURE_NUM) {
            throw new IllegalArgumentException("Invalid StructureType id: " + id);
        }
        return BY_ID[id];
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

    public static void init() {
    }
}
