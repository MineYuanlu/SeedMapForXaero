package bid.yuanlu.seedmap4xaero.client.nativeapi;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import net.minecraft.SharedConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.QueryPointCache;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorProvider;
import bid.yuanlu.seedmap4xaero.client.render.NativeBiomeColor;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

public final class Xsm {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/Xsm");

    static {
        try {
            var libName = System.mapLibraryName("xsmcore");
            var tmp = Files.createTempFile(libName, "");
            try (var in = Xsm.class.getResourceAsStream("/" + libName)) {
                if (in == null) {
                    throw new RuntimeException(
                            "Native library not found in JAR: /" + libName);
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp.toFile().deleteOnExit();
            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load native library libxsmcore", e);
        }
    }

    private static long lastSeed = Long.MIN_VALUE;
    private static int lastDim = Integer.MIN_VALUE;

    public static void setGameVersion() {
        final var version = SharedConstants.getCurrentVersion().name();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionSegment = arena.allocateFrom(version);

            boolean success = XsmNative.setGameVersion(versionSegment);
            if (!success) {
                throw new IllegalStateException("Unsupported game version: " + version);
            }
        }
    }

    /** 设置世界种子/维度（已缓存去重）。 */
    public static void setWorld(long seed, int dim) {
        if (seed == lastSeed && dim == lastDim)
            return;
        lastSeed = seed;
        lastDim = dim;
        XsmNative.setWorld(seed, dim);
    }

    /**
     * 将生物群系颜色表设为内置颜色表
     */
    public static void setBiomeColorTable() {
        XsmNative.setBiomeColorTableNative();
    }

    public static void setBiomeColorTable(BiomeColorProvider provider) {
        if (provider instanceof NativeBiomeColor) {
            XsmNative.setBiomeColorTableNative();
            return;
        }
        int[] pairs = new int[256 * 2];
        for (int id = 0; id < 256; id++) {
            pairs[id * 2] = id;
            pairs[id * 2 + 1] = provider.getColor(id);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(256L * 2 * 4);
            seg.copyFrom(MemorySegment.ofArray(pairs));
            boolean success = XsmNative.setBiomeColorTable(seg, 256);
            if (!success) {
                LOGGER.error("Failed to set biome color table: {}", provider.name());
            }
        }
    }

    private static byte @Nullable [] lastBiomeDisabled = null;

    public static void setBiomeDisabled(BitSetView disabled) {
        byte[] bits = disabled.toByteArray();
        if (Arrays.equals(lastBiomeDisabled, bits))
            return;
        lastBiomeDisabled = bits;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bits.length);
            seg.copyFrom(MemorySegment.ofArray(bits));
            boolean success = XsmNative.setBiomeDisabled(seg, bits.length);
            if (!success) {
                LOGGER.error("Failed to set biome disabled: {}", disabled);
            }
        }
    }

    /**
     * 生成瓦片图像。
     *
     * <p>
     * 调用 C 侧 genCellImg，输出固定 64×64 像素的 RGBA 数据，
     * 返回 ABGR 格式 {@code int[]}（兼容 {@code NativeImage.setPixelABGR}）。
     * </p>
     *
     * @param scale          缩放因子（1, 4, 16, 64, 256；256 仅主世界）
     * @param worldX         世界方块 X 坐标（原点）
     * @param worldZ         世界方块 Z 坐标（原点）
     * @param absY           绝对高度（仅用于群系生成）
     * @param enableLighting 是否启用地形光照
     * @return 64×64 像素数组，每像素 ABGR {@code int}
     */
    public static int[] genCellImg(int scale, int worldX, int worldZ, int absY, boolean enableLighting) {
        try (Arena arena = Arena.ofConfined()) {
            // C 侧输出 24-bit RGB，每像素 3 字节
            MemorySegment data = arena.allocate(64L * 64 * 3);
            int result = XsmNative.genCellImg(scale, worldX, worldZ, absY, data, enableLighting);
            if (result != 0) {
                LOGGER.warn("genCellImg returned {} for scale={} worldX={} worldZ={} absY={}",
                        result, scale, worldX, worldZ, absY);
                return null;
            }
            int[] pixels = new int[64 * 64];
            for (int i = 0; i < pixels.length; i++) {
                long off = (long) i * 3;
                int r = data.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
                int g = data.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
                int b = data.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
                pixels[i] = (0xFF << 24) | (b << 16) | (g << 8) | r;
            }
            return pixels;
        }
    }

    public static @Nullable QueryPointCache queryPoint(int worldX, int worldZ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment biomeName = arena.allocate(32);
            MemorySegment height = arena.allocate(ValueLayout.JAVA_INT);
            int ret = XsmNative.queryPoint(worldX, worldZ, biomeName, 32, height);
            if (ret == 0) {
                final var name = biomeName.getString(0);
                if (name == null)
                    return null;
                return new QueryPointCache(name,
                        height.get(ValueLayout.JAVA_INT, 0));
            } else {
                return null;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    public static int @Nullable [] queryExactChunkHeight(int chunkX, int chunkZ, int @NotNull [] heights) {
        if (heights == null || heights.length != 256)
            throw new IllegalArgumentException("heights must be an array of length 256");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(256 * 4L);
            int ret = XsmNative.queryExactChunkHeight(chunkX, chunkZ, seg);
            if (ret == 0) {
                for (int i = 0; i < 256; i++) {
                    heights[i] = seg.getAtIndex(ValueLayout.JAVA_INT, i);
                }
                return heights;
            }
        } catch (Throwable e) {
            Xsm.LOGGER.warn("queryExactChunkHeight failed", e);
        }
        return null;
    }

    public interface RegionStructureSetter {
        void set(int rx, int rz, boolean found, int bx, int bz);
    }

    public static void queryRegionStructuresGrid(
            int structureType,
            int rx0, int rz0, int rx1, int rz1,
            int rx2, int rz2, int rx3, int rz3,
            RegionStructureSetter setter) {
        int ex0 = Math.max(rx0, Math.min(rx2, rx3));
        int ex1 = Math.min(rx1, Math.max(rx2, rx3));
        int ez0 = Math.max(rz0, Math.min(rz2, rz3));
        int ez1 = Math.min(rz1, Math.max(rz2, rz3));
        if (ex0 >= ex1) {
            ex0 = 0;
            ex1 = 0;
        }
        if (ez0 >= ez1) {
            ez0 = 0;
            ez1 = 0;
        }
        long total = (long) (rx1 - rx0) * (rz1 - rz0);
        long excl = (long) (ex1 - ex0) * (ez1 - ez0);
        int n = (int) (total - excl);
        if (n <= 0)
            return;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment found = arena.allocate(n);
            MemorySegment bx = arena.allocate(4L * n);
            MemorySegment bz = arena.allocate(4L * n);

            XsmNative.queryRegionStructuresGrid(
                    structureType,
                    rx0, rz0, rx1, rz1,
                    ex0, ez0, ex1, ez1,
                    found, bx, bz);

            int index = 0;
            for (int x = rx0; x < rx1; x++) {
                boolean inX = ex0 <= x && x < ex1;
                for (int z = rz0; z < rz1; z++) {
                    if (inX && ez0 <= z && z < ez1)
                        continue;
                    int idx = index++;
                    setter.set(x, z,
                            found.get(ValueLayout.JAVA_BYTE, idx) != 0,
                            bx.getAtIndex(ValueLayout.JAVA_INT, idx),
                            bz.getAtIndex(ValueLayout.JAVA_INT, idx));
                }
            }
        }
    }

    public static @Nullable StructureType.Config getStructureConfig(int type) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment salt = arena.allocate(4);
            MemorySegment regionSize = arena.allocate(4);
            MemorySegment chunkRange = arena.allocate(4);
            MemorySegment dim = arena.allocate(4);
            MemorySegment rarity = arena.allocate(4);
            int ok = XsmNative.xsmGetStructureConfig(
                    type, salt, regionSize, chunkRange, dim, rarity);
            if (ok == 0)
                return null;
            return new StructureType.Config(
                    salt.get(ValueLayout.JAVA_INT, 0),
                    regionSize.get(ValueLayout.JAVA_INT, 0),
                    chunkRange.get(ValueLayout.JAVA_INT, 0),
                    dim.get(ValueLayout.JAVA_INT, 0),
                    rarity.get(ValueLayout.JAVA_FLOAT, 0));
        }
    }

    public static int getStructFEATURE_NUM() {
        return XsmNative.xsmGetStructFEATURE_NUM();
    }

    public static @Nullable String biome2str(int biomeId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(64);
            if (XsmNative.xsmBiome2str(biomeId, out, 64))
                return out.getString(0);
            return null;
        }
    }
}
