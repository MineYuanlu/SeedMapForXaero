package bid.yuanlu.seedmap4xaero.client.configs.perf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile.Paths;

/**
 * perf 配置（{@code perf_config.json}）的门面类。
 * <p>
 * 与 {@code ServerConfig}（按 mainId 隔离）不同：性能配置是 <b>JVM 级全局语义</b>，
 * 单文件 {@code gameDir/xaero/seed-map-for-xaero/global/perf_config.json}，
 * 客户端初始化时加载一次，不随世界切换。
 * <p>
 * 无 legacy 前身文件（v2 新增配置），加载链中 legacy 阶段恒跳过。
 * <p>
 * 变更通知：{@link #addListener} 注册的回调在任意配置项实际变化后触发
 * （如 {@code CacheHelper} 重建线程池、{@code CellCache} 调整预算）。
 * 持久化：setter 只标脏；周期刷盘（60s）与面板显式 {@link #flush()} 负责落盘。
 */
public final class PerfConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/PerfConfig");
    private static final String CONFIG_FILE = "perf_config.json";

    /** 全局配置子目录名（预留未来其他全局文件平铺于此）。 */
    private static final String GLOBAL_DIR = "global";

    private static volatile PerfConfigData active = new PerfConfigData();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** 单测注入的 base 目录；非 null 时优先于真实 gameDir。 */
    private static volatile Path testBaseDir;

    private PerfConfig() {
    }

    /** {@code gameDir/xaero/seed-map-for-xaero} */
    private static Path baseDir() {
        var injected = testBaseDir;
        if (injected != null)
            return injected;
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static Paths paths(Path base) {
        // legacy 名仅占位：load() 传 legacyCodec=null 恒跳过
        return JsonConfigFile.pathsFor(base, GLOBAL_DIR, CONFIG_FILE, CONFIG_FILE + ".sm4x");
    }

    /** 客户端初始化时加载全局配置（幂等；失败/缺失落默认值）。 */
    public static synchronized void init() {
        active = loadConfig(baseDir());
    }

    static PerfConfigData data() {
        return active;
    }

    // ─── 便捷读取 ───────────────────────────────────────────────

    /** 配置的手动线程数；0 = 自动。 */
    public static int generationThreads() {
        return active.getGenerationThreads();
    }

    /**
     * 实际生效的生成线程数：手动值钳制到 1..availableProcessors；
     * 自动（0）= {@code max(1, min(4, cores/4))}——预留一半核给 MC 本体，
     * 上限 4 避免 16 线程桌面全占。
     */
    public static int effectiveGenerationThreads() {
        int manual = active.getGenerationThreads();
        int cores = Runtime.getRuntime().availableProcessors();
        if (manual <= 0)
            return Math.max(1, Math.min(4, cores / 4));
        return Math.max(1, Math.min(manual, cores));
    }

    /** CellCache 全局 LRU 预算（条目数）：1MB = 64 条（每条 ≈16KB 像素/GPU 纹理）。 */
    public static int cacheCapacityEntries() {
        return active.getCacheCapacityMB() * 64;
    }

    /** 配置的缓存容量原值（MB）。 */
    public static int cacheCapacityMB() {
        return active.getCacheCapacityMB();
    }

    public static boolean prefetchEnabled() {
        return active.isPrefetchEnabled();
    }

    public static boolean pregenEnabled() {
        return active.isPregenEnabled();
    }

    public static int pregenRadiusBlocks() {
        return active.getPregenRadiusBlocks();
    }

    public static boolean diskCacheEnabled() {
        return active.isDiskCacheEnabled();
    }

    public static int diskCacheMaxMB() {
        return active.getDiskCacheMaxMB();
    }

    public static boolean debugOverlay() {
        return active.isDebugOverlay();
    }

    // ─── 便捷写入（标脏 + 通知；落盘由 flush 完成） ──────────────

    public static void setGenerationThreads(int threads) {
        var cfg = active;
        if (cfg.setGenerationThreads(threads))
            notifyListeners();
    }

    public static void setCacheCapacityMB(int mb) {
        var cfg = active;
        if (cfg.setCacheCapacityMB(mb))
            notifyListeners();
    }

    public static void setPrefetchEnabled(boolean enabled) {
        var cfg = active;
        if (cfg.setPrefetchEnabled(enabled))
            notifyListeners();
    }

    public static void setPregenEnabled(boolean enabled) {
        var cfg = active;
        if (cfg.setPregenEnabled(enabled))
            notifyListeners();
    }

    public static void setPregenRadiusBlocks(int blocks) {
        var cfg = active;
        if (cfg.setPregenRadiusBlocks(blocks))
            notifyListeners();
    }

    public static void setDiskCacheEnabled(boolean enabled) {
        var cfg = active;
        if (cfg.setDiskCacheEnabled(enabled))
            notifyListeners();
    }

    public static void setDiskCacheMaxMB(int mb) {
        var cfg = active;
        if (cfg.setDiskCacheMaxMB(mb))
            notifyListeners();
    }

    public static void setDebugOverlay(boolean enabled) {
        var cfg = active;
        if (cfg.setDebugOverlay(enabled))
            notifyListeners();
    }

    private static void notifyListeners() {
        for (Runnable l : LISTENERS)
            l.run();
    }

    // ─── 变更监听 ───────────────────────────────────────────────

    /** 注册配置变更回调（任意项实际变化后触发；保留引用，不做弱引用）。 */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    // ─── 持久化 ─────────────────────────────────────────────────

    /**
     * 会话内刷写（仅脏时，rotate=false）——全局配置无生命周期切换点，
     * .old 备份恒缺省；损坏时回退链落默认值。
     */
    public synchronized static void flush() {
        saveCurrent(false);
    }

    private synchronized static void saveCurrent(boolean rotate) {
        final var cfg = active;
        if (cfg == null || !cfg.dirty.compareAndSet(true, false)) {
            return;
        }
        if (!saveConfig(baseDir(), cfg, rotate)) {
            cfg.dirty.set(true); // 写盘失败恢复脏标志, 本轮变更不丢
        }
    }

    /**
     * 将 {@code cfg} 原子写入 {@code base/global/perf_config.json}。
     * base 参数独立注入以便单元测试（不依赖 Minecraft 客户端）。
     *
     * @return 是否写入成功
     */
    static boolean saveConfig(Path base, PerfConfigData cfg, boolean rotate) {
        try {
            JsonConfigFile.save(paths(base), cfg, PerfConfigData.JSON_CODEC, rotate);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save perf config", e);
            return false;
        }
    }

    /**
     * 从 {@code base/global/perf_config.json} 加载（无 legacy 阶段）。
     * base 参数独立注入以便单元测试。
     */
    static PerfConfigData loadConfig(Path base) {
        return JsonConfigFile.load(paths(base), new PerfConfigData(), PerfConfigData.JSON_CODEC, null);
    }

    /** 测试辅助：重置到默认并注入 base 目录（单测间隔离）。 */
    static void resetForTest(Path base) {
        testBaseDir = base;
        active = new PerfConfigData();
        LISTENERS.clear();
    }

    /** 已注册的监听器快照（测试断言用）。 */
    static List<Runnable> listenersForTest() {
        return List.copyOf(LISTENERS);
    }
}
