package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import bid.yuanlu.seedmap4xaero.client.cache.CellCache.CellData;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache.CellKey;

/**
 * 瓦片生成 + 后台任务的统一调度器。
 * <p>
 * 生成线程通过 {@link #take(int)} 取工作项：
 * <ol>
 * <li>即时 job（结构查询等，{@link #submitJob} 提交，FIFO）——便宜且交互相关，恒优先</li>
 * <li>瓦片：按优先级扫描 {@code pendingTiles}——<b>kind 升序</b>（见下）→
 * 粗层优先（背景先行，super 层是细层的 fallback 源）→ 离相机近优先 → FIFO。
 * 相机坐标每帧经 {@link #updateCamera} 更新，优先级随之实时重排
 * （每件取活 O(n) 扫描，n ≤ backpressure 上限，可忽略）</li>
 * </ol>
 *
 * <p>
 * 瓦片三档优先级（{@link CellCache#KIND_VISIBLE} / {@link CellCache#KIND_BACKGROUND} /
 * {@link CellCache#KIND_PREFETCH}）：
 * <ul>
 * <li>VISIBLE：可见 cell 的正常请求（getOrRequest），恒最先</li>
 * <li>BACKGROUND：当前视口的粗层背景（prefetch 铺到最大 scale）——排在可见之后、
 * 其他 prefetch 之前；冷缓存打开地图先出全屏粗背景，细瓦片再由近及远填充</li>
 * <li>PREFETCH：视野外圈环 + 后台预生成——只在无更高档需求时消费，
 * 且不会在积压时饿死可见请求</li>
 * </ul>
 * 同档内 scale 粗者优先：zoom 过渡期多档可见请求并存时先铺粗层。
 * <p>
 * 阻塞语义：{@code take} 无工作时 {@code await()}；{@link #bumpGeneration()} 唤醒全部
 * 线程并使旧代 {@code take} 返回 null（线程池重建时旧线程优雅退出）。
 */
final class GenScheduler {

    /** 待生成瓦片（key → data）；put/remove/take 均在锁内或渲染线程。 */
    private final ConcurrentHashMap<CellKey, CellData> pendingTiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> jobs = new ConcurrentLinkedQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasWork = lock.newCondition();

    private volatile boolean running = true;
    /** 线程池代数；重建时 +1，旧代线程退出。 */
    private volatile int generation;
    private volatile double camX;
    private volatile double camZ;
    private long seq;

    /** 入队一个待生成瓦片（渲染线程调用）。 */
    void enqueueTile(CellData d) {
        lock.lock();
        try {
            d.seq = ++seq;
            pendingTiles.put(d.key, d);
            hasWork.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 提交即时任务（任意线程）。 */
    void submitJob(Runnable r) {
        lock.lock();
        try {
            jobs.add(r);
            hasWork.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 渲染线程每帧更新相机世界坐标（影响瓦片优先级）。 */
    void updateCamera(double x, double z) {
        this.camX = x;
        this.camZ = z;
    }

    int pendingCount() {
        return pendingTiles.size();
    }

    /** 当前池代数（线程启动时记录）。 */
    int currentGeneration() {
        return generation;
    }

    /**
     * 取下一个工作项：{@link Runnable}（即时 job）或 {@link CellData}（瓦片，
     * 已从 pending 移除）。无工作时阻塞；池代数变化后返回 {@code null}。
     */
    Object take(int myGeneration) throws InterruptedException {
        lock.lock();
        try {
            while (running && myGeneration == generation) {
                Runnable j = jobs.poll();
                if (j != null)
                    return j;
                CellData best = null;
                for (var e : pendingTiles.values()) {
                    if (e.cancelled) {
                        pendingTiles.remove(e.key, e);
                        continue;
                    }
                    if (best == null || isHigherPriority(e, best))
                        best = e;
                }
                if (best != null) {
                    pendingTiles.remove(best.key, best);
                    return best;
                }
                hasWork.await();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 优先级比较（true = a 先于 b）：
     * kind 升序 → scale 粗（数值大）优先 → 距离近优先 → FIFO。
     * <p>
     * 纯函数（基础类型参数），供单测直接验证排序语义。
     */
    static boolean isHigherPriority(int kindA, int scaleA, double distA, long seqA,
            int kindB, int scaleB, double distB, long seqB) {
        if (kindA != kindB)
            return kindA < kindB;
        if (scaleA != scaleB)
            return scaleA > scaleB;
        if (distA != distB)
            return distA < distB;
        return seqA < seqB;
    }

    private boolean isHigherPriority(CellData a, CellData b) {
        return isHigherPriority(a.priorityKind, a.key.scale(), distSq(a.key), a.seq,
                b.priorityKind, b.key.scale(), distSq(b.key), b.seq);
    }

    /** 线程池重建：代数 +1 并唤醒全部等待线程（旧代 take 返回 null）。 */
    void bumpGeneration() {
        lock.lock();
        try {
            generation++;
            hasWork.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 关停：take 恒返 null。 */
    void shutdown() {
        lock.lock();
        try {
            running = false;
            hasWork.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 清空待生成瓦片（世界/维度切换；CellData 已由 CellCache.clear 标记 cancelled）。 */
    void clearTiles() {
        lock.lock();
        try {
            pendingTiles.clear();
        } finally {
            lock.unlock();
        }
    }

    private double distSq(CellKey key) {
        double dx = key.worldX() + key.blockSize() / 2.0 - camX;
        double dz = key.worldZ() + key.blockSize() / 2.0 - camZ;
        return dx * dx + dz * dz;
    }
}
