package bid.yuanlu.seedmap4xaero.client.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link GenScheduler#isHigherPriority} 排序语义：
 * kind 升序（VISIBLE &lt; BACKGROUND &lt; PREFETCH）→ scale 粗优先 → 距离近优先 → FIFO。
 */
class GenSchedulerPriorityTest {

    private static final int VISIBLE = CellCache.KIND_VISIBLE;
    private static final int BACKGROUND = CellCache.KIND_BACKGROUND;
    private static final int PREFETCH = CellCache.KIND_PREFETCH;

    private static boolean first(int kA, int sA, double dA, long qA, int kB, int sB, double dB, long qB) {
        return GenScheduler.isHigherPriority(kA, sA, dA, qA, kB, sB, dB, qB);
    }

    @Test
    void visibleBeatsBackground() {
        // 可见请求恒先于背景（即使背景更粗）
        assertTrue(first(VISIBLE, 1, 1e12, 0, BACKGROUND, 256, 0, 1));
        assertFalse(first(BACKGROUND, 256, 0, 1, VISIBLE, 1, 1e12, 0));
    }

    @Test
    void backgroundBeatsPrefetch() {
        // 视口粗层背景先于外圈环/预生成（同 scale）
        assertTrue(first(BACKGROUND, 16, 100, 0, PREFETCH, 16, 0, 1));
        assertFalse(first(PREFETCH, 16, 0, 1, BACKGROUND, 16, 100, 0));
    }

    @Test
    void pregenBacklogMustNotStarveVisible() {
        // 关键回归: 预生成积压的粗瓦片（PREFETCH）不得越过可见细瓦片（VISIBLE）
        assertTrue(first(VISIBLE, 1, 1e12, 0, PREFETCH, 256, 0, 1));
        assertFalse(first(PREFETCH, 256, 0, 1, VISIBLE, 1, 1e12, 0));
    }

    @Test
    void coarseScaleFirstWithinSameKind() {
        // 同档（BACKGROUND）内 scale 粗者优先: 64 先于 16
        assertTrue(first(BACKGROUND, 64, 1e9, 5, BACKGROUND, 16, 0, 4));
        assertFalse(first(BACKGROUND, 16, 0, 4, BACKGROUND, 64, 1e9, 5));
    }

    @Test
    void distanceSecondWithinSameKindAndScale() {
        assertTrue(first(VISIBLE, 16, 100, 9, VISIBLE, 16, 200, 3));
        assertFalse(first(VISIBLE, 16, 200, 3, VISIBLE, 16, 100, 9));
    }

    @Test
    void fifoTiebreak() {
        assertTrue(first(BACKGROUND, 16, 100, 3, BACKGROUND, 16, 100, 7));
        assertFalse(first(BACKGROUND, 16, 100, 7, BACKGROUND, 16, 100, 3));
    }
}
