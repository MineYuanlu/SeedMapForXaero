package bid.yuanlu.seedmap4xaero.client.nativeapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.test.NativeMcTest;

/**
 * 跨 MC 版本切换（ViaVersion 场景）: 运行时重复 setGameVersion 后
 * 世界生成真实生效。native 缺失时由 {@link NativeMcTest} 整套跳过。
 */
class NativeVersionSwitchTest extends NativeMcTest {

    @Test
    void switchToLegacyAndBack() {
        assertTrue(Xsm.applyGameVersion("1.18.2"));
        try {
            Xsm.setWorld(SEED, 0);
            assertNotNull(Xsm.queryPoint(0, 0), "query works under 1.18.2");
        } finally {
            assertTrue(Xsm.applyGameVersion(null), "switch back to client version");
        }
        Xsm.setWorld(SEED, 0);
        assertNotNull(Xsm.queryPoint(0, 0), "query works after switching back");
    }

    @Test
    void sameVersionIsDeduped() {
        assertTrue(Xsm.applyGameVersion(null));
        // 重复应用同一版本不应失败（内部去重，不重置生成器）
        assertTrue(Xsm.applyGameVersion(null));
    }

    @Test
    void unsupportedVersionRejectedThenRecovers() {
        assertFalse(Xsm.applyGameVersion("99.99.99"), "unknown version must be rejected");
        // 拒绝后原版本继续可用
        Xsm.setWorld(SEED, 0);
        assertNotNull(Xsm.queryPoint(0, 0));
        assertTrue(Xsm.applyGameVersion(null));
    }

    @Test
    void versionActuallyChangesGeneration() {
        // 1.17 (旧地形噪声) 与 1.18+ (新噪声) 在同一种子下必然生成不同地形
        int[][] coords = { { 0, 0 }, { 100_000, 100_000 }, { -50_000, 30_000 }, { -123_456, -65_432 } };
        int[][] legacyPixels = pixelsUnder(coords, "1.17.1");
        int[][] modernPixels = pixelsUnder(coords, "1.18.2");
        boolean differs = false;
        for (int i = 0; i < coords.length && !differs; i++) {
            if (legacyPixels[i] == null || modernPixels[i] == null)
                continue;
            differs = !java.util.Arrays.equals(legacyPixels[i], modernPixels[i]);
        }
        assertTrue(differs, "terrain generation must differ between 1.17.1 and 1.18.2");
        assertTrue(Xsm.applyGameVersion(null));
    }

    private int[][] pixelsUnder(int[][] coords, String version) {
        assertTrue(Xsm.applyGameVersion(version));
        Xsm.setWorld(SEED, 0);
        int[][] out = new int[coords.length][];
        for (int i = 0; i < coords.length; i++)
            out[i] = Xsm.genCellImg(4, coords[i][0], coords[i][1], 64, false);
        return out;
    }
}
