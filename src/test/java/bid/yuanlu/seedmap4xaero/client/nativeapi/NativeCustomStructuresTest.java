package bid.yuanlu.seedmap4xaero.client.nativeapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.test.NativeMcTest;

/**
 * 数据包自定义结构的 native 集成测试: {@code xsmSetCustomStructures} 注入 →
 * {@code queryRegionStructuresGrid}/{@code xsmGetStructureConfig} 查询闭环。
 * 网格/掷骰的逐字节正确性已在 C 单测 (unit_tests.cpp) 对拍 cubiomes
 * getFeaturePos/getLargeStructurePos 与独立掷骰参考; 本套只验证 Java→C 的
 * FFM 布局与生命周期 (注入/清除/维度过滤)。
 */
class NativeCustomStructuresTest extends NativeMcTest {

    @AfterEach
    void clearCustomTable() {
        Xsm.setCustomStructures(new int[0], new int[0]);
        // 恢复基类世界状态 (dimFilter 会切到下界)
        Xsm.setWorld(SEED, 0);
    }

    /** 单结构集合: 参数取自 native Village 配置 → 网格位置与原版候选逐字节一致
     * (原版额外做 biome 过滤, 故原版命中 ⊆ 自定义命中)。 */
    @Test
    void singleEntryMatchesVanillaVillageGrid() {
        var village = bid.yuanlu.seedmap4xaero.client.structure.StructureType.VILLAGE.config();
        assertTrue(village != null && village.regionSize() > village.chunkRange(),
                "village config unavailable");
        final int salt = village.salt();
        final int spacing = village.regionSize();
        final int separation = village.regionSize() - village.chunkRange();
        int[] sets = { salt, spacing, separation, 0, 0, 0, 1, 1065353216, 0 };
        int[] entries = { 100, 1 };
        assertTrue(Xsm.setCustomStructures(sets, entries));

        final int rx0 = -2, rz0 = -2, rx1 = 3, rz1 = 3;
        final int regionCount = (rx1 - rx0) * (rz1 - rz0);
        List<int[]> custom = collectGrid(100, rx0, rz0, rx1, rz1);
        List<int[]> vanilla = collectGrid(5, rx0, rz0, rx1, rz1);

        // 自定义无 biome 校验: 每个 region 恰有一个候选
        assertEquals(regionCount, custom.size(), "single-entry custom grid hits every region");
        var customPos = new java.util.HashSet<Long>();
        for (int[] p : custom)
            customPos.add(((long) p[0] << 32) | (p[1] & 0xFFFFFFFFL));
        // 原版 (biome 过滤后) 的命中必须全部出现在自定义网格里
        for (int[] p : vanilla) {
            assertTrue(customPos.contains(((long) p[0] << 32) | (p[1] & 0xFFFFFFFFL)),
                    "vanilla village position missing from custom grid: " + p[0] + "," + p[1]);
        }
    }

    /** 配置查询: spacing/separation/dim 原样回传; 未注入 id 失败。 */
    @Test
    void structureConfigForCustomIds() {
        int[] sets = { 2358902, 27, 15, 1, 0, 0, 2, 1065353216, 0 };
        int[] entries = { 100, 3, 101, 1 };
        assertTrue(Xsm.setCustomStructures(sets, entries));
        Xsm.setWorld(SEED, 0);

        var cfg = Xsm.getStructureConfig(101);
        assertEquals(2358902, cfg.salt());
        assertEquals(27, cfg.regionSize());
        assertEquals(12, cfg.chunkRange(), "chunkRange = spacing - separation");
        assertEquals(0, cfg.dim());
        assertEquals(0f, cfg.rarity());
        assertEquals(null, Xsm.getStructureConfig(999), "未注入 id 返回 null");
    }

    /** 维度过滤: 下界集合在主世界查询返回 0。 */
    @Test
    void dimFilter() {
        int[] sets = { 12345, 20, 8, 0, -1, 0, 1, 1065353216, 0 };
        int[] entries = { 100, 1 };
        assertTrue(Xsm.setCustomStructures(sets, entries));

        Xsm.setWorld(SEED, 0);
        assertTrue(collectGrid(100, -1, -1, 2, 2).isEmpty(), "主世界查询下界结构 = 空");

        Xsm.setWorld(SEED, -1);
        assertFalse(collectGrid(100, -1, -1, 2, 2).isEmpty(), "下界查询应命中");
    }

    /** 多结构集合: 各 id 的命中并集恰覆盖每个 region 一次。 */
    @Test
    void multiEntrySelectionCoversEachRegionExactlyOnce() {
        // 3 等权结构, Terralith regular 同款参数
        int[] sets = { 2358902, 27, 15, 0, 0, 0, 3, 1065353216, 0 };
        int[] entries = { 100, 1, 101, 1, 102, 1 };
        assertTrue(Xsm.setCustomStructures(sets, entries));
        Xsm.setWorld(SEED, 0);

        final int rx0 = -4, rz0 = -4, rx1 = 4, rz1 = 4;
        int total = 0;
        for (int id = 100; id <= 102; id++) {
            total += collectGrid(id, rx0, rz0, rx1, rz1).size();
        }
        assertEquals(64, total, "8×8 region 每个恰有一个赢家");
    }

    /** 参数校验: 非法 id/权重/分组 → false; 空 = 清除成功。 */
    @Test
    void validationAndClear() {
        int[] bad = { 0, 4, 2, 0, 0, 0, 1, 1065353216, 0 };
        int[] badId = { 99, 1 };
        assertFalse(Xsm.setCustomStructures(bad, badId));
        int[] badWeight = { 100, 0 };
        assertFalse(Xsm.setCustomStructures(bad, badWeight));
        assertTrue(Xsm.setCustomStructures(new int[0], new int[0]));
    }

    private static List<int[]> collectGrid(int type, int rx0, int rz0, int rx1, int rz1) {
        List<int[]> out = new ArrayList<>();
        Xsm.queryRegionStructuresGrid(type, rx0, rz0, rx1, rz1, 0, 0, 0, 0,
                (rx, rz, found, bx, bz, variant) -> {
                    if (found)
                        out.add(new int[] { bx, bz });
                });
        return out;
    }

    /**
     * frequency<1 频率门对拍: 用 java.util.Random (与 vanilla LegacyRandomSource
     * 同 LCG) 独立复刻 4 种 FrequencyReductionMethod (语义按 MC 26.x 字节码锁定,
     * shouldGenerate(seed, salt, chunkX, chunkZ, frequency)), 与 C 侧 gate
     * 逐 chunk 对比。spacing=1 (YUNG's 形状) 使每个 chunk 都是候选。
     */
    @Test
    void frequencyGateMatchesVanillaReducers() {
        final long seed = 0x1A2B3C4D5E6F7809L;
        final int salt = 593751784; // YUNG's Better Mineshafts
        final float freq = 0.003f;
        // (frequencyBits, method) 四种 reducer 逐一验证
        int[][] cases = {
                { Float.floatToIntBits(freq), 0 },  // default
                { Float.floatToIntBits(freq), 1 },  // legacy_type_1
                { Float.floatToIntBits(freq), 2 },  // legacy_type_2
                { Float.floatToIntBits(freq), 3 },  // legacy_type_3
        };
        for (int[] c : cases) {
            int[] sets = { salt, 1, 0, 0, 0, 0, 1, c[0], c[1] };
            int[] entries = { 100, 1 };
            assertTrue(Xsm.setCustomStructures(sets, entries), "method " + c[1]);
            Xsm.setWorld(seed, 0);

            var expected = new java.util.HashSet<Long>();
            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    if (frequencyPass(seed, salt, x, z, freq, c[1]))
                        expected.add(((long) (x * 16) << 32) | ((z * 16) & 0xFFFFFFFFL));
                }
            }
            List<int[]> actual = collectGrid(100, -8, -8, 8, 8);
            assertEquals(expected.size(), actual.size(), "method " + c[1] + " hit count");
            for (int[] p : actual) {
                assertTrue(expected.contains(((long) p[0] << 32) | (p[1] & 0xFFFFFFFFL)),
                        "method " + c[1] + " unexpected hit at " + p[0] + "," + p[1]);
            }
        }
        Xsm.setCustomStructures(new int[0], new int[0]);
    }

    /** MC 26.x StructurePlacement 4 种频率约简器的独立复刻 (java.util.Random)。 */
    private static boolean frequencyPass(long seed, int salt, int x, int z,
            float frequency, int method) {
        switch (method) {
            case 0: { // DEFAULT: setLargeFeatureWithSalt(seed, salt, x, z); nextFloat < freq
                Random r = new Random(seed);
                r.setSeed(salt * 341873128712L + x * 132897987541L + seed + z);
                return r.nextFloat() < frequency;
            }
            case 1: { // LEGACY_TYPE_1: setLargeFeatureSeed(seed, x, z); nextDouble < freq
                Random r = new Random(seed);
                r.setSeed(seed);
                long a = r.nextLong(), b = r.nextLong();
                r.setSeed(x * a ^ z * b ^ seed);
                return r.nextDouble() < frequency;
            }
            case 2: { // LEGACY_TYPE_2: setLargeFeatureWithSalt(seed, x, z, 10387320); nextFloat < freq
                Random r = new Random(seed);
                r.setSeed(x * 341873128712L + z * 132897987541L + seed + 10387320L);
                return r.nextFloat() < frequency;
            }
            default: { // LEGACY_TYPE_3: setSeed((int)((x>>4) ^ ((z>>4)<<4)) ^ seed); nextInt(); nextInt(1/freq)==0
                Random r = new Random(seed);
                r.setSeed(((long) ((x >> 4) ^ ((z >> 4) << 4))) ^ seed);
                r.nextInt();
                return r.nextInt((int) (1.0f / frequency)) == 0;
            }
        }
    }
}
