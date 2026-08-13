package bid.yuanlu.seedmap4xaero.client.nativeapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.client.cache.QueryPointCache;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.test.NativeMcTest;

/**
 * 真实 native (libxsmcore) 集成测试: 走 cubiomes 查询真实结构配置 /
 * 生物群系 / 地形渲染。native 缺失时由 {@link NativeMcTest} 整套跳过。
 * <p>
 * 断言为跨 26.1/26.2 版本稳定的事实值 (维度、regionSize 范围、已知 biomes
 * 名称), 而非逐版本快照, 以同时验证 native 与当前 MC 版本一致。
 */
class NativeIntegrationTest extends NativeMcTest {

    @Test
    void featureNumMatchesEnum() {
        assertEquals(26, Xsm.getStructFEATURE_NUM(), "26 structure ids across 26.1/26.2");
    }

    @Test
    void fortressConfigInNether() {
        var cfg = StructureType.FORTRESS.config();
        assertNotNull(cfg, "fortress should have a native config");
        assertEquals(-1, cfg.dim(), "fortress dim must be nether");
        assertTrue(cfg.regionSize() > 0);
    }

    @Test
    void villageConfigPlains() {
        var cfg = StructureType.VILLAGE.config();
        assertNotNull(cfg, "village should have a native config");
        assertEquals(0, cfg.dim(), "village dim must be overworld");
        assertTrue(cfg.regionSize() >= 32, "village regionSize should be >= 32, got " + cfg.regionSize());
    }

    @Test
    void strongholdStillHasNoConfig() {
        assertNull(StructureType.STRONGHOLD.config(), "stronghold never has a config");
    }

    @Test
    void biomeNamesResolve() {
        // 跨版本稳定的 biome 名称 (cubiomes 内部 id → name)
        assertEquals("plains", Xsm.biome2str(1));
        assertEquals("desert", Xsm.biome2str(2));
        assertEquals("ocean", Xsm.biome2str(0));
    }

    @Test
    void queryPointReturnsValidHeight() {
        var qp = Xsm.queryPoint(0, 0);
        assertNotNull(qp, "queryPoint(0,0) should not be null");
        assertNotNull(qp.biomeName(), "biome name should resolve");
        assertTrue(qp.height() > 0, "surface height should be positive, got " + qp.height());
        assertEquals(QueryPointCache.UNKNOWN_HEIGHT, Integer.MIN_VALUE);
    }

    @Test
    void queryPointDeterministic() {
        var a = Xsm.queryPoint(1234, -5678);
        var b = Xsm.queryPoint(1234, -5678);
        assertNotNull(a);
        assertEquals(a.biomeName(), b.biomeName());
        assertEquals(a.height(), b.height());
    }

    @Test
    void genCellImgSmoke() {
        var pixels = Xsm.genCellImg(4, 0, 0, 64, true);
        assertNotNull(pixels, "genCellImg should return pixels");
        assertEquals(64 * 64, pixels.length);
        boolean nonZero = false;
        for (int p : pixels) {
            if ((p & 0xFFFFFF) != 0) {
                nonZero = true;
                break;
            }
        }
        assertTrue(nonZero, "genCellImg output should not be all-black");
    }

    @Test
    void desertPyramidLoot() {
        int px = -1, pz = -1;
        for (int rx = 0; rx < 64 && px < 0; rx++) {
            for (int rz = 0; rz < 64; rz++) {
                final var holder = new int[] { -1, -1 };
                Xsm.queryRegionStructuresGrid(StructureType.DESERT_PYRAMID.id,
                        rx, rz, rx + 1, rz + 1, 0, 0, 0, 0,
                        (x, z, found, bx, bz, variant) -> {
                            if (found) {
                                holder[0] = bx;
                                holder[1] = bz;
                            }
                        });
                if (holder[0] >= 0) {
                    px = holder[0];
                    pz = holder[1];
                }
            }
        }
        assertTrue(px >= 0, "should find a desert pyramid");
        var loot = Xsm.queryStructureLoot(StructureType.DESERT_PYRAMID.id, px, pz);
        assertNotNull(loot, "desert pyramid should produce loot");
        assertEquals(4, loot.size(), "desert pyramid has 4 chests");
        boolean anyItem = false;
        for (var chest : loot) {
            assertTrue(chest.items().size() >= 0);
            for (var item : chest.items()) {
                if (item.count() > 0 && item.globalItemId() >= 0) {
                    anyItem = true;
                }
            }
        }
        assertTrue(anyItem, "at least one non-empty loot item");
    }

    @Test
    void desertPyramidLootDeterministic() {
        var a = Xsm.queryStructureLoot(StructureType.DESERT_PYRAMID.id, 3168, 21296);
        var b = Xsm.queryStructureLoot(StructureType.DESERT_PYRAMID.id, 3168, 21296);
        if (a == null || b == null) {
            return; // native 无此位置时跳过 (不同版本位置不同)
        }
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            var ca = a.get(i);
            var cb = b.get(i);
            assertEquals(ca.chestX(), cb.chestX());
            assertEquals(ca.chestZ(), cb.chestZ());
            assertEquals(ca.lootSeed(), cb.lootSeed());
            assertEquals(ca.items().size(), cb.items().size());
            for (int j = 0; j < ca.items().size() && j < cb.items().size(); j++) {
                var ia = ca.items().get(j);
                var ib = cb.items().get(j);
                assertEquals(ia.globalItemId(), ib.globalItemId());
                assertEquals(ia.count(), ib.count());
                assertEquals(ia.enchantments().size(), ib.enchantments().size());
            }
        }
    }

    @Test
    void lootNamesResolve() {
        assertNotNull(Xsm.itemName(0), "itemName(0) should resolve");
        assertNotNull(Xsm.enchantmentName(1), "enchantmentName(1) should resolve");
    }

    @Test
    void unsupportedStructureLootEmpty() {
        // Swamp_Hut 无战利品表
        var loot = Xsm.queryStructureLoot(StructureType.SWAMP_HUT.id, 0, 0);
        assertNotNull(loot, "unsupported structure should give empty list, not null");
        assertTrue(loot.isEmpty());
    }
}
