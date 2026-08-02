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
}
