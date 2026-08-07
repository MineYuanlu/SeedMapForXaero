package bid.yuanlu.seedmap4xaero.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import net.minecraft.world.phys.Vec3;

class HighlightHudRendererTest {

    @BeforeAll
    static void init() {
        StructureType.init();
    }

    @Test
    void frontCenterMapsToScreenCenter() {
        float[] s = HighlightHudRenderer.ndcToScreen(new Vec3(0, 0, 0.5), 1000, 800);
        assertNotNull(s);
        assertEquals(500.0f, s[0], 1e-6f);
        assertEquals(400.0f, s[1], 1e-6f);
    }

    @Test
    void behindCameraReturnsNull() {
        assertNull(HighlightHudRenderer.ndcToScreen(new Vec3(0, 0, 2.0), 1000, 800));
    }

    @Test
    void positiveXOffsetMovesRight() {
        float[] s = HighlightHudRenderer.ndcToScreen(new Vec3(0.5, 0, 0.5), 1000, 800);
        assertEquals(750.0f, s[0], 1e-6f);
        assertEquals(400.0f, s[1], 1e-6f);
    }

    @Test
    void positiveYOffsetMovesUp() {
        // NDC y 向上, 屏幕 y 向下
        float[] s = HighlightHudRenderer.ndcToScreen(new Vec3(0, 0.5, 0.5), 1000, 800);
        assertEquals(500.0f, s[0], 1e-6f);
        assertEquals(200.0f, s[1], 1e-6f);
    }

    @Test
    void keepsSubPixelPrecision() {
        // 回归: 旧的 (int) 截断会把 562.5 变成 562, 导致移动时每帧跳格
        float[] s = HighlightHudRenderer.ndcToScreen(new Vec3(0.125, 0, 0.5), 1000, 800);
        assertEquals(562.5f, s[0], 1e-3f);
        assertEquals(400.0f, s[1], 1e-6f);
    }

    @Test
    void spriteUvInsideUnitSquare() {
        float[] uv = HighlightHudRenderer.spriteUv(StructureType.VILLAGE, 0);
        assertTrue(uv[0] >= 0.0f && uv[0] < 1.0f, "u0 must be in [0,1): " + uv[0]);
        assertTrue(uv[1] > uv[0] && uv[1] <= 1.0f, "u1 must be within (u0,1]: " + uv[1]);
    }

    @Test
    void spriteUvDistinctVariants() {
        float[] plains = HighlightHudRenderer.spriteUv(StructureType.VILLAGE, 0);
        float[] desert = HighlightHudRenderer.spriteUv(StructureType.VILLAGE, 1);
        assertTrue(plains[0] != desert[0], "village plains and desert should use different sprites");
    }

    @Test
    void spriteUvCellWidthMatchesSourceGrid() {
        float[] uv = HighlightHudRenderer.spriteUv(StructureType.VILLAGE, 0);
        float expectedCell = (float) HighlightHudRenderer.SOURCE_CELL / StructureType.SPRITESHEET_WIDTH;
        assertEquals(expectedCell, uv[1] - uv[0], 1e-7f);
    }
}
