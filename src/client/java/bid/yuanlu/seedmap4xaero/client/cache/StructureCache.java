package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;

import org.jetbrains.annotations.NotNull;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

public class StructureCache {

    private static final EnumMap<@NotNull StructureType, TileCache> CACHES = new EnumMap<>(
            StructureType.class);
    /** CACHES的伴生对象, 用于缓存结构查询结果, 由 {@link #updateStructuresInArea} 更新 */
    public static final EnumMap<@NotNull StructureType, Collection<RegionPos>> REGIONS = new EnumMap<>(
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
            if (regionCount > type.maxRegionHide)
                continue;
            final var cache = CACHES.computeIfAbsent(type, t -> new TileCache(t));
            final var tiles = cache.update(regionX0, regionX1, regionZ0, regionZ1);
            if (tiles != null)
                REGIONS.put(type, tiles);
        }
    }

    public static void clear() {
        CACHES.clear();
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
        public Collection<RegionPos> update(
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
                    tiles.computeIfAbsent(key, k -> new RegionPos(type, fx, fz));
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
                        rp.loaded = true;
                        rp.blockX = bx;
                        rp.blockZ = bz;
                    }));

            return tiles.values();
        }

    }

    /**
     * 一个Region的查询结果
     * <p>
     * Minecraft设定同一个Region(大小由固定值设置)至多只有一个结构
     */
    public static final class RegionPos {
        public final int regionX;
        public final int regionZ;
        public volatile int blockX;
        public volatile int blockZ;
        /** 代表blockX/Z是否有效; false代表加载中/此区域没有生成结构/程序出错 */
        public volatile boolean loaded;

        public RegionPos(@NotNull StructureType type, int regionX, int regionZ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.blockX = 0;
            this.blockZ = 0;
            this.loaded = false;
        }
    }
}
