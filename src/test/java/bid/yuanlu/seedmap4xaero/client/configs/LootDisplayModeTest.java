package bid.yuanlu.seedmap4xaero.client.configs.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LootDisplayModeTest {

    @Test
    void isTiled() {
        assertFalse(LootDisplayMode.QUICK_PEEK.isTiled());
        assertFalse(LootDisplayMode.DETAIL.isTiled());
        assertTrue(LootDisplayMode.TILED_PEEK.isTiled());
        assertTrue(LootDisplayMode.TILED_DETAIL.isTiled());
    }

    @Test
    void isDetail() {
        assertFalse(LootDisplayMode.QUICK_PEEK.isDetail());
        assertTrue(LootDisplayMode.DETAIL.isDetail());
        assertFalse(LootDisplayMode.TILED_PEEK.isDetail());
        assertTrue(LootDisplayMode.TILED_DETAIL.isDetail());
    }

    @Test
    void translationKey() {
        assertEquals("xsm.gui.panel.loot_mode.quick_peek", LootDisplayMode.QUICK_PEEK.translationKey());
        assertEquals("xsm.gui.panel.loot_mode.detail", LootDisplayMode.DETAIL.translationKey());
        assertEquals("xsm.gui.panel.loot_mode.tiled_peek", LootDisplayMode.TILED_PEEK.translationKey());
        assertEquals("xsm.gui.panel.loot_mode.tiled_detail", LootDisplayMode.TILED_DETAIL.translationKey());
    }

    @Test
    void defaultModeIsQuickPeek() {
        assertEquals(LootDisplayMode.QUICK_PEEK, new ConfigData().getLootDisplayMode());
    }
}
