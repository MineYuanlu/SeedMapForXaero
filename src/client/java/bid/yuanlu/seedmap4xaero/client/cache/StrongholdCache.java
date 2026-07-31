package bid.yuanlu.seedmap4xaero.client.cache;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

/**
 * 要塞精确位置缓存。
 * <p>
 * 要塞位置由严格的 RNG 链顺序生成(无法跳算), 但总量固定(1.9+ 为 128),
 * 因此一次性全部精算, 按环分批后台推进: 每算完一环发布一次快照。
 * </p>
 */
public final class StrongholdCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StrongholdCache");

    /** 1.9+ 世界要塞总数上限 */
    public static final int MAX_COUNT = 128;
    /** 每环累计结束 index (环容量 3,6,10,15,21,28,36,9) */
    private static final int[] RING_ENDS = { 3, 9, 19, 34, 55, 83, 119, 128 };

    private static final Object LOCK = new Object();
    private static long generation = 0;
    private static boolean requested = false;
    private static volatile @Nullable StrongholdPos[] positions;

    /**
     * 渲染线程调用: 返回当前已算出的要塞位置快照 (null 槽位 = 未计算)。
     * 首次调用触发环形分批后台计算。
     */
    public static @Nullable StrongholdPos[] update() {
        StrongholdPos[] cur = positions;
        if (cur != null)
            return cur;
        synchronized (LOCK) {
            cur = positions;
            if (cur == null && !requested) {
                requested = true;
                scheduleRing(0, generation);
            }
            return cur;
        }
    }

    /** 世界/维度切换时调用, 使进行中的计算作废 */
    public static void clear() {
        synchronized (LOCK) {
            generation++;
            positions = null;
            requested = false;
        }
    }

    private static void scheduleRing(int from, long gen) {
        if (from >= MAX_COUNT)
            return;
        final int to = nextRingEnd(from);
        CacheHelper.CACHE_WORKER.execute(() -> {
            try {
                StrongholdPos[] batch = Xsm.queryStrongholdsRange(from, to);
                synchronized (LOCK) {
                    if (gen != generation)
                        return;
                    StrongholdPos[] cur = positions;
                    StrongholdPos[] next = cur == null ? new StrongholdPos[MAX_COUNT] : cur.clone();
                    for (StrongholdPos p : batch) {
                        if (p != null && p.index() >= 0 && p.index() < MAX_COUNT)
                            next[p.index()] = p;
                    }
                    positions = next;
                    if (batch.length > 0 && from + batch.length < MAX_COUNT)
                        scheduleRing(from + batch.length, gen);
                }
            } catch (Exception e) {
                LOGGER.error("queryStrongholdsRange [{}, {}) failed", from, to, e);
                synchronized (LOCK) {
                    if (gen == generation)
                        positions = new StrongholdPos[0];
                }
            }
        });
    }

    private static int nextRingEnd(int from) {
        for (int end : RING_ENDS)
            if (end > from)
                return end;
        return MAX_COUNT;
    }

    /**
     * 一个要塞的精确方块位置, 按生成顺序(index)排列
     *
     * @param index  生成顺序编号 (0 起, 最内环在前)
     * @param blockX 精确方块坐标 X
     * @param blockZ 精确方块坐标 Z
     */
    public static record StrongholdPos(int index, int blockX, int blockZ) {
    }

}
