package bid.yuanlu.seedmap4xaero.client.nativeapi;

/**
 * native 传输层：手写 JNI stub，全部 MC 线（26.x unobf 与 ≤1.21.11 remap）共用。
 * <p>
 * 旧版曾用 jextract 生成 FFM 绑定（Java 25 only）；因 1.20.5–1.21.11 捆绑 Java 21
 * （FFM 在 21 上是 preview 不可用），且 JNI 在 Java 25 上同样可用，统一为 JNI 传输：
 * 方法名按 JNI 标准命名约定绑定 {@code libxsmcore} 内 {@code xsm/apis/jni.cpp}
 * 的导出符号，签名使用基本类型数组（数组均为 C 侧输出/输入缓冲）。
 * <p>
 * 方法清单与 C 侧 {@code xsm/apis/all.h} 一一对应；新增 C API 时同步补齐
 * Xsm.java、本类与 jni.cpp 三处。
 */
public final class XsmNative {

    private XsmNative() {
    }

    /** C: setGameVersion(const char*) */
    static native boolean setGameVersion(String version);

    /** C: setWorld(uint64_t, int) */
    static native void setWorld(long seed, int dim);

    /** C: setBiomeColorTableNative(void) */
    static native boolean setBiomeColorTableNative();

    /** C: setBiomeColorTable(uint32_t*, uint32_t) — colors 长度 = size*2 (BiomeID, RGBA) */
    static native boolean setBiomeColorTable(int[] colors, int size);

    /** C: setBiomeDisabled(const uint8_t*, uint32_t) */
    static native boolean setBiomeDisabled(byte[] bitset, int size);

    /** C: genCellImg(...) — data 输出 64*64*4 字节 RGBA */
    static native int genCellImg(int scale, int worldX, int worldZ, int absY, byte[] data,
            boolean light);

    /** C: queryPoint(...) — biomeName 输出 null 结尾字符串, height 输出 1 个值 */
    static native int queryPoint(int worldX, int worldZ, byte[] biomeName, int biomeNameLen,
            int[] height);

    /** C: queryExactChunkHeight(...) — heightsOut 输出 256 个 int */
    static native int queryExactChunkHeight(int chunkX, int chunkZ, int[] heightsOut);

    /** C: xsmGetStructureConfig(...) — out = [salt, regionSize, chunkRange, dim] */
    static native int xsmGetStructureConfig(int structureType, int[] out, float[] outRarity);

    /** C: xsmGetStructFEATURE_NUM(void) */
    static native int xsmGetStructFEATURE_NUM();

    /** C: queryRegionStructuresGrid(...) — found/bx/bz/vr 由调用方按网格预分配 */
    static native void queryRegionStructuresGrid(int structureType,
            int rx0, int rz0, int rx1, int rz1, int rx2, int rz2, int rx3, int rz3,
            byte[] outFound, int[] outBlockX, int[] outBlockZ, int[] outVariant);

    /** C: queryStrongholdsRange(...) */
    static native int queryStrongholdsRange(int from, int to, int[] outBlockX, int[] outBlockZ);

    /** C: querySpawn(...) — outXZ 长度 >= 2, 输出 [x, z] */
    static native int querySpawn(int[] outXZ);

    /** C: querySparseStructures(...) — outNext 长度 1, 输出续传点 (-1 = 完成) */
    static native int querySparseStructures(int structureType,
            int rx0, int rz0, int rx1, int rz1, int ex0, int ez0, int ex1, int ez1,
            long start, int cap, int[] outBlockX, int[] outBlockZ, int[] outVariant,
            long[] outNext);

    /** C: xsmBiome2str(...) — out 输出 null 结尾字符串 */
    static native boolean xsmBiome2str(int biomeId, byte[] out, int outLen);

    /** C: xsmQueryStructureLoot(...) — data 为 int32 扁平缓冲 (见 render.h 布局) */
    static native int xsmQueryStructureLoot(int structureType, int blockX, int blockZ, int outCap,
            int[] outData, int[] outWritten, int[] outChestCount, byte[] outPieceNames,
            byte[] outLootTables);

    /** C: xsmItemName(...) — out 输出 null 结尾字符串 */
    static native boolean xsmItemName(int globalItemId, byte[] out, int outLen);

    /** C: xsmEnchantmentName(...) — out 输出 null 结尾字符串 */
    static native boolean xsmEnchantmentName(int enchantmentId, byte[] out, int outLen);
}
