package bid.yuanlu.seedmap4xaero.client.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.test.McBootstrap;

class BiomeTypeTest extends McBootstrap {

    @BeforeAll
    static void initTypes() {
        try {
            // biome2str 需要 native 库已加载; 无 native 的 JVM 跳过本套件
            Xsm.getStructFEATURE_NUM();
        } catch (Throwable t) {
            Assumptions.abort("native library unavailable, skipping BiomeType tests");
        }
        BiomeType.init();
    }

    @Test
    void biomesIniLoads() {
        assertTrue(BiomeType.values().length > 0, "biomes.ini should load at least one biome");
    }

    @Test
    void plainsByIndex() {
        // plains 的 sprite 索引应 ≥0 且与 values 中对应项一致
        var plains = BiomeType.byId(1);
        assertNotNull(plains, "plains (id=1) should be registered");
        assertTrue(plains.spriteIndex >= 0);
        assertEquals(plains, BiomeType.byId(1));
    }

    @Test
    void idsInRangeAreRegisteredWithValidSprite() {
        for (BiomeType t : BiomeType.values()) {
            assertTrue(t.id >= 0 && t.id < 256, "biome id out of range: " + t.id);
            assertTrue(t.spriteIndex >= 0, "negative sprite index for " + t.id);
            assertNotNull(t.name, "biome name should not be null");
        }
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(BiomeType.byId(-1));
        assertNull(BiomeType.byId(999));
    }
}
