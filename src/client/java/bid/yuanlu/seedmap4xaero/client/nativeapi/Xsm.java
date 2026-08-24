package bid.yuanlu.seedmap4xaero.client.nativeapi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.SharedConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.cache.QueryPointCache;
import bid.yuanlu.seedmap4xaero.client.cache.StrongholdCache.StrongholdPos;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorProvider;
import bid.yuanlu.seedmap4xaero.client.render.NativeBiomeColor;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

public final class Xsm {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/Xsm");

    static {
        loadNativeLibrary();
    }

    /**
     * 按运行平台（os × arch × Android）从 JAR 内 {@code /native/<os>/<arch>/} 子目录
     * 解压并加载 {@code libxsmcore}。macOS 是 universal dylib，两架构共用一份。
     * Android (FCL/Pojav) 的 JVM 链接 bionic，native 产物必须单独用 NDK 编译。
     */
    private static void loadNativeLibrary() {
        String libName = System.mapLibraryName("xsmcore");
        String resourcePath = nativeResourcePath(libName);
        try {
            Path tmp = Files.createTempFile(libName, "");
            try (InputStream in = Xsm.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new RuntimeException(
                            "Native library not found in JAR: " + resourcePath
                                    + " (os=" + osName() + ", arch=" + archName()
                                    + ", android=" + isAndroid() + ")");
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp.toFile().deleteOnExit();
            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to load native library libxsmcore from " + resourcePath, e);
        }
    }

    private static String nativeResourcePath(String libName) {
        String os = osName();
        if ("macos".equals(os)) {
            // universal dylib 同时含 arm64 + x86_64 两个 slice
            return "/native/macos/universal/" + libName;
        }
        String arch = archName();
        if ("android".equals(os) && "aarch64".equals(arch)) {
            arch = "arm64";
        }
        return "/native/" + os + "/" + arch + "/" + libName;
    }

    private static String osName() {
        if (isAndroid()) {
            return "android";
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x86-64":
            case "x64":
                return "x86_64";
            case "aarch64":
            case "arm64":
            case "armv8":
            case "armv8l":
                return "aarch64";
            default:
                return arch;
        }
    }

    private static boolean isAndroid() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static long lastSeed = Long.MIN_VALUE;
    private static int lastDim = Integer.MIN_VALUE;
    private static String lastAppliedVersion = null;
    private static String lastRejectedVersion = null;

    /**
     * 版本选择器展示的 MC 版本（倒序，仅 26.1+；ViaVersion 跨版本场景）。
     * <p>
     * C 侧 {@code mcVersionMap} 支持更多旧版本（配置中已有的值仍可生效），
     * 但 UI 仅提供 26.1+ 选项；新 MC 版本发布时在此追加。
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(
            "26.2", "26.1");

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

    /**
     * 应用世界生成 MC 版本（已去重）。null 表示跟随当前客户端版本。
     *
     * @return false 表示版本字符串不被 C 侧支持（保持原版本不变）
     */
    public static boolean applyGameVersion(@Nullable String version) {
        final String target = version != null ? version : SharedConstants.getCurrentVersion().name();
        if (Objects.equals(target, lastAppliedVersion))
            return true;
        if (Objects.equals(target, lastRejectedVersion))
            return false;
        try (Arena arena = Arena.ofConfined()) {
            boolean success = XsmNative.setGameVersion(arena.allocateFrom(target));
            if (!success) {
                lastRejectedVersion = target;
                LOGGER.warn("Unsupported MC version: {}, keeping current version ({})", target, lastAppliedVersion);
                return false;
            }
        }
        lastAppliedVersion = target;
        // C 侧 setGameVersion 已重置 gen_setWorld，强制后续重新 setWorld 并清空全部缓存
        resetWorldState();
        CacheHelper.invalidateAll();
        return true;
    }

    /** 重置种子/维度去重哨兵，强制下一次 setWorld 真正下发到 C 侧。 */
    public static void resetWorldState() {
        lastSeed = Long.MIN_VALUE;
        lastDim = Integer.MIN_VALUE;
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
        void set(int rx, int rz, boolean found, int bx, int bz, int variant);
    }

    /**
     * 稀疏结构(regionSize=1, 逐区块低概率)批量查询: 只回传命中的 block 坐标。
     *
     * @return 续传点(线性序号); -1 表示扫描完成。返回 >= 0 时本次结果已满,
     *         需以相同矩形+排除矩形+该返回值再次调用以继续。
     */
    public static long querySparseStructures(
            int structureType,
            int rx0, int rz0, int rx1, int rz1,
            int ex0, int ez0, int ex1, int ez1,
            long start, int cap,
            SparseStructureSetter setter) {
        int cex0, cex1, cez0, cez1;
        if (ex0 >= ex1) {
            cex0 = 0;
            cex1 = 0;
        } else {
            cex0 = Math.max(rx0, ex0);
            cex1 = Math.min(rx1, ex1);
        }
        if (ez0 >= ez1) {
            cez0 = 0;
            cez1 = 0;
        } else {
            cez0 = Math.max(rz0, ez0);
            cez1 = Math.min(rz1, ez1);
        }
        if (cex0 >= cex1) {
            cex0 = 0;
            cex1 = 0;
        }
        if (cez0 >= cez1) {
            cez0 = 0;
            cez1 = 0;
        }
        if (cap <= 0 || rx1 <= rx0 || rz1 <= rz0)
            return -1;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bx = arena.allocate(4L * cap);
            MemorySegment bz = arena.allocate(4L * cap);
            MemorySegment vr = arena.allocate(4L * cap);
            MemorySegment next = arena.allocate(8);
            int n = XsmNative.querySparseStructures(
                    structureType,
                    rx0, rz0, rx1, rz1,
                    cex0, cez0, cex1, cez1,
                    start, cap, bx, bz, vr, next);
            long nextVal = next.get(ValueLayout.JAVA_LONG, 0);
            for (int i = 0; i < n; i++) {
                setter.set(bx.getAtIndex(ValueLayout.JAVA_INT, i),
                        bz.getAtIndex(ValueLayout.JAVA_INT, i),
                        vr.getAtIndex(ValueLayout.JAVA_INT, i));
            }
            return nextVal;
        }
    }

    public interface SparseStructureSetter {
        void set(int blockX, int blockZ, int variant);
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
            MemorySegment vr = arena.allocate(4L * n);

            XsmNative.queryRegionStructuresGrid(
                    structureType,
                    rx0, rz0, rx1, rz1,
                    ex0, ez0, ex1, ez1,
                    found, bx, bz, vr);

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
                            bz.getAtIndex(ValueLayout.JAVA_INT, idx),
                            vr.getAtIndex(ValueLayout.JAVA_INT, idx));
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

    /**
     * 查询要塞精确位置, 返回 index ∈ [from, to) 的部分 (按生成顺序)。
     *
     * @param from 起始 index (含)
     * @param to   结束 index (不含)
     * @return 实际返回的要塞位置数组
     */
    public static @NotNull StrongholdPos[] queryStrongholdsRange(int from, int to) {
        if (to <= from)
            return new StrongholdPos[0];
        try (Arena arena = Arena.ofConfined()) {
            int cap = to - from;
            MemorySegment x = arena.allocate(4L * cap);
            MemorySegment z = arena.allocate(4L * cap);
            int n = XsmNative.queryStrongholdsRange(from, to, x, z);
            if (n < 0 || n > cap)
                n = 0;
            StrongholdPos[] out = new StrongholdPos[n];
            for (int i = 0; i < n; i++) {
                out[i] = new StrongholdPos(from + i,
                        x.getAtIndex(ValueLayout.JAVA_INT, i),
                        z.getAtIndex(ValueLayout.JAVA_INT, i));
            }
            return out;
        }
    }

    /**
     * 单口箱子的战利品数据。
     *
     * @param chestX,chestZ 箱子方块坐标 (来自 cubiomes piece 数据)
     * @param lootSeed      该箱子的战利品种子
     * @param pieceName     cubiomes piece 名称 (如 "TeDP")
     * @param lootTable     战利品表名称 (如 "desert_pyramid")
     * @param items         战利品列表 (顺序即容器槽位顺序)
     */
    public record ChestLoot(int chestX, int chestZ, long lootSeed,
                            String pieceName, String lootTable, List<LootItem> items) {
    }

    /** 单件战利品。 */
    public record LootItem(int globalItemId, int count, List<int[]> enchantments) {
    }

    private static final int LOOT_BUF_CAP = 4096;
    private static final int LOOT_NAME_SLOT = 64;

    /**
     * 查询结构位置处的箱子战利品 (复刻 SeedMapper showLoot 管线)。
     * <p>
     * 布局 (C 端): 每口箱子 5 个头部 int32
     * {@code [chestX, chestZ, lootSeedLo, lootSeedHi, itemCount]},
     * 然后每个物品 {@code [globalItemId, count, enchantmentCount, (id, level)×ench]}。
     *
     * @param structureType 结构类型 (cubiomes StructureType 枚举值)
     * @param blockX,blockZ 结构生成点方块坐标
     * @return 箱子列表; 空列表 = 无战利品/不支持; {@code null} = native 错误
     */
    public static @Nullable List<ChestLoot> queryStructureLoot(int structureType, int blockX, int blockZ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(4L * LOOT_BUF_CAP);
            MemorySegment written = arena.allocate(4);
            MemorySegment chests = arena.allocate(4);
            MemorySegment pieceNames = arena.allocate(128L * LOOT_NAME_SLOT);
            MemorySegment lootTables = arena.allocate(128L * LOOT_NAME_SLOT);
            int rc = XsmNative.xsmQueryStructureLoot(
                    structureType, blockX, blockZ, LOOT_BUF_CAP, data, written, chests,
                    pieceNames, lootTables);
            if (rc != 0)
                return rc == -2 ? List.of() : null;
            int nChests = chests.get(ValueLayout.JAVA_INT, 0);
            int w = written.get(ValueLayout.JAVA_INT, 0);
            if (nChests <= 0)
                return List.of();

            List<ChestLoot> out = new ArrayList<>(nChests);
            int o = 0;
            for (int c = 0; c < nChests && o < w; c++) {
                int chestX = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                int chestZ = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                long seedLo = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT, o++));
                long seedHi = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT, o++));
                long lootSeed = (seedHi << 32) | seedLo;
                int itemCount = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                String pieceName = pieceNames.getString((long) c * LOOT_NAME_SLOT);
                String lootTable = lootTables.getString((long) c * LOOT_NAME_SLOT);
                List<LootItem> items = new ArrayList<>(itemCount);
                for (int i = 0; i < itemCount && o < w; i++) {
                    int gid = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                    int count = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                    int enchCount = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                    List<int[]> ench = new ArrayList<>(enchCount);
                    for (int e = 0; e < enchCount && o < w; e++) {
                        int id = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                        int level = data.getAtIndex(ValueLayout.JAVA_INT, o++);
                        ench.add(new int[] { id, level });
                    }
                    items.add(new LootItem(gid, count, ench));
                }
                out.add(new ChestLoot(chestX, chestZ, lootSeed, pieceName, lootTable, items));
            }
            return out;
        }
    }

    /** 查询 global item id 对应的物品名称 (如 {@code minecraft:apple})。 */
    public static @Nullable String itemName(int globalItemId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(64);
            if (XsmNative.xsmItemName(globalItemId, out, 64))
                return out.getString(0);
            return null;
        }
    }

    /** 查询附魔 id 对应的附魔名称 (如 {@code sharpness}, 无 {@code minecraft:} 前缀)。 */
    public static @Nullable String enchantmentName(int enchantmentId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(64);
            if (XsmNative.xsmEnchantmentName(enchantmentId, out, 64))
                return out.getString(0);
            return null;
        }
    }
}
