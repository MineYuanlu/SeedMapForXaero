package bid.yuanlu.seedmap4xaero.client.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StructureTypeTest {

    @BeforeAll
    static void init() {
        StructureType.init();
    }

    @Test
    void featureNumMatchesEnumIds() {
        int max = -1;
        for (StructureType t : StructureType.values())
            max = Math.max(max, t.id);
        assertEquals(max + 1, StructureType.FEATURE_NUM);
    }

    @Test
    void everyIdResolvable() {
        for (int id = 0; id < StructureType.FEATURE_NUM; id++)
            assertNotNull(StructureType.byId(id));
    }

    @Test
    void byIdOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> StructureType.byId(-1));
        assertThrows(IllegalArgumentException.class, () -> StructureType.byId(StructureType.FEATURE_NUM));
    }

    @Test
    void jungleTempleAliasSharesId() {
        assertEquals(StructureType.JUNGLE_TEMPLE.id, StructureType.JUNGLE_PYRAMID.id);
        assertEquals(StructureType.JUNGLE_PYRAMID, StructureType.byId(2), "later alias wins BY_ID");
    }

    @Test
    void defaultEnabledSet() {
        var de = StructureType.defaultEnabled();
        assertTrue(de.get(StructureType.VILLAGE.id), "village default enabled");
        assertTrue(de.get(StructureType.STRONGHOLD.id), "stronghold default enabled");
        assertFalse(de.get(StructureType.FEATURE.id), "feature default disabled");
        assertFalse(de.get(StructureType.TREASURE.id), "treasure default disabled");
    }

    @Test
    void everyTypeHasDefaultIconAfterInit() {
        for (StructureType t : StructureType.values()) {
            // alias 共享 byId 解析到的数组
            assertTrue(StructureType.byId(t.id).getSpriteIndex(0) >= 0,
                    "variant 0 icon missing for " + t.key);
        }
    }

    @Test
    void spriteSheetWidthsConsistent() {
        assertEquals(StructureType.SHEET_SIZE * 20, StructureType.SPRITESHEET_WIDTH);
        assertEquals(StructureType.SHEET_SIZE * 16, StructureType.PLAIN_SPRITESHEET_WIDTH);
        assertTrue(StructureType.SHEET_SIZE >= 20, "expected at least 20 sprites");
    }

    @Test
    void variantTranslationKeys() {
        assertNull(StructureType.END_CITY.variantTranslationKey(0));
        assertEquals("xsm.structure.end_city.variant.ship",
                StructureType.END_CITY.variantTranslationKey(1));

        assertEquals("xsm.structure.village.variant.zombie",
                StructureType.VILLAGE.variantTranslationKey(8));
        assertEquals("xsm.structure.village.variant.desert",
                StructureType.VILLAGE.variantTranslationKey(1));
        assertEquals("xsm.structure.village.variant.savanna",
                StructureType.VILLAGE.variantTranslationKey(2));
        assertEquals("xsm.structure.village.variant.taiga",
                StructureType.VILLAGE.variantTranslationKey(3));
        assertEquals("xsm.structure.village.variant.snowy",
                StructureType.VILLAGE.variantTranslationKey(4));
        assertNull(StructureType.VILLAGE.variantTranslationKey(5));

        assertEquals("xsm.structure.bastion.variant.hoglin_stable",
                StructureType.BASTION.variantTranslationKey(1));
        assertEquals("xsm.structure.bastion.variant.treasure",
                StructureType.BASTION.variantTranslationKey(2));
        assertEquals("xsm.structure.bastion.variant.bridge",
                StructureType.BASTION.variantTranslationKey(3));
        assertNull(StructureType.BASTION.variantTranslationKey(0));

        assertEquals("xsm.structure.igloo.variant.basement",
                StructureType.IGLOO.variantTranslationKey(1));
        assertEquals("xsm.structure.shipwreck.variant.beached",
                StructureType.SHIPWRECK.variantTranslationKey(1));

        assertEquals("xsm.structure.ruined_portal.variant.giant",
                StructureType.RUINED_PORTAL.variantTranslationKey(1));
        assertEquals("xsm.structure.ruined_portal.variant.underground",
                StructureType.RUINED_PORTAL.variantTranslationKey(2));
        assertEquals("xsm.structure.ruined_portal.variant.airpocket",
                StructureType.RUINED_PORTAL.variantTranslationKey(4));

        assertEquals("xsm.structure.geode.variant.cracked",
                StructureType.GEODE.variantTranslationKey(1));
        assertEquals("xsm.structure.trial_chambers.variant.end",
                StructureType.TRIAL_CHAMBERS.variantTranslationKey(1));
        assertEquals("xsm.structure.trial_chambers.variant.corridor",
                StructureType.TRIAL_CHAMBERS.variantTranslationKey(2));
    }

    @Test
    void sparseTypesHaveProbNormalTypesDoNot() {
        assertTrue(StructureType.TREASURE.prob > 0);
        assertTrue(StructureType.MINESHAFT.prob > 0);
        assertTrue(StructureType.GEODE.prob > 0);
        assertTrue(StructureType.END_GATEWAY.prob > 0);
        assertTrue(StructureType.END_ISLAND.prob > 0);
        assertEquals(-1f, StructureType.VILLAGE.prob);
        assertEquals(-1f, StructureType.STRONGHOLD.prob);
    }

    @Test
    void strongholdHasNoConfig() {
        assertNull(StructureType.STRONGHOLD.config(), "stronghold never has a config");
    }

    @Test
    void translationKey() {
        assertEquals("xsm.structure.desert_pyramid", StructureType.DESERT_PYRAMID.translationKey());
    }
}
