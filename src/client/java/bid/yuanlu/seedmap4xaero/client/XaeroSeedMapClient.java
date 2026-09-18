package bid.yuanlu.seedmap4xaero.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.command.Sm4xCommand;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.datapack.DatapackStructures;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import bid.yuanlu.seedmap4xaero.client.render.HighlightHudRenderer;
import bid.yuanlu.seedmap4xaero.client.render.HighlightWorldRenderer;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureVisitTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
        StructureVisitTracker.register();
        HighlightWorldRenderer.register();
        HighlightHudRenderer.register();
        Sm4xCommand.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CellCache.clear();
            HighlightedStructures.clear();
            ServerConfig.deactivate();
            StructureDataConfig.deactivate();
            DatapackStructures.clear();
        });

        // 周期刷盘 (rotate=false, 只写脏数据): 崩溃最多丢 FLUSH_INTERVAL_TICKS 的访问记录。
        // .old 恒为上次世界切换检查点 (轮替契约见 JsonConfigFile)。
        final long[] tickCounter = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter[0] % FLUSH_INTERVAL_TICKS != 0)
                return;
            ServerConfig.flush();
            StructureDataConfig.flush();
        });
    }

    /** 周期刷盘间隔: 1200 tick = 60s (20tps)。 */
    private static final long FLUSH_INTERVAL_TICKS = 1200;
}
