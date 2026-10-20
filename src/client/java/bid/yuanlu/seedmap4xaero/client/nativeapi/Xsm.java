package bid.yuanlu.seedmap4xaero.client.nativeapi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import bid.yuanlu.seedmap4xaero.client.cache.SpawnCache.SpawnPos;
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
        Path tmp;
        try {
            tmp = Files.createTempFile(libName, "");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file for native library " + libName, e);
        }
        try (InputStream in = Xsm.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException(
                        "Native library not found in JAR: " + resourcePath
                                + " (os=" + osName() + ", arch=" + archName()
                                + ", android=" + androidSignal() + ")");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to extract native library libxsmcore from " + resourcePath
                            + " to " + tmp.toAbsolutePath(), e);
        }
        tmp.toFile().deleteOnExit();
        try {
            System.load(tmp.toAbsolutePath().toString());
            LOGGER.info("[native-load] loaded {} ({} bytes, signal={})",
                    resourcePath, tmp.toFile().length(), androidSignal());
            int features = XsmNative.xsmGetStructFEATURE_NUM();
            LOGGER.info("[native-load] smoke call ok: xsmGetStructFEATURE_NUM()={}", features);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[native-load] FAILED to load {} (os={}, arch={}, android={})",
                    tmp.toAbsolutePath(), osName(), archName(), androidSignal(), e);
            throw e;
        } catch (Throwable e) {
            LOGGER.error("[native-load] loaded but smoke call FAILED (os={}, arch={}, android={})",
                    osName(), archName(), androidSignal(), e);
            throw e;
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
        return androidSignal() != null;
    }

    /** 返回 Android 检测命中的信号描述；非 Android 返回 null。 */
    private static @Nullable String androidSignal() {
        // FCL/Pojav 用桌面版 OpenJDK 起 MC 进程，android.os.Build 不在 classpath，
        // 必须叠加系统属性/环境变量/路径等信号判定。
        try {
            Class.forName("android.os.Build");
            return "class:android.os.Build";
        } catch (ClassNotFoundException | LinkageError ignored) {
            // fall through
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osVersion = System.getProperty("os.version", "").toLowerCase(Locale.ROOT);
        if (osName.contains("android")) {
            return "os.name=" + System.getProperty("os.name");
        }
        if (osVersion.startsWith("android")) {
            return "os.version=" + System.getProperty("os.version");
        }
        for (String key : new String[]{"POJAV_RENDERER", "POJAV_NATIVEDIR",
                "FCL_NATIVEDIR", "POJAVEXEC_EGL"}) {
            if (System.getenv().containsKey(key)) {
                return "env:" + key;
            }
        }
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (tmp.contains("/storage/emulated/") || tmp.contains("/data/user/0/")) {
            return "tmpdir=" + tmp;
        }
        return null;
    }

    /** 读取 C 侧输出的 null 结尾字符串缓冲。 */
    private static String cstr(byte[] buf) {
        return cstrAt(buf, 0, buf.length);
    }

    /** 读取 C 侧输出的 null 结尾字符串缓冲（指定切片）。 */
    private static String cstrAt(byte[] buf, int off, int maxLen) {
        int len = 0;
        while (len < maxLen && buf[off + len] != 0) {
            len++;
        }
        return new String(buf, off, len, StandardCharsets.UTF_8);
    }

    private static long lastSeed = Long.MIN_VALUE;
    private static int lastDim = Integer.MIN_VALUE;
    private static String lastAppliedVersion = null;
    private static String lastRejectedVersion = null;

    /**
     * 版本选择器展示的 MC 版本（倒序；ViaVersion 跨版本场景）。
     * <p>
     * legacy 分支（Java 21 / 混淆 MC 线）仅提供 1.21.11；
     * C 侧 {@code mcVersionMap} 支持更多旧版本（配置中已有的值仍可生效）。
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(
            "1.21.11");

    public static void setGameVersion() {
        final var version = SharedConstants.getCurrentVersion().name();
        boolean success = XsmNative.setGameVersion(version);
        if (!success) {
            throw new IllegalStateException("Unsupported game version: " + version);
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
        boolean success = XsmNative.setGameVersion(target);
        if (!success) {
            lastRejectedVersion = target;
            LOGGER.warn("Unsupported MC version: {}, keeping current version ({})", target, lastAppliedVersion);
            return false;
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
        boolean success = XsmNative.setBiomeColorTable(pairs, 256);
        if (!success) {
            LOGGER.error("Failed to set biome color table: {}", provider.name());
        }
    }

    private static byte @Nullable [] lastBiomeDisabled = null;

    public static void setBiomeDisabled(BitSetView disabled) {
        byte[] bits = disabled.toByteArray();
        if (Arrays.equals(lastBiomeDisabled, bits))
            return;
        lastBiomeDisabled = bits;
        boolean success = XsmNative.setBiomeDisabled(bits, bits.length);
        if (!success) {
            LOGGER.error("Failed to set biome disabled: {}", disabled);
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
        // C 侧输出 24-bit RGB，每像素 3 字节
        byte[] data = new byte[64 * 64 * 3];
        int result = XsmNative.genCellImg(scale, worldX, worldZ, absY, data, enableLighting);
        if (result != 0) {
            LOGGER.warn("genCellImg returned {} for scale={} worldX={} worldZ={} absY={}",
                    result, scale, worldX, worldZ, absY);
            return null;
        }
        int[] pixels = new int[64 * 64];
        for (int i = 0; i < pixels.length; i++) {
            int off = i * 3;
            int r = data[off] & 0xFF;
            int g = data[off + 1] & 0xFF;
            int b = data[off + 2] & 0xFF;
            pixels[i] = (0xFF << 24) | (b << 16) | (g << 8) | r;
        }
        return pixels;
    }

    public static @Nullable QueryPointCache queryPoint(int worldX, int worldZ) {
        byte[] biomeName = new byte[32];
        int[] height = new int[1];
        int ret = XsmNative.queryPoint(worldX, worldZ, biomeName, 32, height);
        if (ret == 0) {
            final var name = cstr(biomeName);
            if (name == null || name.isEmpty())
                return null;
            return new QueryPointCache(name, height[0]);
        } else {
            return null;
        }
    }

    public static int @Nullable [] queryExactChunkHeight(int chunkX, int chunkZ, int @NotNull [] heights) {
        if (heights == null || heights.length != 256)
            throw new IllegalArgumentException("heights must be an array of length 256");
        int ret = XsmNative.queryExactChunkHeight(chunkX, chunkZ, heights);
        if (ret != 0) {
            Xsm.LOGGER.warn("queryExactChunkHeight failed: {}", ret);
            return null;
        }
        return heights;
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

        int[] bx = new int[cap];
        int[] bz = new int[cap];
        int[] vr = new int[cap];
        long[] next = new long[1];
        int n = XsmNative.querySparseStructures(
                structureType,
                rx0, rz0, rx1, rz1,
                cex0, cez0, cex1, cez1,
                start, cap, bx, bz, vr, next);
        long nextVal = next[0];
        for (int i = 0; i < n; i++) {
            setter.set(bx[i], bz[i], vr[i]);
        }
        return nextVal;
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

        byte[] found = new byte[n];
        int[] bx = new int[n];
        int[] bz = new int[n];
        int[] vr = new int[n];

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
                        found[idx] != 0,
                        bx[idx],
                        bz[idx],
                        vr[idx]);
            }
        }
    }

    public static @Nullable StructureType.Config getStructureConfig(int type) {
        int[] out = new int[4];
        float[] rarity = new float[1];
        int ok = XsmNative.xsmGetStructureConfig(type, out, rarity);
        if (ok == 0)
            return null;
        return new StructureType.Config(
                out[0],
                out[1],
                out[2],
                out[3],
                rarity[0]);
    }

    public static int getStructFEATURE_NUM() {
        return XsmNative.xsmGetStructFEATURE_NUM();
    }

    public static @Nullable String biome2str(int biomeId) {
        byte[] out = new byte[64];
        if (XsmNative.xsmBiome2str(biomeId, out, 64))
            return cstr(out);
        return null;
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
        int cap = to - from;
        int[] x = new int[cap];
        int[] z = new int[cap];
        int n = XsmNative.queryStrongholdsRange(from, to, x, z);
        if (n < 0 || n > cap)
            n = 0;
        StrongholdPos[] out = new StrongholdPos[n];
        for (int i = 0; i < n; i++) {
            out[i] = new StrongholdPos(from + i, x[i], z[i]);
        }
        return out;
    }

    /**
     * 查询世界出生点 (cubiomes getSpawn, 精确)。仅主世界有效, 较慢, 应在后台线程调用。
     *
     * @return 出生点方块坐标; {@code null} = native 未就绪/非主世界/失败
     */
    public static @Nullable SpawnPos querySpawn() {
        int[] xz = new int[2];
        if (XsmNative.querySpawn(xz) == 0)
            return null;
        return new SpawnPos(xz[0], xz[1]);
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
        int[] data = new int[LOOT_BUF_CAP];
        int[] written = new int[1];
        int[] chests = new int[1];
        byte[] pieceNames = new byte[128 * LOOT_NAME_SLOT];
        byte[] lootTables = new byte[128 * LOOT_NAME_SLOT];
        int rc = XsmNative.xsmQueryStructureLoot(
                structureType, blockX, blockZ, LOOT_BUF_CAP, data, written, chests,
                pieceNames, lootTables);
        if (rc != 0)
            return rc == -2 ? List.of() : null;
        int nChests = chests[0];
        int w = written[0];
        if (nChests <= 0)
            return List.of();

        List<ChestLoot> out = new ArrayList<>(nChests);
        int o = 0;
        for (int c = 0; c < nChests && o < w; c++) {
            int chestX = data[o++];
            int chestZ = data[o++];
            long seedLo = Integer.toUnsignedLong(data[o++]);
            long seedHi = Integer.toUnsignedLong(data[o++]);
            long lootSeed = (seedHi << 32) | seedLo;
            int itemCount = data[o++];
            String pieceName = cstrAt(pieceNames, c * LOOT_NAME_SLOT, LOOT_NAME_SLOT);
            String lootTable = cstrAt(lootTables, c * LOOT_NAME_SLOT, LOOT_NAME_SLOT);
            List<LootItem> items = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount && o < w; i++) {
                int gid = data[o++];
                int count = data[o++];
                int enchCount = data[o++];
                List<int[]> ench = new ArrayList<>(enchCount);
                for (int e = 0; e < enchCount && o < w; e++) {
                    int id = data[o++];
                    int level = data[o++];
                    ench.add(new int[] { id, level });
                }
                items.add(new LootItem(gid, count, ench));
            }
            out.add(new ChestLoot(chestX, chestZ, lootSeed, pieceName, lootTable, items));
        }
        return out;
    }

    /** 查询 global item id 对应的物品名称 (如 {@code minecraft:apple})。 */
    public static @Nullable String itemName(int globalItemId) {
        byte[] out = new byte[64];
        if (XsmNative.xsmItemName(globalItemId, out, 64))
            return cstr(out);
        return null;
    }

    /** 查询附魔 id 对应的附魔名称 (如 {@code sharpness}, 无 {@code minecraft:} 前缀)。 */
    public static @Nullable String enchantmentName(int enchantmentId) {
        byte[] out = new byte[64];
        if (XsmNative.xsmEnchantmentName(enchantmentId, out, 64))
            return cstr(out);
        return null;
    }
}
