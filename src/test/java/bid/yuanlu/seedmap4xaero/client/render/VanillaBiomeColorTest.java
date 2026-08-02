package bid.yuanlu.seedmap4xaero.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.test.McBootstrap;

class VanillaBiomeColorTest extends McBootstrap {

    private static final int UNKNOWN_COLOR = 0x555555;

    @Test
    void waterBiomeColor() {
        // ocean=0 → water type, WATER_COLOR[0]=0x000070
        assertEquals(0x000070, VanillaBiomeColor.INSTANCE.getColor(0) & 0xFFFFFF);
    }

    @Test
    void desertFixedColor() {
        // desert=2 → fixed(2, 0xFAD98F)
        assertEquals(0xFAD98F, VanillaBiomeColor.INSTANCE.getColor(2) & 0xFFFFFF);
    }

    @Test
    void plainsFixedColor() {
        // plains=1 → grass(1, 0.8, 0.4, 0x91BD59); fallback color when grass-map yields magenta
        // getColor must not be 0xFF00FF nor fallback unknown
        int c = VanillaBiomeColor.INSTANCE.getColor(1);
        assertNotEquals(0xFF00FF, c);
        assertNotEquals(UNKNOWN_COLOR, c);
    }

    @Test
    void outOfRangeReturnsUnknown() {
        assertEquals(UNKNOWN_COLOR, VanillaBiomeColor.INSTANCE.getColor(-1));
        assertEquals(UNKNOWN_COLOR, VanillaBiomeColor.INSTANCE.getColor(256));
        assertEquals(UNKNOWN_COLOR, VanillaBiomeColor.INSTANCE.getColor(9999));
    }

    @Test
    void aquaticFlags() {
        assertTrue(VanillaBiomeColor.INSTANCE.isAquatic(0));  // ocean
        assertTrue(VanillaBiomeColor.INSTANCE.isAquatic(10)); // frozen_ocean
        org.junit.jupiter.api.Assertions.assertFalse(VanillaBiomeColor.INSTANCE.isAquatic(1)); // plains
        org.junit.jupiter.api.Assertions.assertFalse(VanillaBiomeColor.INSTANCE.isAquatic(-1));
    }

    @Test
    void swampColorDeterministic() {
        // 同一坐标两次一致 (PerlinSimplexNoise 固定 seed 0)
        int a = VanillaBiomeColor.INSTANCE.getColor(6, 123, 456);
        int b = VanillaBiomeColor.INSTANCE.getColor(6, 123, 456);
        assertEquals(a, b);
        // 颜色必须落在两个 swamp 候选色之一
        int rgb = a & 0xFFFFFF;
        assertTrue(rgb == 0x4C763C || rgb == 0x6A7039, "unexpected swamp color 0x" + Integer.toHexString(rgb));
    }

    @Test
    void temperatureDownfallClamped() {
        assertEquals(0.8f, VanillaBiomeColor.getTemperature(1));
        assertEquals(0.4f, VanillaBiomeColor.getDownfall(1));
        assertEquals(0.5f, VanillaBiomeColor.getTemperature(999));
        assertEquals(0.5f, VanillaBiomeColor.getDownfall(-1));
    }
}
