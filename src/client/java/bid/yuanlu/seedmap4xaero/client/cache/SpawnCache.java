package bid.yuanlu.seedmap4xaero.client.cache;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.cache.StructureCache.StructurePos;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

/**
 * 世界出生点缓存。
 * <p>
 * 出生点由种子唯一确定 (cubiomes getSpawn, 单点), 每个世界只需计算一次;
 * 首次渲染触发后台计算, 结果以 volatile 快照发布; 世界/维度切换时 {@link #clear()} 作废。
 * </p>
 */
public final class SpawnCache {

    private static final Object LOCK = new Object();
    private static long generation = 0;
    private static boolean requested = false;
    private static volatile @Nullable SpawnPos spawn;

    /**
     * 渲染线程调用: 返回已算出的出生点 (null = 未算出/不可用)。
     * 首次调用触发后台计算。
     */
    public static @Nullable SpawnPos update() {
        SpawnPos cur = spawn;
        if (cur != null)
            return cur;
        synchronized (LOCK) {
            cur = spawn;
            if (cur == null && !requested) {
                requested = true;
                schedule(generation);
            }
            return cur;
        }
    }

    /** 世界/维度切换时调用, 使进行中的计算作废。 */
    public static void clear() {
        synchronized (LOCK) {
            generation++;
            spawn = null;
            requested = false;
        }
    }

    private static void schedule(long gen) {
        CacheHelper.worker().execute(() -> {
            SpawnPos p = Xsm.querySpawn();
            if (p == null)
                return;
            synchronized (LOCK) {
                if (gen == generation)
                    spawn = p;
            }
        });
    }

    /** 世界出生点方块坐标 (实现 StructurePos, 复用结构图标渲染管线)。 */
    public static record SpawnPos(int blockX, int blockZ) implements StructurePos {

        @Override
        public boolean loaded() {
            return true;
        }
    }

}
