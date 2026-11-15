package bid.yuanlu.seedmap4xaero.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.BackgroundPregen;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.command.Sm4xCommand;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
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
        PerfConfig.init();
        Xsm.setGameVersion();
        Xsm.setBiomeColorTable(BiomeColorTable.providers().get(0));
        StructureType.init();
        BiomeType.init();
        StructureVisitTracker.register();
        BackgroundPregen.register();
        HighlightWorldRenderer.register();
        HighlightHudRenderer.register();
        Sm4xCommand.register();

        // issue #38: DISCONNECT 由 Connection.channelInactive 在 Netty 线程触发，而清理中的
        // CellCache.clear() 需关闭 GPU 纹理（渲染线程独占；MC 的 _deleteTexture 首行就是
        // RenderSystem.assertOnRenderThread，非渲染线程抛 IllegalStateException 且被 netty
        // 管线静默吞掉 → 清理中途夭折、缓存残留纹理泄漏）。不用 client.execute：断线流程中
        // Minecraft.disconnect → dropAllTasks 会清空 pendingRunnables，排队的清理任务可能被
        // 丢弃（gametest close 先 disconnect 后 dropAllTasks，必丢）。改置标志，由下方
        // END_CLIENT_TICK（每 tick 必跑、不受 dropAllTasks 影响）在渲染线程消费。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pendingDisconnectCleanup = true);

        // 周期刷盘 (rotate=false, 只写脏数据): 崩溃最多丢 FLUSH_INTERVAL_TICKS 的访问记录。
        // .old 恒为上次世界切换检查点 (轮替契约见 JsonConfigFile)。
        final long[] tickCounter = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingDisconnectCleanup) {
                pendingDisconnectCleanup = false;
                CellCache.clear();
                HighlightedStructures.clear();
                ServerConfig.deactivate();
                StructureDataConfig.deactivate();
            }
            if (++tickCounter[0] % FLUSH_INTERVAL_TICKS != 0)
                return;
            ServerConfig.flush();
            StructureDataConfig.flush();
            PerfConfig.flush();
        });
    }

    /** 断线清理待执行标志：DISCONNECT（Netty 线程）置位，END_CLIENT_TICK（渲染线程）消费。 */
    private static volatile boolean pendingDisconnectCleanup;

    /** 周期刷盘间隔: 1200 tick = 60s (20tps)。 */
    private static final long FLUSH_INTERVAL_TICKS = 1200;
}
