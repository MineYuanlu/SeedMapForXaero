package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.AbstractCollection;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.cache.StrongholdCache.StrongholdPos;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class StructureCache {

    private static final EnumMap<@NotNull StructureType, TileCache> CACHES = new EnumMap<>(
            StructureType.class);
    private static final EnumMap<@NotNull StructureType, TileCache2> SPARSE_CACHES = new EnumMap<>(
            StructureType.class);
    /** CACHES的伴生对象, 用于缓存结构查询结果, 由 {@link #updateStructuresInArea} 更新 */
    public static final EnumMap<@NotNull StructureType, Collection<? extends StructurePos>> REGIONS = new EnumMap<>(
            StructureType.class);

    /**
     * 设置当前帧显示的范围, 触发结构计算更新
     * 可在稍后读取 {@link #REGIONS} 获取结果
     */
    public static void updateStructuresInArea(BitSetView enabledTypes,
            int blockX0, int blockZ0, int blockX1, int blockZ1) {
        REGIONS.clear();
        if (enabledTypes.isEmpty())
            return;
        if (blockX1 < blockX0) {
            int temp = blockX0;
            blockX0 = blockX1;
            blockX1 = temp;
        }
        if (blockZ1 < blockZ0) {
            int temp = blockZ0;
            blockZ0 = blockZ1;
            blockZ1 = temp;
        }
        for (int i = enabledTypes.nextSetBit(0); 0 <= i
                && i < StructureType.FEATURE_NUM; i = enabledTypes.nextSetBit(i + 1)) {
            final var type = StructureType.byId(i);
            if (type.config == null)
                continue;
            final int blockPerRegion = type.config.regionSize() * 16;
            final int regionX0 = Math.floorDiv(blockX0, blockPerRegion);
            final int regionX1 = Math.floorDiv(blockX1 - 1, blockPerRegion) + 1;
            final int regionZ0 = Math.floorDiv(blockZ0, blockPerRegion);
            final int regionZ1 = Math.floorDiv(blockZ1 - 1, blockPerRegion) + 1;
            final long regionCount = (long) (regionX1 - regionX0) * (long) (regionZ1 - regionZ0);
            if (type.prob > 0) {
                // 稀疏类型(regionSize=1, 逐区块低概率): 按期望命中量过滤, 超量整类跳过
                final long expected = (long) Math.ceil(regionCount * type.prob);
                if (expected > StructureType.MAX_REGION_HIDE)
                    continue;
                final var cache = SPARSE_CACHES.computeIfAbsent(type, t -> new TileCache2(t));
                final var tiles = cache.update(regionX0, regionX1, regionZ0, regionZ1);
                if (tiles != null)
                    REGIONS.put(type, tiles);
            } else {
                if (regionCount > type.maxRegionHide)
                    continue;
                final var cache = CACHES.computeIfAbsent(type, t -> new TileCache(t));
                final var tiles = cache.update(regionX0, regionX1, regionZ0, regionZ1);
                if (tiles != null)
                    REGIONS.put(type, tiles);
            }
        }
    }

    public static void clear() {
        CACHES.clear();
        SPARSE_CACHES.clear();
    }

    /**
     * 要塞精确位置 (按生成顺序, 按环渐进填充; null 槽位 = 未计算)。
     * 首次调用触发异步分批计算。
     */
    public static @Nullable StrongholdPos[] strongholds() {
        return StrongholdCache.update();
    }

    public interface StructurePos {
        boolean loaded();

        int blockX();

        int blockZ();
    }

    /**
     * 稀疏类型(regionSize=1, 逐区块低概率)缓存。
     * 只存命中位置(blockX<<32|blockZ), 通过 covered/pending 状态机只扫描新增区域,
     * 结果量超上限时截断续传; 快照 copy-on-write 发布, 渲染线程无锁读取。
     */
    private static final class TileCache2 extends AbstractCollection<StructurePos> {
        private final @NotNull StructureType type;
        /** 已发布快照: (blockX<<32)|(blockZ&0xffffffff); 渲染线程只读 */
        private volatile LongOpenHashSet snapshot = new LongOpenHashSet();
        /** 上一帧 region 矩形(检测变化, 移除框外命中) */
        private int rx0, rx1, rz0, rz1;
        /** 已完整扫描的矩形; 仅当一次扫描无截断时推进 */
        private int covX0, covX1, covZ0, covZ1;
        private boolean hasCovered;
        /** 截断后的续传状态 */
        private int pendX0, pendX1, pendZ0, pendZ1;
        private long pendStart;
        private boolean hasPending;
        /** 是否有 worker 任务在跑(防止重复入队) */
        private boolean jobRunning;

        TileCache2(@NotNull StructureType type) {
            this.type = type;
        }

        /**
         * 每帧调用(渲染线程)。
         * 
         * @param rx0 regionX左侧边界(含)
         * @param rx1 regionX右侧边界(不含)
         * @param rz0 regionZ左侧边界(含)
         * @param rz1 regionZ右侧边界(不含)
         * @return 结构位置集合(当前快照)
         */
        public Collection<? extends StructurePos> update(
                final int rx0, final int rx1,
                final int rz0, final int rz1) {
            synchronized (this) {
                if (this.rx0 != rx0 || this.rx1 != rx1 || this.rz0 != rz0 || this.rz1 != rz1) {
                    // 视口扩大(旧矩形⊆新矩形)时无需移除任何命中
                    final boolean grew = rx0 <= this.rx0 && this.rx1 <= rx1
                            && rz0 <= this.rz0 && this.rz1 <= rz1;
                    this.rx0 = rx0;
                    this.rx1 = rx1;
                    this.rz0 = rz0;
                    this.rz1 = rz1;
                    if (!grew)
                        retainIn(rx0, rx1, rz0, rz1);
                }
                if (hasPending) {
                    // 视口仍与待续传矩形相交 → 续传; 否则放弃(区域已远离)
                    // 续传矩形面积远超视口(如缩放进其内部) → 放弃, 不为看不见的区域排债
                    if (pendX0 < rx1 && pendX1 > rx0 && pendZ0 < rz1 && pendZ1 > rz0) {
                        final long pendArea = (long) (pendX1 - pendX0) * (pendZ1 - pendZ0);
                        final long viewArea = (long) (rx1 - rx0) * (rz1 - rz0);
                        if (pendArea / 4L <= viewArea)
                            enqueueScan(pendX0, pendX1, pendZ0, pendZ1, pendStart);
                        else
                            hasPending = false;
                    } else {
                        hasPending = false;
                    }
                    return this;
                }
                if (hasCovered && covX0 == rx0 && covX1 == rx1 && covZ0 == rz0 && covZ1 == rz1)
                    return this; // 已全部覆盖
                enqueueScan(rx0, rx1, rz0, rz1, -1);
                return this;
            }
        }

        /** 移除矩形外的命中 (需持有锁) */
        private void retainIn(int rx0, int rx1, int rz0, int rz1) {
            final LongOpenHashSet s = snapshot;
            if (s.isEmpty())
                return;
            final LongOpenHashSet ns = new LongOpenHashSet(s.size());
            var it = s.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int cx = (int) (key >> 32) >> 4;
                int cz = (int) (key & 0xFFFFFFFFL) >> 4;
                if (cx >= rx0 && cx < rx1 && cz >= rz0 && cz < rz1)
                    ns.add(key);
            }
            snapshot = ns;
        }

        /**
         * 入队扫描任务: 扫描 rect \ (covered∩rect), 从 start(线性序号) 续传。
         * 扫描完成(无截断) → covered=rect; 截断 → pending={rect, next}。
         * (需持有锁)
         */
        private void enqueueScan(final int rx0, final int rx1,
                final int rz0, final int rz1, final long start) {
            if (jobRunning)
                return;
            jobRunning = true;
            final int ex0, ex1, ez0, ez1;
            if (hasCovered) {
                ex0 = Math.max(covX0, rx0);
                ex1 = Math.min(covX1, rx1);
                ez0 = Math.max(covZ0, rz0);
                ez1 = Math.min(covZ1, rz1);
            } else {
                ex0 = 0;
                ex1 = 0;
                ez0 = 0;
                ez1 = 0;
            }
            final boolean noExcl = ex0 >= ex1 || ez0 >= ez1;
            final int fex0 = noExcl ? 0 : ex0, fex1 = noExcl ? 0 : ex1;
            final int fez0 = noExcl ? 0 : ez0, fez1 = noExcl ? 0 : ez1;
            final int cap = StructureType.MAX_REGION_HIDE;
            final int id = type.id;
            CacheHelper.CACHE_WORKER.execute(() -> {
            final LongArrayList hits = new LongArrayList();
                final long next = Xsm.querySparseStructures(
                        id,
                        rx0, rz0, rx1, rz1,
                        fex0, fez0, fex1, fez1,
                        start, cap,
                        (bx, bz) -> hits.add(((long) bx << 32) | (bz & 0xFFFFFFFFL)));
                synchronized (TileCache2.this) {
                    jobRunning = false;
                    if (next >= 0) {
                        hasPending = true;
                        pendX0 = rx0;
                        pendX1 = rx1;
                        pendZ0 = rz0;
                        pendZ1 = rz1;
                        pendStart = next;
                    } else {
                        hasPending = false;
                        hasCovered = true;
                        covX0 = rx0;
                        covX1 = rx1;
                        covZ0 = rz0;
                        covZ1 = rz1;
                    }
                    if (!hits.isEmpty()) {
                        LongOpenHashSet ns = new LongOpenHashSet(snapshot.size() + hits.size());
                        ns.addAll(snapshot);
                        for (long k : hits) {
                            int cx = (int) (k >> 32) >> 4;
                            int cz = (int) (k & 0xFFFFFFFFL) >> 4;
                            if (cx >= TileCache2.this.rx0 && cx < TileCache2.this.rx1
                                    && cz >= TileCache2.this.rz0 && cz < TileCache2.this.rz1)
                                ns.add(k);
                        }
                        snapshot = ns;
                    }
                }
            });
        }

        @Override
        public Iterator<StructurePos> iterator() {
            return new PosIterator(snapshot);
        }

        @Override
        public int size() {
            return snapshot.size();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof StructurePos pos))
                return false;
            if (!pos.loaded())
                return false;
            long key = (long) pos.blockX() << 32 | (pos.blockZ() & 0xFFFFFFFFL);
            return snapshot.contains(key);
        }

        private static final class PosIterator implements Iterator<StructurePos>, StructurePos {
            private final LongIterator longIt;
            private long pos;

            PosIterator(LongOpenHashSet set) {
                longIt = set.iterator();
            }

            @Override
            public boolean hasNext() {
                return longIt.hasNext();
            }

            @Override
            public StructurePos next() {
                pos = longIt.nextLong();
                return this;
            }

            @Override
            public boolean loaded() {
                return true; // 快照中只存命中
            }

            @Override
            public int blockX() {
                return (int) (pos >> 32);
            }

            @Override
            public int blockZ() {
                return (int) (pos & 0xFFFFFFFFL);
            }
        }
    }

    /**
     * 一个结构的全部缓存
     */
    private static final class TileCache {
        private final @NotNull StructureType type;
        private final Long2ObjectLinkedOpenHashMap<RegionPos> tiles = new Long2ObjectLinkedOpenHashMap<>();
        private int rx0, rx1, rz0, rz1;

        TileCache(@NotNull StructureType type) {
            this.type = type;
        }

        /**
         * 触发区域结构查询
         * 
         * @param rx0 regionX左侧边界(含)
         * @param rx1 regionX右侧边界(不含)
         * @param rz0 regionZ左侧边界(含)
         * @param rz1 regionZ右侧边界(不含)
         * @return 结构位置集合
         */
        public Collection<? extends StructurePos> update(
                final int rx0, final int rx1,
                final int rz0, final int rz1) {
            if (this.rx0 == rx0 && this.rx1 == rx1 && this.rz0 == rz0 && this.rz1 == rz1)
                return tiles.values();
            final int orx0 = this.rx0, orx1 = this.rx1, orz0 = this.rz0, orz1 = this.rz1;
            this.rx0 = rx0;
            this.rx1 = rx1;
            this.rz0 = rz0;
            this.rz1 = rz1;

            var it = tiles.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                long key = entry.getLongKey();
                int x = (int) (key >> 32);
                int z = (int) (key & 0xFFFFFFFFL);
                if (x < rx0 || x >= rx1 || z < rz0 || z >= rz1)
                    it.remove();
            }

            for (int x = rx0; x < rx1; x++) {
                final int fx = x;
                for (int z = rz0; z < rz1; z++) {
                    final int fz = z;
                    final long key = (long) fx << 32 | (fz & 0xFFFFFFFFL);
                    tiles.computeIfAbsent(key, k -> new RegionPos());
                }
            }

            int iex0 = Math.max(rx0, orx0), iex1 = Math.min(rx1, orx1);
            int iez0 = Math.max(rz0, orz0), iez1 = Math.min(rz1, orz1);
            if (iex0 >= iex1) {
                iex0 = 0;
                iex1 = 0;
            }
            if (iez0 >= iez1) {
                iez0 = 0;
                iez1 = 0;
            }
            final int fex0 = iex0, fex1 = iex1, fez0 = iez0, fez1 = iez1;

            // 进行diff更新, 只更新多出来的部分(在[r0,r1)且不在[fe0, fe1)的部分)，避免重复计算
            CacheHelper.CACHE_WORKER.execute(() -> Xsm.queryRegionStructuresGrid(
                    type.id,
                    rx0, rz0, rx1, rz1,
                    fex0, fez0, fex1, fez1,
                    (rx, rz, loaded, bx, bz) -> {
                        if (!loaded)
                            return;
                        long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                        final var rp = tiles.get(key);
                        if (rp == null)
                            return;
                        rp.loaded = true;
                        rp.blockX = bx;
                        rp.blockZ = bz;
                    }));

            return tiles.values();
        }

        /**
         * 一个Region的查询结果
         * <p>
         * Minecraft设定同一个Region(大小由固定值设置)至多只有一个结构
         */
        private static final class RegionPos implements StructurePos {
            public volatile int blockX;
            public volatile int blockZ;
            /** 代表blockX/Z是否有效; false代表加载中/此区域没有生成结构/程序出错 */
            public volatile boolean loaded;

            public RegionPos() {
                this.blockX = 0;
                this.blockZ = 0;
                this.loaded = false;
            }

            @Override
            public boolean loaded() {
                return loaded;
            }

            @Override
            public int blockX() {
                return blockX;
            }

            @Override
            public int blockZ() {
                return blockZ;
            }

        }

    }

}
