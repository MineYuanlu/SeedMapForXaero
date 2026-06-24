package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

public record QueryPointCache(@NotNull String biomeName, int height) {

    public static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    private static final int POINT_CACHE_MAX = 256;
    private static final LinkedHashMap<Long, QueryPointCache> POINT_CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, QueryPointCache> eldest) {
            return size() > POINT_CACHE_MAX;
        }
    };
    private static final int CHUNK_CACHE_MAX = 64;
    private static final int CHUNK_HEIGHT_LENGTH = 16 * 16;
    private static final LinkedHashMap<Long, int[]> CHUNK_HEIGHT_CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > CHUNK_CACHE_MAX;
        }
    };

    public static @Nullable String queryBiomeName(final int worldX, final int worldZ) {
        final var point = queryPoint(worldX, worldZ);
        return point == null ? null : point.biomeName();
    }

    public static int queryHeight(final int worldX, final int worldZ) {
        final int chunkX = worldX >> 4;
        final int chunkZ = worldZ >> 4;
        long keyChunk = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        final var chunkHeights = CHUNK_HEIGHT_CACHE.computeIfAbsent(keyChunk, k -> {
            int[] heights = new int[CHUNK_HEIGHT_LENGTH];
            Arrays.fill(heights, UNKNOWN_HEIGHT);
            CacheHelper.CACHE_WORKER.execute(() -> Xsm.queryExactChunkHeight(chunkX, chunkZ, heights));
            return heights;
        });
        int rx = worldX - (chunkX << 4), rz = worldZ - (chunkZ << 4);
        final var chunkHeight = chunkHeights[rx * 16 + rz];
        if (chunkHeight != UNKNOWN_HEIGHT)
            return chunkHeight;

        final var point = queryPoint(worldX, worldZ);
        return point == null ? UNKNOWN_HEIGHT : point.height();
    }

    public static void clear() {
        POINT_CACHE.clear();
        CHUNK_HEIGHT_CACHE.clear();
    }


    private static @Nullable QueryPointCache queryPoint(final int worldX, final int worldZ) {
        long key = ((long) worldX << 32) | (worldZ & 0xFFFFFFFFL);
        final var point = POINT_CACHE.computeIfAbsent(key, k -> Xsm.queryPoint(worldX, worldZ));
        return point;
    }

}