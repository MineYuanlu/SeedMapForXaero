package bid.yuanlu.seedmap4xaero.client.structure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 结构访问检测: 玩家进入结构阈值范围内时记录历史最小访问距离。
 * <p>
 * 仅在玩家方块坐标 (或种子) 变化时检测, 站立不动零开销; 在缓存 worker 线程上
 * 对玩家所在 region/chunk 的 3×3 邻域发起 native 查询 (阈值 10 < region/chunk
 * 最小边长 16, 3×3 已覆盖贴边场景), 要塞则用 {@link StructureCache} 快照。
 * 种子未知 (如多人未设种子) 时自然跳过。
 */
public final class StructureVisitTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureVisitTracker");

    /** 访问判定阈值 (Chebyshev 距离, 方块); 必须 < 16 才能被 3×3 邻域覆盖。 */
    public static final int THRESHOLD = 10;

    /** 上次检测的 (seed, dim, px, pz); 结构是种子的纯函数, 位置不变则结果不变。 */
    private static long lastSeed;
    private static int lastDim = Integer.MIN_VALUE;
    private static int lastPX = Integer.MIN_VALUE, lastPZ = Integer.MIN_VALUE;

    private StructureVisitTracker() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StructureVisitTracker::onEndTick);
    }

    private static void onEndTick(Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || player.isDeadOrDying())
            return;
        if (StructureDataConfig.getActiveData() == null)
            return;
        final var seed = ServerConfig.resolveSeed();
        if (seed == null)
            return;
        final int dim = ServerConfig.resolveDimId();
        if (dim == Integer.MIN_VALUE)
            return;

        final int px = player.getBlockX();
        final int pz = player.getBlockZ();
        final long s = seed;
        if (s == lastSeed && dim == lastDim && px == lastPX && pz == lastPZ)
            return;
        lastSeed = s;
        lastDim = dim;
        lastPX = px;
        lastPZ = pz;

        // 快照线程敏感状态, native 查询挪到 worker 线程
        CacheHelper.worker().execute(() -> detect(s, dim, px, pz));
    }

    private static void detect(long seed, int dim, int px, int pz) {
        try {
            final var wc = ServerConfig.getActiveWorldConfig();
            if (wc == null)
                return;
            final var enabled = wc.getStructureTypeSet();
            for (int i = enabled.nextSetBit(0); 0 <= i && i < StructureType.FEATURE_NUM; i = enabled
                    .nextSetBit(i + 1)) {
                final var type = StructureType.byId(i);
                if (type == StructureType.FEATURE)
                    continue;
                final var cfg = type.config();
                if (cfg == null)
                    continue;
                if (type == StructureType.STRONGHOLD) {
                    detectStrongholds(px, pz);
                    continue;
                }
                if (cfg.dim() != dim)
                    continue;
                if (type.prob > 0)
                    detectSparse(type, px, pz);
                else
                    detectRegion(type, px, pz);
            }
        } catch (Throwable t) {
            LOGGER.warn("structure visit detection failed", t);
        }
    }

    /** 普通类型: 查玩家所在 region 的 3×3 邻域。 */
    private static void detectRegion(StructureType type, int px, int pz) {
        final var cfg = type.config();
        if (cfg == null)
            return;
        final int blockPerRegion = cfg.regionSize() * 16;
        final int rx = Math.floorDiv(px, blockPerRegion);
        final int rz = Math.floorDiv(pz, blockPerRegion);
        Xsm.queryRegionStructuresGrid(type.id, rx - 1, rz - 1, rx + 2, rz + 2, 0, 0, 0, 0,
                (rrx, rrz, found, bx, bz, variant) -> {
                    if (!found)
                        return;
                    visit(type, bx, bz, px, pz);
                });
    }

    /** 稀疏类型: 查玩家所在 chunk 的 3×3 邻域。 */
    private static void detectSparse(StructureType type, int px, int pz) {
        final int cx = (px >> 4) - 1;
        final int cz = (pz >> 4) - 1;
        Xsm.querySparseStructures(type.id, cx, cz, cx + 3, cz + 3, 0, 0, 0, 0,
                -1, StructureType.MAX_SPARSE_HITS,
                (bx, bz, variant) -> visit(type, bx, bz, px, pz));
    }

    /** 要塞: 反查缓存快照 (仅已计算的槽位)。 */
    private static void detectStrongholds(int px, int pz) {
        final var strongholds = StructureCache.strongholds();
        if (strongholds == null)
            return;
        for (var p : strongholds) {
            if (p == null)
                continue;
            visit(StructureType.STRONGHOLD, p.blockX(), p.blockZ(), px, pz);
        }
    }

    /** Chebyshev 距离在阈值内 → 记录历史最小访问距离。 */
    private static void visit(StructureType type, int bx, int bz, int px, int pz) {
        int dist = Math.max(Math.abs(px - bx), Math.abs(pz - bz));
        if (dist > THRESHOLD)
            return;
        StructureDataConfig.markVisited(type, StructureDataConfig.keyOf(bx, bz), dist);
    }
}
