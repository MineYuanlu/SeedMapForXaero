package bid.yuanlu.seedmap4xaero.client.biome;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;

public final class BiomeType {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/BiomeType");
    private static final int MAX_ID = 256;
    private static final BiomeType[] BY_ID = new BiomeType[MAX_ID];
    private static BiomeType[] VALUES;

    public static final Identifier BIOMES_TEXTURE = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/icons/biomes.png");

    public static final int SPRITESHEET_WIDTH = 1520;
    public static final int SPRITESHEET_HEIGHT = 16;

    public final int id;
    public final String name;
    public final int spriteIndex;

    private BiomeType(int id, String name, int spriteIndex) {
        this.id = id;
        this.name = name;
        this.spriteIndex = spriteIndex;
    }

    public static void init() {
        List<BiomeType> list = new ArrayList<>();
        try (var in = BiomeType.class.getResourceAsStream(
                "/assets/seed-map-for-xaero/textures/icons/biomes.ini")) {
            if (in == null) {
                LOGGER.warn("biomes.ini not found, biome panel disabled");
                VALUES = new BiomeType[0];
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#"))
                        continue;
                    var parts = line.split("=");
                    if (parts.length != 2)
                        continue;
                    int biomeId = Integer.parseInt(parts[0].trim());
                    int spriteIdx = Integer.parseInt(parts[1].trim());
                    String name = Xsm.biome2str(biomeId);
                    if (name == null || name.isEmpty())
                        name = I18n.get("xsm.biome.unknown", biomeId);
                    var bt = new BiomeType(biomeId, name, spriteIdx);
                    if (biomeId >= 0 && biomeId < MAX_ID)
                        BY_ID[biomeId] = bt;
                    list.add(bt);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load biomes.ini", e);
        }

        VALUES = list.toArray(new BiomeType[0]);
        LOGGER.info("Loaded {} biome types from biomes.ini", VALUES.length);
    }

    public static BiomeType[] values() {
        return VALUES;
    }

    public static @Nullable BiomeType byId(int id) {
        if (id < 0 || id >= MAX_ID)
            return null;
        return BY_ID[id];
    }
}
