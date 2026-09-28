package bid.yuanlu.seedmap4xaero.client.cache;

import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 后台预生成（默认关，{@code pregenEnabled}）：游玩中（地图关闭）以最低调度
 * 优先级缓慢预生成玩家周边的<b>粗层瓦片</b>，下次打开地图即有背景可画。
 * <p>
 * 层次选择：{维度最大档, /4, /16} 三层（如主世界 256/64/16）。粗层单位面积
 * 生成成本随 scale 平方下降，半径 8192 时约 200+13+1 张；细层（4/1）面积成本
 * 平方上涨，不做预生成（用户真看到时经正常请求生成，粗层经多级 SuperScale
 * fallback 兜底）。
 * <p>
 * 节流：每 {@link #CHECK_INTERVAL_TICKS} tick 扫描一次（纯 containsKey 检查，
 * 微秒级）；实际生成受生成线程数上限 + MIN_PRIORITY + pending backpressure
 * 约束，对游戏帧率无感。重复扫描天然回填被 LRU 驱逐的瓦片。
 */
public final class BackgroundPregen {

    /** 扫描间隔（tick）。 */
    private static final int CHECK_INTERVAL_TICKS = 40;

    private static long nextCheckTick;

    private BackgroundPregen() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BackgroundPregen::onEndTick);
    }

    private static void onEndTick(Minecraft mc) {
        if (!PerfConfig.pregenEnabled())
            return;
        // 地图打开时由渲染路径自身的请求/prefetch 驱动，无需后台补充
        if (mc.screen instanceof xaero.map.gui.GuiMap)
            return;
        final LocalPlayer player = mc.player;
        if (player == null)
            return;
        final Long seed = ServerConfig.resolveSeed();
        if (seed == null)
            return;
        final int dim = ServerConfig.resolveDimId();
        if (dim == Integer.MIN_VALUE)
            return;

        final long tick = mc.level != null ? mc.level.getGameTime() : 0;
        if (tick < nextCheckTick)
            return;
        nextCheckTick = tick + CHECK_INTERVAL_TICKS;

        final int px = player.getBlockX();
        final int pz = player.getBlockZ();
        final int maxScale = dim == 0 ? 256 : 64;
        final int radius = PerfConfig.pregenRadiusBlocks();

        for (int scale = maxScale; scale >= 16; scale /= 4) {
            int tileBlocks = 64 * scale;
            int r = Math.floorDiv(radius, tileBlocks);
            int cx0 = Math.floorDiv(px, tileBlocks);
            int cz0 = Math.floorDiv(pz, tileBlocks);
            // 圆形范围（中心距 ≤ radius），比方形省 ~21%
            double rSq = (double) radius * radius;
            for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
                for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                    double dx = (cx + 0.5) * tileBlocks - px;
                    double dz = (cz + 0.5) * tileBlocks - pz;
                    if (dx * dx + dz * dz > rSq)
                        continue;
                    CellCache.prefetch(new CellCache.CellKey(scale, cx, cz),
                            CellCache.KIND_PREFETCH);
                }
            }
        }
    }
}
