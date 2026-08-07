package bid.yuanlu.seedmap4xaero.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import bid.yuanlu.seedmap4xaero.client.render.HighlightHudRenderer;
import bid.yuanlu.seedmap4xaero.client.render.HighlightWorldRenderer;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** 种子地图客户端入口。 */
public class XaeroSeedMapClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/XaeroSeedMapClient");

    @Override
    public void onInitializeClient() {
        Xsm.setGameVersion();
        Xsm.setBiomeColorTable(BiomeColorTable.providers().get(0));
        StructureType.init();
        BiomeType.init();
        HighlightWorldRenderer.register();
        HighlightHudRenderer.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CellCache.clear();
            HighlightedStructures.clear();
            ServerConfig.deactivate();
        });
    }
}
