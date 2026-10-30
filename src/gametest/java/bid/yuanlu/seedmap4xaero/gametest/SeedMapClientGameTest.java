package bid.yuanlu.seedmap4xaero.gametest;

import java.util.Objects;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.configs.basic.ConfigData;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureData;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataLegacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache.CellKey;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.compat.CompatGui;
import bid.yuanlu.seedmap4xaero.client.compat.CompatInput;
import bid.yuanlu.seedmap4xaero.client.configs.basic.LootDisplayMode;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureGroups;
import bid.yuanlu.seedmap4xaero.client.gui.SeedMapPanel;
import bid.yuanlu.seedmap4xaero.client.mixin.SeedMapMixin;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.HighlightHudRenderer;
import bid.yuanlu.seedmap4xaero.client.structure.ChestLootWidget;
import bid.yuanlu.seedmap4xaero.client.structure.CustomStructureType;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.LootPreviewState;
import bid.yuanlu.seedmap4xaero.client.structure.StructureInfo;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.client.structure.StructureTypes;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;

import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/**
 * E2E client gametest：真实启动 MC 客户端 → 创建单机世界（已知种子）→ 打开 Xaero World Map
 * → 断言种子地图渲染路径真实工作。
 *
 * <p>
 * 验证链路（与 mod 的 client/AGENTS.md 渲染流程对应）：
 * <ol>
 * <li>{@code ServerConfig.activate} 在 GuiMap.init 时被调用（activeMainId 非 null）</li>
 * <li>{@link ServerConfig#resolveSeed} 从单机服务器正确解析已知种子</li>
 * <li>{@link SeedMapMixin#tickWorldInfo} → {@code Xsm.setWorld} + {@link CellCache} 触发
 *     native C 生成（mapProcessor 可用 + hasScaleCache 有数据）</li>
 * <li>{@link StructureCache#REGIONS} 有异步结构查询结果</li>
 * <li>HUD 高亮图标背后隐藏：真实结构正对时绘制 ({@code lastFrameHighlightBlits==1})、
 *     转向水平镜像点后隐藏 ({@code ==0})——回归 26.2 线性深度下 NDC.z&lt;0 漏过 z&gt;1 检查的镜像 bug</li>
 * </ol>
 * </p>
 */
@SuppressWarnings("UnstableApiUsage")
public class SeedMapClientGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/gametest");

    /** 与 NativeMcTest 一致的已知种子，用于确定性断言。 */
    private static final long KNOWN_SEED = 123456789L;

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            xsm$runTest(context);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void xsm$runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(ui -> ui.setSeed(Long.toString(KNOWN_SEED)))
                .create()) {
            try {
                xsm$runAssertions(context, singleplayer);

                LOGGER.info("seed-map E2E assertions passed");
            } catch (Throwable e) {
                // 测试体异常时 try-with-resources 的 close() 会撞上 close() 死锁（见下方注释），
                // 框架的重抛永远走不到——必须在这里立即落盘，否则失败原因被死锁吞掉
                LOGGER.error("seed-map E2E FAILED", e);
                throw e;
            } finally {
                // 绕开 close() 死锁：MC 26 的 IntegratedServer.halt 先 executeBlocking 等 server 处理停止
                // 任务，而 fabric client gametest 的 phaser 让 server 卡在 postRunTasks 等 render arrive。
                // 在 server 线程内主动 halt（executeBlocking 检测当前线程即 server，直接执行不阻塞），
                // 使 server 先脱离 phaser 协调，之后 close() 的 disconnect 才能顺利完成。
                // 放在 finally：断言失败时同样先 halt，否则 close() 死锁会让 CI 一直卡住。
                try {
                    singleplayer.getServer().runOnServer(server -> server.halt(false));
                } catch (Throwable t) {
                    LOGGER.warn("server halt during gametest teardown failed", t);
                }
            }
        }
    }

    private void xsm$runAssertions(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        waitForChunksRender(singleplayer);
        context.waitTick();

        assertSeedResolved(context, singleplayer);
        context.takeScreenshot("1-world-before-map");

        // 配置系统 v2: 预置 legacy .sm4x → 开图激活时真实迁移
        seedLegacyConfigs(context);

        openWorldMap(context);
        context.waitForScreen(GuiMap.class);

        // 等若干 tick 让 tickWorldInfo → CellCache 生成链路跑起来
        context.waitTicks(20);

        assertMapActivated();
        assertCellCachePopulated(context);
        assertStructureCachePopulated(context);
        context.takeScreenshot("2-seed-map-baselines");

        // 配置系统 v2: 迁移产物 + JSON 落盘 + marks region 分片
        assertLegacyMigrated(context);
        assertJsonConfigOnDisk(context);
        assertBackgroundPregenTick(context);

        // 后台预生成 tick 路径 (26.2+ screen 读取 compat 覆盖)
        assertBackgroundPregenTick(context);

        // 数据包自定义结构 (路线 A): 模拟 DatapackStructures.reload 注入
        // (gametest 世界无数据包, 直接注入合成表), 验证 Java 注册表 →
        // getStructureTypeSet → StructureCache → REGIONS 全链路。
        // 过程截图人工验收: 地图上的琥珀菱形图标 + 面板类型列表自定义行。
        assertCustomStructuresInjected(context);

        context.takeScreenshot("3-seed-map-final");

        assertPanelScreenshots(context, singleplayer);

        assertHighlightHudHiddenWhenBehind(context, singleplayer);

        assertLootTitleTooltipNoCrash(context);

        assertLootPaging(context);
    }

    /**
     * 等待 chunk 渲染完成。跨 fabric-client-gametest 版本兼容：
     * <ul>
     * <li>5.1.x（fabric-api 0.153/0.155，MC 26.1）：{@code singleplayer.getClientLevel()}
     *     返回 {@code TestClientLevelContext}（有 {@code waitForChunksRender()}）</li>
     * <li>6.0.0（fabric-api 0.156，MC 26.2）：该类型被移除，改走
     *     {@code singleplayer.getConnection().waitForChunksRender()}</li>
     * </ul>
     * 用反射统一入口：先试 {@code getClientLevel().waitForChunksRender()}，
     * 没有则退到 {@code getConnection().waitForChunksRender()}。
     */
    private static void waitForChunksRender(TestSingleplayerContext singleplayer) {
        try {
            Object level = TestSingleplayerContext.class.getMethod("getClientLevel").invoke(singleplayer);
            level.getClass().getMethod("waitForChunksRender").invoke(level);
            return;
        } catch (NoSuchMethodException e) {
            // 6.0.0：getClientLevel 已移除
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("waitForChunksRender (getClientLevel path) failed", e);
        }

        try {
            Object connection = TestSingleplayerContext.class.getMethod("getConnection").invoke(singleplayer);
            connection.getClass().getMethod("waitForChunksRender").invoke(connection);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("waitForChunksRender (getConnection path) failed", e);
        }
    }

    /** 单机种子解析：直接读 Minecraft 单机服务器的世界生成设置。 */
    private static void assertSeedResolved(ClientGameTestContext context, TestSingleplayerContext sp) {
        context.runOnClient(client -> {
            Long seed = ServerConfig.resolveSeed();
            if (seed == null || seed != KNOWN_SEED) {
                throw new AssertionError("resolveSeed() = " + seed + ", expected " + KNOWN_SEED);
            }
            // 直接验证单机服务器已用该种子
            Long serverSeed = client.getSingleplayerServer().getWorldGenSettings().options().seed();
            if (!Objects.equals(serverSeed, KNOWN_SEED)) {
                throw new AssertionError("server seed = " + serverSeed + ", expected " + KNOWN_SEED);
            }
        });
    }

    /** 打开 Xaero World Map：手动构造 GuiMap（等同按 M 快捷键的路径）。 */
    private static void openWorldMap(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var session = WorldMapSession.getCurrentSession();
            if (session == null || !session.isUsable()) {
                throw new AssertionError("WorldMapSession not usable");
            }
            MapProcessor mp = session.getMapProcessor();
            if (mp == null) {
                throw new AssertionError("MapProcessor not ready");
            }
            // MC 26.2 移除 Minecraft.setScreen，改为 setScreenAndShow（26.1 两者都有）
            client.setScreenAndShow(new GuiMap(null, null, mp, client.getCameraEntity()));
        });
    }

    /** GuiMap.init → SeedMapMixin.xsm$onGuiMapInit → ServerConfig.activate。 */
    private static void assertMapActivated() {
        String mainId = ServerConfig.activeMainId();
        if (mainId == null) {
            throw new AssertionError("ServerConfig.activate was not called after opening the map");
        }
        LOGGER.info("activeMainId = {}", mainId);
    }

    /**
     * 断言 CellCache 生成：地图以 userScale 打开后，可见区域的 cell 会被
     * {@code getOrRequest} 请求，异步 C 生成完成后缓存内有数据。
     */
    private static void assertCellCachePopulated(ClientGameTestContext context) {
        context.waitFor(client -> {
            for (int scale : new int[] { 1, 4, 16 }) {
                if (CellCache.hasScaleCache(scale)) {
                    LOGGER.info("CellCache has scale={} entries", scale);
                    return true;
                }
            }
            return false;
        }, 100);
    }

    /** 断言结构缓存有查询结果（异步 CACHE_WORKER 写入 REGIONS）。 */
    private static void assertStructureCachePopulated(ClientGameTestContext context) {
        context.waitFor(client -> !StructureCache.REGIONS.isEmpty(), 200);
        LOGGER.info("StructureCache types = {}", StructureCache.REGIONS.keySet());
    }

    /**
     * 开图前预置 legacy {@code .sm4x}（v0.6.x 二进制格式，由冻结的 legacy writer
     * 生成）：activate 加载时走真实迁移链路。标记放在当前单机 (seed=KNOWN_SEED,
     * mwId="") 下 (12345,-5432) 供后续断言。
     */
    private static void seedLegacyConfigs(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var session = WorldMapSession.getCurrentSession();
            if (session == null || !session.isUsable()) {
                throw new AssertionError("session not usable before map open");
            }
            var mp = session.getMapProcessor();
            String mainId = mp.getCurrentWorldId();
            if (mainId == null && mp.getMapWorld() != null) {
                mainId = mp.getMapWorld().getMainId();
            }
            if (mainId == null) {
                throw new AssertionError("mainId unavailable before map open");
            }
            var base = client.gameDirectory.toPath()
                    .resolve("xaero").resolve("seed-map-for-xaero").resolve(mainId);
            try {
                java.nio.file.Files.createDirectories(base);
                Sm4xFile.writeFrame(base.resolve("server_config.sm4x"), ConfigData.empty(),
                        ConfigData.LEGACY_CODEC);
                var dim = new StructureDataLegacy.LegacyDim(java.util.Map.of(
                        StructureType.VILLAGE.id,
                        java.util.List.of(new StructureDataLegacy.LegacyMark(
                                StructureDataConfig.keyOf(12345, -5432), 7, "e2e组"))));
                var snap = new StructureDataLegacy.Snapshot(
                        java.util.List.of(),
                        java.util.List.of(new StructureData.UserGroup("e2e组", 0xFF00AAFF)),
                        new Long2ObjectOpenHashMap<>(java.util.Map.of(
                                KNOWN_SEED, new StructureDataLegacy.LegacySeed(java.util.Map.of("", dim)))));
                Sm4xFile.writeFrame(base.resolve("structure_data.sm4x"), snap,
                        StructureDataLegacy.LEGACY_CODEC);
            } catch (java.io.IOException e) {
                throw new AssertionError("failed to seed legacy configs", e);
            }
            LOGGER.info("legacy configs seeded at {}", base);
        });
        context.waitTick();
    }

    /** 开图后断言 legacy → JSON 迁移产物：改名 + json + 会话内可见（设置 + 标记）。 */
    private static void assertLegacyMigrated(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var base = client.gameDirectory.toPath()
                    .resolve("xaero").resolve("seed-map-for-xaero").resolve(ServerConfig.activeMainId());
            if (!java.nio.file.Files.exists(base.resolve("server_config.sm4x.legacy"))) {
                throw new AssertionError("server_config.sm4x not migrated/renamed");
            }
            if (!java.nio.file.Files.exists(base.resolve("structure_data.sm4x.legacy"))) {
                throw new AssertionError("structure_data.sm4x not migrated/renamed");
            }
            if (java.nio.file.Files.exists(base.resolve("server_config.sm4x"))
                    || java.nio.file.Files.exists(base.resolve("structure_data.sm4x"))) {
                throw new AssertionError("legacy files must be retired after migration");
            }

            var settings = StructureDataConfig.getActiveData();
            if (settings == null || settings.colorOf("e2e组") != 0xFF00AAFF) {
                throw new AssertionError("user group from legacy not visible in session");
            }
            var mark = StructureDataConfig.getMark(StructureType.VILLAGE,
                    StructureDataConfig.keyOf(12345, -5432));
            if (mark == null || mark.minDist() != 7 || !"e2e组".equals(mark.group())) {
                throw new AssertionError("migrated mark not visible in session: " + mark);
            }
            LOGGER.info("legacy migration E2E assertions passed");
        });
        context.waitTick();
    }

    /**
     * 配置系统 v2 落盘验证：
     * <ol>
     * <li>server_config.json 为纯 JSON（设置变更 → flush → 内容可读回）</li>
     * <li>迁移标记的 region 分片存在且含结构 key + 用户组</li>
     * <li>会话内新 markVisited → flush → 新 region 分片生成</li>
     * </ol>
     */
    private static void assertJsonConfigOnDisk(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var mainId = ServerConfig.activeMainId();
            if (mainId == null)
                throw new AssertionError("config not activated");
            var base = client.gameDirectory.toPath()
                    .resolve("xaero").resolve("seed-map-for-xaero").resolve(mainId);

            // 1. server_config.json: 设置变更 → flush → 纯 JSON 可读
            var cfg = ServerConfig.getActiveConfig();
            if (cfg == null)
                throw new AssertionError("active config missing");
            cfg.setInvisibleBiomes(true);
            ServerConfig.flush();
            var jsonPath = base.resolve("server_config.json");
            if (!java.nio.file.Files.exists(jsonPath))
                throw new AssertionError("server_config.json not created");
            try {
                String text = java.nio.file.Files.readString(jsonPath);
                if (!text.strip().startsWith("{"))
                    throw new AssertionError("server_config.json is not plain JSON: " + text);
                if (!text.contains("\"invisibleBiomes\": true"))
                    throw new AssertionError("setting change not persisted:\n" + text);
            } catch (java.io.IOException e) {
                throw new AssertionError("failed to read server_config.json", e);
            }
            cfg.setInvisibleBiomes(false); // 还原

            // 2. 迁移标记的 region 分片 (12345,-5432) → region (12,-6); mwId "" → default/
            var marksDir = base.resolve("marks")
                    .resolve(Long.toHexString(KNOWN_SEED))
                    .resolve("default");
            try {
                String text = java.nio.file.Files.readString(marksDir.resolve("r.12.-6.json"));
                if (!text.contains("\"minecraft:village\"") || !text.contains("e2e组"))
                    throw new AssertionError("region file missing structure key/group:\n" + text);
            } catch (java.io.IOException e) {
                throw new AssertionError("migrated marks region file missing: " + marksDir, e);
            }

            // 3. 会话内新访问 → flush → 新 region 分片 (23456,-6789) → region (22,-7)
            StructureDataConfig.markVisited(StructureType.FORTRESS,
                    StructureDataConfig.keyOf(23456, -6789), 42);
            StructureDataConfig.flush();
            if (!java.nio.file.Files.exists(marksDir.resolve("r.22.-7.json")))
                throw new AssertionError("live markVisited region file not created");

            // 4. perf 全局配置: 变更 → flush → gameDir/…/global/perf_config.json 落盘
            PerfConfig.setDebugOverlay(true);
            PerfConfig.flush();
            var perfPath = client.gameDirectory.toPath()
                    .resolve("xaero").resolve("seed-map-for-xaero")
                    .resolve("global").resolve("perf_config.json");
            if (!java.nio.file.Files.exists(perfPath))
                throw new AssertionError("perf_config.json not created");
            try {
                String text = java.nio.file.Files.readString(perfPath);
                if (!text.contains("\"debugOverlay\": true"))
                    throw new AssertionError("perf setting change not persisted:\n" + text);
            } catch (java.io.IOException e) {
                throw new AssertionError("failed to read perf_config.json", e);
            }
            PerfConfig.setDebugOverlay(false); // 还原
            PerfConfig.flush();

            LOGGER.info("json config E2E assertions passed (server_config.json + marks shards + perf_config.json)");
        });
        context.waitTick();
    }

    /**
     * 数据包自定义结构链路 (路线 A):
     * <ol>
     * <li>模拟 {@code DatapackStructures.reload}: C 表注入 + Java 注册表 +
     *     缓存失效 (gametest 世界无数据包, 注入 Terralith regular 同款合成表)</li>
     * <li>直查 {@code Xsm.queryRegionStructuresGrid} 有确定位置</li>
     * <li>等 REGIONS 收录自定义 id (getStructureTypeSet → updateStructuresInArea 全链路)</li>
     * <li>过程截图 3a/3b: 地图上的自定义结构图标 (琥珀菱形回退) +
     *     面板类型列表自定义行 (Alpha/Beta)</li>
     * <li>清理: 清空 C 表 + 注册表 + 缓存</li>
     * </ol>
     */
    private static void assertCustomStructuresInjected(ClientGameTestContext context) {
        final int customId = 100;
        context.runOnClient(client -> {
            // 1. 注入: 2 个单条目集合 (无加权掷骰 → 每 region 两结构都有候选,
            //    截图确定性可见), spacing=16 (256 格/region, 出生点视野必覆盖)
            int[] sets = { 2358902, 16, 7, 0, 0, 0, 1, 1065353216, 0,
                    2358903, 16, 7, 0, 0, 1, 1, 1065353216, 0 };
            int[] entries = { customId, 1, customId + 1, 1 };
            if (!Xsm.setCustomStructures(sets, entries)) {
                throw new AssertionError("Xsm.setCustomStructures rejected the table");
            }
            var types = new java.util.ArrayList<CustomStructureType>();
            var config = new StructureType.Config(2358902, 16, 9, 0, 0f);
            types.add(new CustomStructureType(customId, "gametest:alpha", "Alpha", config, 1, null, false));
            types.add(new CustomStructureType(customId + 1, "gametest:beta", "Beta", config, 1, null, false));
            StructureTypes.setCustomTypes(types);
            CacheHelper.invalidateAll();
        });

        // 2. 直查: 单机种子下 3×3 region 至少一个候选 (网格恒产出, 无 biome 校验)
        context.waitFor(client -> {
            final boolean[] hit = { false };
            Xsm.queryRegionStructuresGrid(customId, -1, -1, 2, 2, 0, 0, 0, 0,
                    (rx, rz, found, bx, bz, variant) -> {
                        if (found)
                            hit[0] = true;
                    });
            return hit[0];
        }, 50);

        // 3. 全链路: REGIONS 收录自定义 id 且视口内有已加载候选 (图标才可见)
        //    (REGIONS 集合恒含视口内全部 region 的占位条目, 仅判非空会假通过;
        //     同时等 builtin 缓存重建回填, 面板截图才有最近结构可用)
        context.waitFor(client -> anyLoadedCandidate(customId, customId + 1)
                && nearestStructure(client) != null, 200);
        context.waitTicks(5); // 等图标 overlay 帧渲染
        LOGGER.info("custom structures visible in REGIONS");

        // 3.5 过程截图 (注入仍在生效, 清理前): 地图图标 + 面板类型行
        context.runOnClient(client -> {
            for (int id : new int[] { customId, customId + 1 }) {
                var positions = StructureCache.REGIONS.get(id);
                if (positions == null)
                    continue;
                int shown = 0;
                for (var pos : positions) {
                    if (!pos.loaded())
                        continue;
                    LOGGER.info("custom structure id={} candidate at ({},{})",
                            id, pos.blockX(), pos.blockZ());
                    if (++shown >= 3)
                        break;
                }
            }
        });
        context.takeScreenshot("3a-custom-structures-map");
        context.runOnClient(client -> {
            var panel = SeedMapPanel.activePanel();
            if (panel == null)
                throw new AssertionError("SeedMapPanel not registered during custom injection");
            panel.toggleOpen();
            panel.testExpandSections();
            panel.testSelectStructTab(0);
            panel.testScrollStructList(Integer.MAX_VALUE); // 自定义行在列表末尾, render 时钳制到底
        });
        context.waitTick();
        context.takeScreenshot("3b-panel-custom-structures");
        context.runOnClient(client -> {
            var panel = SeedMapPanel.activePanel();
            panel.toggleOpen(); // 还原关闭态
            panel.testScrollStructList(0); // 还原滚动, 不污染后续面板截图
        });
        context.waitTick();

        // 4. 清理 (不污染后续断言)
        context.runOnClient(client -> {
            Xsm.setCustomStructures(new int[0], new int[0]);
            StructureTypes.setCustomTypes(java.util.List.of());
            CacheHelper.invalidateAll();
        });
        // invalidateAll 清空了含 REGIONS 的全部缓存; 等渲染帧重查回填到
        // "能找到最近结构" 的程度, 否则后续面板截图步骤断言失败
        // (注: REGIONS 可能残留清理后的自定义 id 条目, 不能只判 isEmpty)
        context.waitFor(client -> nearestStructure(client) != null, 200);
        LOGGER.info("custom structure injection test passed, REGIONS repopulated");
    }

    /**
     * 后台预生成 tick 路径：监听器每 tick 读取当前 screen（26.1 字段 / 26.2+ {@code Gui.screen()}），
     * 26.2 曾因直接访问 {@code Minecraft.screen} 抛 {@code NoSuchFieldError}。开图时 pregen
     * 会提前返回，但 screen 读取发生在提前返回之前，足以覆盖跨版本取值链。
     */
    private static void assertBackgroundPregenTick(ClientGameTestContext context) {
        // PerfConfig 的落盘路径依赖 Minecraft.getInstance()，只能在客户端线程操作
        context.runOnClient(client -> PerfConfig.setPregenEnabled(true));
        try {
            context.waitTicks(3);
            context.runOnClient(client -> {
                Object screen = CompatGui.currentScreen(client);
                if (!(screen instanceof GuiMap))
                    throw new AssertionError("background pregen failed to read current screen: " + screen);
            });
        } finally {
            try {
                context.runOnClient(client -> {
                    PerfConfig.setPregenEnabled(false);
                    PerfConfig.flush();
                });
            } catch (Throwable t) {
                LOGGER.warn("background pregen E2E teardown failed", t);
            }
        }
        LOGGER.info("background pregen tick E2E assertion passed");
    }

    /** 要高亮的真实结构位置。 */
    private record StructureTarget(int blockX, int blockZ, StructureInfo type, int variant) {
    }

    /**
     * 面板 UI 截图（人工验收）: 打开面板 → 生物群系展开/类型/分组/组编辑器/图标 五张；
     * 再给最近结构上组色遮罩，截一张无面板遮挡的地图视图验证图标 mask。
     * 结束后还原标记与组色。
     */
    private static void assertPanelScreenshots(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        // 保证最近结构在地图视野内 (面板截图时背后可见)
        StructureTarget target = waitForNearestStructure(context);
        if (context.computeOnClient(client -> Math.hypot(
                client.player.getX() - (target.blockX() + 0.5),
                client.player.getZ() - (target.blockZ() + 0.5)) > 400.0)) {
            context.runOnClient(client -> client.setScreenAndShow(null));
            context.waitTick();
            teleportNear(context, singleplayer, target.blockX() + 32, target.blockZ() + 32);
            openWorldMap(context);
            context.waitForScreen(GuiMap.class);
            context.waitTicks(20);
        }

        context.runOnClient(client -> {
            SeedMapPanel panel = SeedMapPanel.activePanel();
            if (panel == null) {
                throw new AssertionError("SeedMapPanel not registered after GuiMap init");
            }
            panel.toggleOpen();
            panel.testExpandSections();
        });
        context.waitTick();
        context.takeScreenshot("4-panel-types");

        setStructTab(context, 1);
        context.waitTick();
        context.takeScreenshot("5-panel-groups");

        context.runOnClient(client -> {
            if (SeedMapPanel.activePanel().testCreateGroup() == null) {
                throw new AssertionError("createGroup failed to open editor");
            }
        });
        context.waitTick();
        context.takeScreenshot("6-panel-group-editor");

        setStructTab(context, 2);
        context.waitTick();
        context.takeScreenshot("7-panel-icons");

        // 性能区: 展开后截图 (perf_config.json 全局配置 UI)
        context.runOnClient(client -> SeedMapPanel.activePanel().testExpandPerf());
        context.waitTick();
        context.takeScreenshot("panel-perf");

        // 生物群系区展开截图: 展开帧每帧走 renderBiomeSection → CompatGui.blitSprite
        // (issue #32: 26.3 上 blitSprite 重载误匹配在此崩溃)。断言计数增长 =
        // 真实渲染帧确实驱动了该反射路径, 且客户端未崩。
        long blitSpritesBefore = context.computeOnClient(client -> CompatGui.blitSpriteCallCount);
        context.runOnClient(client -> SeedMapPanel.activePanel().testSetBiomeExpanded(true));
        context.waitTicks(3);
        context.runOnClient(client -> {
            long called = CompatGui.blitSpriteCallCount - blitSpritesBefore;
            if (called <= 0) {
                throw new AssertionError("biome panel expanded but CompatGui.blitSprite not invoked ("
                        + called + " calls) — issue #32 render path not exercised");
            }
            Object screen = CompatGui.currentScreen(client);
            if (screen == null) {
                throw new AssertionError("cannot read current screen via compat layer");
            }
            if (!(screen instanceof GuiMap)) {
                throw new AssertionError("client left map (crash) during biome panel render: " + screen);
            }
            LOGGER.info("biome panel expand drove {} blitSprite calls", called);
        });
        context.takeScreenshot("panel-biomes");
        context.runOnClient(client -> SeedMapPanel.activePanel().testSetBiomeExpanded(false));
        context.waitTick();

        // 图标组色遮罩: 最近结构标为 done + 半透明红覆盖
        context.runOnClient(client -> {
            SeedMapPanel.activePanel().toggleOpen(); // 关面板露出地图
            var t = nearestStructure(client);
            StructureDataConfig.setGroup(t.type(),
                    StructureDataConfig.keyOf(t.blockX(), t.blockZ()), StructureGroups.DONE);
            StructureDataConfig.setGroupColor(StructureGroups.DONE, 0x80FF5555);
        });
        context.waitTick();
        context.takeScreenshot("8-icon-mask");

        // 还原 (避免 run 目录的 structure_data 残留影响下次运行)
        context.runOnClient(client -> {
            var t = nearestStructure(client);
            StructureDataConfig.setGroup(t.type(),
                    StructureDataConfig.keyOf(t.blockX(), t.blockZ()), null);
            StructureDataConfig.clearGroupColor(StructureGroups.DONE);
        });
        context.waitTick();
        LOGGER.info("panel screenshots taken");
    }

    private static void setStructTab(ClientGameTestContext context, int tab) {
        context.runOnClient(client -> SeedMapPanel.activePanel().testSelectStructTab(tab));
    }

    /**
     * 回归: 26.2 线性深度约定下背后结构 NDC.z&lt;0 曾漏过旧的 z&gt;1 隐藏检查 (镜像图标)。
     * 真实高亮一个结构 → 正视必绘制 (blits==1), 转向水平镜像点 (结构恰好正背后) 必隐藏 (blits==0)。
     */
    private static void assertHighlightHudHiddenWhenBehind(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        StructureTarget target = waitForNearestStructure(context);
        LOGGER.info("highlight target = {} at ({},{})", target.type(), target.blockX(), target.blockZ());

        if (context.computeOnClient(client -> Math.hypot(
                client.player.getX() - (target.blockX() + 0.5),
                client.player.getZ() - (target.blockZ() + 0.5)) > 400.0)) {
            teleportNear(context, singleplayer, target.blockX() + 32, target.blockZ() + 32);
        }

        context.runOnClient(client -> client.setScreenAndShow(null));
        context.waitTick();

        context.runOnClient(client -> HighlightedStructures.toggle(
                client.level.dimension(), target.blockX(), target.blockZ(), target.type(), target.variant()));
        context.waitTick();

        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(target.blockX() + 0.5, client.player.getEyeY(), target.blockZ() + 0.5)));
        context.waitFor(client -> HighlightHudRenderer.lastFrameHighlightBlits == 1, 100);
        context.takeScreenshot("9-highlight-hud-facing");

        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(2.0 * client.player.getX() - (target.blockX() + 0.5),
                        client.player.getEyeY(),
                        2.0 * client.player.getZ() - (target.blockZ() + 0.5))));
        context.waitFor(client -> HighlightHudRenderer.lastFrameHighlightBlits == 0, 100);
        context.takeScreenshot("10-highlight-hud-behind");

        context.runOnClient(client -> HighlightedStructures.clear());
        LOGGER.info("highlight-hud E2E assertions passed");
    }

    /**
     * 等待出现已加载结构并返回 (缓存逐帧异步重建, 单次立即查询有竞态)。
     * 超时后 dump REGIONS 内容辅助诊断。
     */
    private static StructureTarget waitForNearestStructure(ClientGameTestContext context) {
        for (int i = 0; i < 200; i++) {
            var t = context.computeOnClient(SeedMapClientGameTest::nearestStructure);
            if (t != null)
                return t;
            context.waitTick();
        }
        context.runOnClient(client -> {
            LOGGER.warn("REGIONS dump: {} types", StructureCache.REGIONS.size());
            for (var e : StructureCache.REGIONS.entrySet()) {
                int loaded = 0;
                for (var p : e.getValue())
                    if (p.loaded())
                        loaded++;
                LOGGER.warn("  id={} total={} loaded={}", e.getKey(), e.getValue().size(), loaded);
            }
        });
        throw new AssertionError("no loaded structure for panel mask screenshot");
    }

    /** REGIONS 中给定结构 id 是否有已加载候选 (占位条目不算, 图标可见的前提)。 */
    private static boolean anyLoadedCandidate(int... ids) {
        for (int id : ids) {
            var c = StructureCache.REGIONS.get(id);
            if (c == null)
                continue;
            for (var p : c)
                if (p.loaded())
                    return true;
        }
        return false;
    }

    /**
     * 回归: 战利品标题 tooltip 经 {@code CompatGui.tooltip} 反射调用（v0.6.2 兼容层
     * 重构引入漏传 {@code GuiGraphicsExtractor} 接收者的崩溃）。用真实渲染帧驱动
     * {@link ChestLootWidget#extractRenderState} 命中标题, 断言不再崩。
     */
    private static void assertLootTitleTooltipNoCrash(ClientGameTestContext context) {
        openWorldMap(context);
        context.waitForScreen(GuiMap.class);
        context.waitTicks(20);

        LootTarget target = context.computeOnClient(SeedMapClientGameTest::findLootStructure);
        if (target == null) {
            throw new AssertionError("no loot-producing structure found for loot tooltip test");
        }

        int[] widgetPos = context.computeOnClient(client -> {
            // 平铺详情: 悬停即自动固定 → 鼠标离开图标后 widget 常驻
            ServerConfig.setLootPreviewEnabled(true);
            ServerConfig.setLootDisplayMode(LootDisplayMode.TILED_DETAIL);
            LootPreviewState.onHover(target.type(), target.blockX(), target.blockZ(),
                    100, 100, client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight());
            ChestLootWidget widget = LootPreviewState.widget();
            if (widget == null) {
                throw new AssertionError("loot widget not built for " + target);
            }
            return new int[] { widget.getX(), widget.getY() };
        });

        // 标题左上角 = widget.x + 8, widget.y + 6 (renderContainer 内 minX/minY); +2 落在字内
        double guiScale = context.computeOnClient(
                client -> (double) client.getWindow().getWidth() / client.getWindow().getGuiScaledWidth());
        context.getInput().setCursorPos((widgetPos[0] + 10) * guiScale, (widgetPos[1] + 8) * guiScale);
        context.waitTicks(5);

        context.runOnClient(client -> {
            Object screen = CompatGui.currentScreen(client);
            if (screen == null)
                throw new AssertionError("cannot read current screen via compat layer");
            if (!(screen instanceof GuiMap))
                throw new AssertionError("client left map (crash) during loot title tooltip: " + screen);
            if (!LootPreviewState.hasWidget()) {
                throw new AssertionError("loot widget disappeared during title hover");
            }
        });
        context.takeScreenshot("loot-title-tooltip");
        LOGGER.info("loot title tooltip E2E assertion passed");

        context.runOnClient(client -> {
            LootPreviewState.close();
            ServerConfig.setLootPreviewEnabled(false);
            ServerConfig.setLootDisplayMode(LootDisplayMode.QUICK_PEEK);
        });
        context.waitTick();
    }

    /**
     * 回归 (issue #30): 详情模式下翻页按钮点击无效 (26.3)。
     * <p>
     * 用真实 MC 输入路径驱动: 详情模式悬停自动固定 widget → 将光标移到"下一页"箭头 →
     * {@code pressMouse(CompatInput.MOUSE_LEFT)} → 断言 {@code chestIndex} 递增。绕过 {@code onHover} 的自动
     * 命中检测 (与 {@link #assertLootTitleTooltipNoCrash} 一致), 但点击仍经
     * {@code MouseHandler → GuiMap.mouseClicked → StructureClickMixin → LootPreviewState}。
     */
    private static void assertLootPaging(ClientGameTestContext context) {
        openWorldMap(context);
        context.waitForScreen(GuiMap.class);
        context.waitTicks(20);

        LootTarget target = context.computeOnClient(client -> {
            ServerConfig.setLootPreviewEnabled(true);
            ServerConfig.setLootDisplayMode(LootDisplayMode.DETAIL);
            return findMultiChestLootStructure(client);
        });
        if (target == null) {
            resetLootState(context);
            LOGGER.info("loot paging E2E skipped: no loot structure with >=2 containers found");
            return;
        }

        int[] widgetPos = context.computeOnClient(client -> {
            // 详情模式: 悬停即自动固定 → 鼠标离开图标后 widget 常驻
            LootPreviewState.onHover(target.type(), target.blockX(), target.blockZ(),
                    100, 100, client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight());
            ChestLootWidget widget = LootPreviewState.widget();
            if (widget == null) {
                throw new AssertionError("loot widget not built for " + target);
            }
            return new int[] { widget.getX(), widget.getY(), widget.getChestDataList().size() };
        });

        double guiScale = context.computeOnClient(
                client -> (double) client.getWindow().getWidth() / client.getWindow().getGuiScaledWidth());
        // "下一页"箭头中心: widget 左上 + (BUTTON_X_OFFSET + BUTTON_WIDTH + BUTTON_WIDTH/2, BUTTON_Y_OFFSET + BUTTON_HEIGHT/2)
        double nextX = widgetPos[0] + 149 + 10 + 5;
        double nextY = widgetPos[1] + 4 + 6;
        context.getInput().setCursorPos(nextX * guiScale, nextY * guiScale);
        context.waitTicks(5);

        context.runOnClient(client -> {
            ChestLootWidget widget = LootPreviewState.widget();
            if (widget == null) {
                throw new AssertionError("loot widget closed before paging click");
            }
            if (widget.getX() != widgetPos[0] || widget.getY() != widgetPos[1]) {
                throw new AssertionError("loot widget moved before click: ("
                        + widget.getX() + "," + widget.getY() + ") expected ("
                        + widgetPos[0] + "," + widgetPos[1] + ")");
            }
            if (widget.getChestIndex() != 0) {
                throw new AssertionError("initial chestIndex != 0: " + widget.getChestIndex());
            }
        });

        context.getInput().pressMouse(CompatInput.MOUSE_LEFT);
        context.waitTicks(3);

        context.runOnClient(client -> {
            ChestLootWidget widget = LootPreviewState.widget();
            if (widget == null) {
                throw new AssertionError("loot widget closed after next-page click");
            }
            if (widget.getChestIndex() != 1) {
                throw new AssertionError("chestIndex after next-page click = "
                        + widget.getChestIndex() + ", expected 1 (size=" + widgetPos[2] + ")");
            }
        });
        context.takeScreenshot("loot-paging-next");

        resetLootState(context);
        LOGGER.info("loot paging E2E assertion passed");
    }

    private static void resetLootState(ClientGameTestContext context) {
        context.runOnClient(client -> {
            LootPreviewState.close();
            ServerConfig.setLootPreviewEnabled(false);
            ServerConfig.setLootDisplayMode(LootDisplayMode.QUICK_PEEK);
        });
        context.waitTick();
    }

    /** 找一个战利品容器数 >= 2 的结构 (翻页测试需要多容器)。 */
    private static @Nullable LootTarget findMultiChestLootStructure(Minecraft client) {
        for (var entry : StructureCache.REGIONS.entrySet()) {
            var type = StructureTypes.byId(entry.getKey());
            if (type == null || !LootPreviewState.isLootSupported(type)) {
                continue;
            }
            for (var pos : entry.getValue()) {
                if (!pos.loaded()) {
                    continue;
                }
                var loot = LootPreviewState.queryLoot(type, pos.blockX(), pos.blockZ());
                if (loot != null && loot.size() >= 2) {
                    return new LootTarget(type, pos.blockX(), pos.blockZ());
                }
            }
        }
        // 退化: 栅格扫描附近的沙漠神殿 (4 个箱子) / 丛林神庙
        final LootTarget[] holder = new LootTarget[1];
        for (var type : new StructureType[] { StructureType.DESERT_PYRAMID, StructureType.JUNGLE_PYRAMID }) {
            if (holder[0] != null) {
                break;
            }
            final int prx = Math.floorDiv((int) client.player.getX(), 512);
            final int prz = Math.floorDiv((int) client.player.getZ(), 512);
            Xsm.queryRegionStructuresGrid(type.id,
                    prx - 12, prz - 12, prx + 12, prz + 12, 0, 0, 0, 0,
                    (x, z, found, bx, bz, variant) -> {
                        if (holder[0] != null || !found) {
                            return;
                        }
                        var loot = LootPreviewState.queryLoot(type, bx, bz);
                        if (loot != null && loot.size() >= 2) {
                            holder[0] = new LootTarget(type, bx, bz);
                        }
                    });
        }
        return holder[0];
    }

    /** 找一个能产出非空战利品的结构 (真实位置优先, 退化为栅格扫描沙漠神殿)。 */
    private static @Nullable LootTarget findLootStructure(Minecraft client) {
        for (var entry : StructureCache.REGIONS.entrySet()) {
            var type = StructureTypes.byId(entry.getKey());
            if (type == null || !LootPreviewState.isLootSupported(type)) {
                continue;
            }
            for (var pos : entry.getValue()) {
                if (!pos.loaded()) {
                    continue;
                }
                var loot = LootPreviewState.queryLoot(type, pos.blockX(), pos.blockZ());
                if (loot != null && !loot.isEmpty()) {
                    return new LootTarget(type, pos.blockX(), pos.blockZ());
                }
            }
        }
        final int prx = Math.floorDiv((int) client.player.getX(), 512);
        final int prz = Math.floorDiv((int) client.player.getZ(), 512);
        final LootTarget[] holder = new LootTarget[1];
        Xsm.queryRegionStructuresGrid(StructureType.DESERT_PYRAMID.id,
                prx - 8, prz - 8, prx + 8, prz + 8, 0, 0, 0, 0,
                (x, z, found, bx, bz, variant) -> {
                    if (holder[0] != null || !found) {
                        return;
                    }
                    var loot = LootPreviewState.queryLoot(StructureType.DESERT_PYRAMID, bx, bz);
                    if (loot != null && !loot.isEmpty()) {
                        holder[0] = new LootTarget(StructureType.DESERT_PYRAMID, bx, bz);
                    }
                });
        return holder[0];
    }

    /** 战利品预览测试目标结构。 */
    private record LootTarget(StructureInfo type, int blockX, int blockZ) {
    }

    /** REGIONS 中离玩家最近且已加载的结构。 */
    private static StructureTarget nearestStructure(Minecraft client) {
        double px = client.player.getX();
        double pz = client.player.getZ();
        StructureTarget nearest = null;
        double best = Double.MAX_VALUE;
        for (var entry : StructureCache.REGIONS.entrySet()) {
            // REGIONS key = 结构 id; 清理后的自定义 id 残留可能已不在注册表
            var type = StructureTypes.byId(entry.getKey());
            if (type == null)
                continue;
            for (var pos : entry.getValue()) {
                if (!pos.loaded())
                    continue;
                double d = Math.hypot(pos.blockX() + 0.5 - px, pos.blockZ() + 0.5 - pz);
                if (d < best) {
                    best = d;
                    nearest = new StructureTarget(pos.blockX(), pos.blockZ(), type, pos.getVariant());
                }
            }
        }
        return nearest;
    }

    /** 把玩家传送到 (blockX, blockZ) 附近并等落地。 */
    private static void teleportNear(ClientGameTestContext context, TestSingleplayerContext singleplayer,
            int blockX, int blockZ) {
        singleplayer.getServer().runOnServer(server -> {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                throw new AssertionError("no player on server");
            }
            players.get(0).teleportTo(blockX + 0.5, 200.0, blockZ + 0.5);
        });
        waitForChunksRender(singleplayer);
        context.waitFor(client -> client.player.distanceToSqr(blockX + 0.5, client.player.getY(), blockZ + 0.5) < 4.0, 200);
        context.waitFor(client -> client.player.getDeltaMovement().lengthSqr() < 0.05, 300);
        context.waitTicks(5);
    }
}
