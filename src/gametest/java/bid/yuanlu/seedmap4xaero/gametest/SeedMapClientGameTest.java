package bid.yuanlu.seedmap4xaero.gametest;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache.CellKey;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.mixin.SeedMapMixin;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.HighlightHudRenderer;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;

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
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(ui -> ui.setSeed(Long.toString(KNOWN_SEED)))
                .create()) {
            waitForChunksRender(singleplayer);
            context.waitTick();

            assertSeedResolved(context, singleplayer);

            openWorldMap(context);
            context.waitForScreen(GuiMap.class);

            // 等若干 tick 让 tickWorldInfo → CellCache 生成链路跑起来
            context.waitTicks(20);

            assertMapActivated();
            assertCellCachePopulated(context);
            assertStructureCachePopulated(context);

            context.takeScreenshot("seed-map-final");

            assertHighlightHudHiddenWhenBehind(context, singleplayer);

            LOGGER.info("seed-map E2E assertions passed");

            // 绕开 close() 死锁：MC 26 的 IntegratedServer.halt 先 executeBlocking 等 server 处理停止
            // 任务，而 fabric client gametest 的 phaser 让 server 卡在 postRunTasks 等 render arrive。
            // 在 server 线程内主动 halt（executeBlocking 检测当前线程即 server，直接执行不阻塞），
            // 使 server 先脱离 phaser 协调，之后 close() 的 disconnect 才能顺利完成。
            singleplayer.getServer().runOnServer(server -> server.halt(false));
        }
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

    /** 要高亮的真实结构位置。 */
    private record StructureTarget(int blockX, int blockZ, StructureType type, int variant) {
    }

    /**
     * 回归: 26.2 线性深度约定下背后结构 NDC.z&lt;0 曾漏过旧的 z&gt;1 隐藏检查 (镜像图标)。
     * 真实高亮一个结构 → 正视必绘制 (blits==1), 转向水平镜像点 (结构恰好正背后) 必隐藏 (blits==0)。
     */
    private static void assertHighlightHudHiddenWhenBehind(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        StructureTarget target = context.computeOnClient(client -> nearestStructure(client));
        if (target == null) {
            throw new AssertionError("no loaded structure in StructureCache.REGIONS to highlight");
        }
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
        context.takeScreenshot("highlight-hud-facing");

        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(2.0 * client.player.getX() - (target.blockX() + 0.5),
                        client.player.getEyeY(),
                        2.0 * client.player.getZ() - (target.blockZ() + 0.5))));
        context.waitFor(client -> HighlightHudRenderer.lastFrameHighlightBlits == 0, 100);
        context.takeScreenshot("highlight-hud-behind");

        context.runOnClient(client -> HighlightedStructures.clear());
        LOGGER.info("highlight-hud E2E assertions passed");
    }

    /** REGIONS 中离玩家最近且已加载的结构。 */
    private static StructureTarget nearestStructure(Minecraft client) {
        double px = client.player.getX();
        double pz = client.player.getZ();
        StructureTarget nearest = null;
        double best = Double.MAX_VALUE;
        for (var entry : StructureCache.REGIONS.entrySet()) {
            for (var pos : entry.getValue()) {
                if (!pos.loaded())
                    continue;
                double d = Math.hypot(pos.blockX() + 0.5 - px, pos.blockZ() + 0.5 - pz);
                if (d < best) {
                    best = d;
                    nearest = new StructureTarget(pos.blockX(), pos.blockZ(), entry.getKey(), pos.getVariant());
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
