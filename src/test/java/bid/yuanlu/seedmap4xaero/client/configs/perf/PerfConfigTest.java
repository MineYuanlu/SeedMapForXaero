package bid.yuanlu.seedmap4xaero.client.configs.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;

class PerfConfigTest {

    @TempDir
    Path tmp;

    private static JsonConfigFile.Paths paths(Path base) {
        return JsonConfigFile.pathsFor(base, "global", "perf_config.json", "perf_config.json.sm4x");
    }

    private PerfConfigData roundtrip(PerfConfigData cfg) throws Exception {
        JsonConfigFile.save(paths(tmp), cfg, PerfConfigData.JSON_CODEC, false);
        return JsonConfigFile.readJson(paths(tmp).target(), PerfConfigData.JSON_CODEC);
    }

    // ─── 默认值 ─────────────────────────────────────────────────

    @Test
    void defaults() {
        var cfg = new PerfConfigData();
        assertEquals(0, cfg.getGenerationThreads());
        assertEquals(128, cfg.getCacheCapacityMB());
        assertTrue(cfg.isPrefetchEnabled());
        assertFalse(cfg.isPregenEnabled());
        assertEquals(8192, cfg.getPregenRadiusBlocks());
        assertFalse(cfg.isDiskCacheEnabled());
        assertEquals(256, cfg.getDiskCacheMaxMB());
        assertFalse(cfg.isDebugOverlay());
    }

    // ─── JSON roundtrip ─────────────────────────────────────────

    @Test
    void jsonRoundtrip() throws Exception {
        var cfg = new PerfConfigData();
        cfg.setGenerationThreads(6);
        cfg.setCacheCapacityMB(256);
        cfg.setPrefetchEnabled(false);
        cfg.setPregenEnabled(true);
        cfg.setPregenRadiusBlocks(16384);
        cfg.setDiskCacheEnabled(true);
        cfg.setDiskCacheMaxMB(1024);
        cfg.setDebugOverlay(true);

        var back = roundtrip(cfg);
        assertEquals(6, back.getGenerationThreads());
        assertEquals(256, back.getCacheCapacityMB());
        assertFalse(back.isPrefetchEnabled());
        assertTrue(back.isPregenEnabled());
        assertEquals(16384, back.getPregenRadiusBlocks());
        assertTrue(back.isDiskCacheEnabled());
        assertEquals(1024, back.getDiskCacheMaxMB());
        assertTrue(back.isDebugOverlay());
    }

    @Test
    void missingFieldsFallBackToDefaults() throws Exception {
        // 仅含一个字段的文档：其余字段落默认值（双向前向兼容）
        Path file = paths(tmp).target();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\": 1, \"cacheCapacityMB\": 64}");
        var cfg = JsonConfigFile.readJson(file, PerfConfigData.JSON_CODEC);
        assertEquals(64, cfg.getCacheCapacityMB());
        assertEquals(0, cfg.getGenerationThreads());
        assertTrue(cfg.isPrefetchEnabled());
        assertFalse(cfg.isPregenEnabled());
    }

    @Test
    void unknownFieldsIgnored() throws Exception {
        Path file = paths(tmp).target();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"futureField\": true, \"cacheCapacityMB\": 96}");
        var cfg = JsonConfigFile.readJson(file, PerfConfigData.JSON_CODEC);
        assertEquals(96, cfg.getCacheCapacityMB());
    }

    // ─── 钳制与单字段容错 ────────────────────────────────────────

    @Test
    void setterClamps() {
        var cfg = new PerfConfigData();
        cfg.setGenerationThreads(-5);
        assertEquals(0, cfg.getGenerationThreads());
        cfg.setGenerationThreads(999);
        assertEquals(64, cfg.getGenerationThreads());
        cfg.setCacheCapacityMB(1);
        assertEquals(PerfConfigData.CACHE_MB_MIN, cfg.getCacheCapacityMB());
        cfg.setCacheCapacityMB(99999);
        assertEquals(PerfConfigData.CACHE_MB_MAX, cfg.getCacheCapacityMB());
        cfg.setPregenRadiusBlocks(0);
        assertEquals(PerfConfigData.PREGEN_RADIUS_MIN, cfg.getPregenRadiusBlocks());
        cfg.setDiskCacheMaxMB(1 << 20);
        assertEquals(PerfConfigData.DISK_MB_MAX, cfg.getDiskCacheMaxMB());
    }

    @Test
    void readClampsOutOfRangeValues() throws Exception {
        Path file = paths(tmp).target();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"cacheCapacityMB\": 99999, \"generationThreads\": -3}");
        var cfg = JsonConfigFile.readJson(file, PerfConfigData.JSON_CODEC);
        assertEquals(PerfConfigData.CACHE_MB_MAX, cfg.getCacheCapacityMB());
        assertEquals(0, cfg.getGenerationThreads());
    }

    @Test
    void malformedTypeFailsWholeDocument() {
        // 类型损坏 = 结构性错误 → IOException 走回退链（与 basic 语义一致）
        Path file = paths(tmp).target();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"cacheCapacityMB\": \"not-a-number\"}");
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> JsonConfigFile.readJson(file, PerfConfigData.JSON_CODEC));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── 门面行为（testBaseDir 注入） ────────────────────────────

    @Test
    void facadeLoadPersistAndDirtyFlag() throws Exception {
        PerfConfig.resetForTest(tmp);
        try {
            PerfConfig.init();
            assertEquals(0, PerfConfig.generationThreads()); // 文件不存在 → 默认

            PerfConfig.setCacheCapacityMB(256);
            assertTrue(PerfConfig.data().dirty.get());
            PerfConfig.flush();
            assertFalse(PerfConfig.data().dirty.get());
            assertTrue(Files.exists(paths(tmp).target()));

            // 模拟重启：重置后重新 init 应读回
            PerfConfig.resetForTest(tmp);
            PerfConfig.init();
            assertEquals(256, PerfConfig.cacheCapacityEntries() / 64);

            // flush 空转：无脏数据时不写
            long mtime1 = Files.getLastModifiedTime(paths(tmp).target()).toMillis();
            Thread.sleep(10);
            PerfConfig.flush();
            long mtime2 = Files.getLastModifiedTime(paths(tmp).target()).toMillis();
            assertEquals(mtime1, mtime2);
        } finally {
            PerfConfig.resetForTest(null);
        }
    }

    @Test
    void facadeListenerFiresOnChangeOnly() {
        PerfConfig.resetForTest(tmp);
        try {
            final int[] fired = {0};
            PerfConfig.addListener(() -> fired[0]++);
            PerfConfig.setPrefetchEnabled(true); // 相等短路 → 不通知
            assertEquals(0, fired[0]);
            PerfConfig.setPrefetchEnabled(false);
            assertEquals(1, fired[0]);
            PerfConfig.setPrefetchEnabled(false);
            assertEquals(1, fired[0]);
        } finally {
            PerfConfig.resetForTest(null);
        }
    }

    // ─── 线程数推导 ─────────────────────────────────────────────

    @Test
    void effectiveThreadsManualClamp() {
        PerfConfig.resetForTest(tmp);
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            PerfConfig.setGenerationThreads(cores * 10);
            assertEquals(cores, PerfConfig.effectiveGenerationThreads());
            PerfConfig.setGenerationThreads(3);
            assertEquals(3, PerfConfig.effectiveGenerationThreads());
        } finally {
            PerfConfig.resetForTest(null);
        }
    }

    @Test
    void effectiveThreadsAutoFormula() {
        PerfConfig.resetForTest(tmp);
        try {
            // 自动模式下结果恒 >= 1（公式 max(1, min(4, cores/4))）
            PerfConfig.setGenerationThreads(0);
            int cores = Runtime.getRuntime().availableProcessors();
            int expected = Math.max(1, Math.min(4, cores / 4));
            assertEquals(expected, PerfConfig.effectiveGenerationThreads());
        } finally {
            PerfConfig.resetForTest(null);
        }
    }
}
