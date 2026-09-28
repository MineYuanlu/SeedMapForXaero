package bid.yuanlu.seedmap4xaero.client.configs.perf;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;
import com.google.gson.JsonObject;

/**
 * perf 配置文档（{@code perf_config.json}）的数据体：性能/资源占用相关全局配置。
 * <p>
 * <b>全局语义</b>：这些是 JVM 级设置（线程数、缓存容量等），不随服务器/世界切换，
 * 因此只有一个文件，不按 mainId 隔离。
 * <p>
 * 只负责数据与自身序列化；磁盘布局（原子写、回退链）在
 * {@link bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile}。
 * 无 legacy 前身（此配置文件自 v2 起新增，不存在旧二进制）。
 *
 * <pre>
 * generationThreads   : int, 0=自动 min(4, cores/4)，手动 1..availableProcessors
 * cacheCapacityMB     : int, CellCache 全局 LRU 预算（1 条 ≈ 16KB，1MB = 64 条）
 * prefetchEnabled     : boolean, 视野外圈预取
 * pregenEnabled       : boolean, 游玩中主动预生成（默认关）
 * pregenRadiusBlocks  : int, 预生成世界半径（方块）
 * diskCacheEnabled    : boolean, 磁盘缓存开关（默认关，实现见 doc/perf.md）
 * diskCacheMaxMB      : int, 磁盘缓存体积上限
 * debugOverlay        : boolean, 性能统计 HUD
 * </pre>
 */
public class PerfConfigData {

    /**
     * 最小预算：一屏工作集 ≈ 2170 条（32MB），再小会抖动（生成→驱逐→重生成）。
     * 取 64MB（4096 条）对齐滑条 32 步进网格并留余量。
     */
    public static final int CACHE_MB_MIN = 64;
    public static final int CACHE_MB_MAX = 512;
    public static final int PREGEN_RADIUS_MIN = 1024;
    public static final int PREGEN_RADIUS_MAX = 65536;
    public static final int DISK_MB_MIN = 32;
    public static final int DISK_MB_MAX = 4096;

    int generationThreads = 0;
    int cacheCapacityMB = 128;
    boolean prefetchEnabled = true;
    boolean pregenEnabled = false;
    int pregenRadiusBlocks = 8192;
    boolean diskCacheEnabled = false;
    int diskCacheMaxMB = 256;
    boolean debugOverlay = false;

    final AtomicBoolean dirty = new AtomicBoolean(false);

    PerfConfigData() {
    }

    /** 公开工厂：供外部（单测等）构造默认配置；常规运行时访问一律经 {@link PerfConfig}。 */
    public static PerfConfigData empty() {
        return new PerfConfigData();
    }

    public void makeDirty() {
        this.dirty.set(true);
    }

    public int getGenerationThreads() {
        return generationThreads;
    }

    public int getCacheCapacityMB() {
        return cacheCapacityMB;
    }

    public boolean isPrefetchEnabled() {
        return prefetchEnabled;
    }

    public boolean isPregenEnabled() {
        return pregenEnabled;
    }

    public int getPregenRadiusBlocks() {
        return pregenRadiusBlocks;
    }

    public boolean isDiskCacheEnabled() {
        return diskCacheEnabled;
    }

    public int getDiskCacheMaxMB() {
        return diskCacheMaxMB;
    }

    public boolean isDebugOverlay() {
        return debugOverlay;
    }

    /** @return 值是否实际变化（钳制后相等视为未变化，不标脏） */
    public synchronized boolean setGenerationThreads(int threads) {
        threads = Math.max(0, Math.min(64, threads));
        if (this.generationThreads == threads)
            return false;
        this.generationThreads = threads;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setCacheCapacityMB(int mb) {
        mb = Math.max(CACHE_MB_MIN, Math.min(CACHE_MB_MAX, mb));
        if (this.cacheCapacityMB == mb)
            return false;
        this.cacheCapacityMB = mb;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setPrefetchEnabled(boolean enabled) {
        if (this.prefetchEnabled == enabled)
            return false;
        this.prefetchEnabled = enabled;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setPregenEnabled(boolean enabled) {
        if (this.pregenEnabled == enabled)
            return false;
        this.pregenEnabled = enabled;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setPregenRadiusBlocks(int blocks) {
        blocks = Math.max(PREGEN_RADIUS_MIN, Math.min(PREGEN_RADIUS_MAX, blocks));
        if (this.pregenRadiusBlocks == blocks)
            return false;
        this.pregenRadiusBlocks = blocks;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setDiskCacheEnabled(boolean enabled) {
        if (this.diskCacheEnabled == enabled)
            return false;
        this.diskCacheEnabled = enabled;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setDiskCacheMaxMB(int mb) {
        mb = Math.max(DISK_MB_MIN, Math.min(DISK_MB_MAX, mb));
        if (this.diskCacheMaxMB == mb)
            return false;
        this.diskCacheMaxMB = mb;
        makeDirty();
        return true;
    }

    /** @return 值是否实际变化 */
    public synchronized boolean setDebugOverlay(boolean enabled) {
        if (this.debugOverlay == enabled)
            return false;
        this.debugOverlay = enabled;
        makeDirty();
        return true;
    }

    // ─── JSON 持久化 ────────────────────────────────────────────

    /** 写出文档体（全部字段写出，保持文件自描述）。 */
    private synchronized JsonObject writeJson() {
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("generationThreads", generationThreads);
        json.addProperty("cacheCapacityMB", cacheCapacityMB);
        json.addProperty("prefetchEnabled", prefetchEnabled);
        json.addProperty("pregenEnabled", pregenEnabled);
        json.addProperty("pregenRadiusBlocks", pregenRadiusBlocks);
        json.addProperty("diskCacheEnabled", diskCacheEnabled);
        json.addProperty("diskCacheMaxMB", diskCacheMaxMB);
        json.addProperty("debugOverlay", debugOverlay);
        return json;
    }

    /** 从文档体读取：缺失字段落默认值，未知字段忽略；数值越界钳制（单字段容错）。 */
    private static PerfConfigData readJson(JsonObject json) throws IOException {
        try {
            final var config = new PerfConfigData();
            if (json.has("generationThreads"))
                config.generationThreads = Math.max(0, Math.min(64, json.get("generationThreads").getAsInt()));
            if (json.has("cacheCapacityMB"))
                config.cacheCapacityMB = Math.max(CACHE_MB_MIN, Math.min(CACHE_MB_MAX, json.get("cacheCapacityMB").getAsInt()));
            if (json.has("prefetchEnabled"))
                config.prefetchEnabled = json.get("prefetchEnabled").getAsBoolean();
            if (json.has("pregenEnabled"))
                config.pregenEnabled = json.get("pregenEnabled").getAsBoolean();
            if (json.has("pregenRadiusBlocks"))
                config.pregenRadiusBlocks = Math.max(PREGEN_RADIUS_MIN, Math.min(PREGEN_RADIUS_MAX, json.get("pregenRadiusBlocks").getAsInt()));
            if (json.has("diskCacheEnabled"))
                config.diskCacheEnabled = json.get("diskCacheEnabled").getAsBoolean();
            if (json.has("diskCacheMaxMB"))
                config.diskCacheMaxMB = Math.max(DISK_MB_MIN, Math.min(DISK_MB_MAX, json.get("diskCacheMaxMB").getAsInt()));
            if (json.has("debugOverlay"))
                config.debugOverlay = json.get("debugOverlay").getAsBoolean();
            return config;
        } catch (RuntimeException e) {
            throw new IOException("Malformed perf config", e);
        }
    }

    /** perf 配置文档的 JSON 编解码器，配合 {@code JsonConfigFile} 使用。 */
    public static final JsonCodec<PerfConfigData> JSON_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(PerfConfigData data) {
            return data.writeJson();
        }

        @Override
        public PerfConfigData read(JsonObject json) throws IOException {
            return PerfConfigData.readJson(json);
        }
    };
}
