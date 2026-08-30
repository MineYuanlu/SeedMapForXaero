package bid.yuanlu.seedmap4xaero.client.configs.basic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile.Sm4xPaths;
import bid.yuanlu.seedmap4xaero.client.mixin.WorldSwitchMixin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import xaero.map.MapProcessor;

/**
 * basic 配置（{@code server_config.sm4x}）的门面类。
 * <p>
 * 职责：
 * <ul>
 * <li>维护当前活动的 {@code mainId}（即 XWM 的世界根标识，如 {@code Multiplayer_192.168.1.1}）
 * <li>懒加载对应的 {@link ConfigData} 并缓存
 * <li>提供 {@link #resolveSeed} 等方法与各配置项的便捷读写
 * <li>通过 {@link Sm4xFile} 原子写入
 * {@code gameDir/xaero/seed-map-for-xaero/&lt;mainId&gt;/server_config.sm4x}
 * </ul>
 * <p>
 * 磁盘 IO（magic/版本帧、.tmp/.old 轮替、损坏回退）在 {@code core.Sm4xFile}；
 * 本类只保留 basic 文档的文件名与生命周期。新增其他 .sm4x 配置文件时新建自己的包，
 * 复用 {@code Sm4xCodec} + {@code Sm4xFile} 即可。
 */
public final class ServerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/ServerConfig");
    private static final String CONFIG_FILE = "server_config.sm4x";

    private static volatile @Nullable String activeMainId;
    private static volatile @Nullable MapProcessor activeMapProcessor;
    private static volatile @Nullable ConfigData activeConfig;

    private ServerConfig() {
    }

    /** {@code gameDir/xaero/seed-map-for-xaero} */
    private static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static Sm4xPaths paths(Path base, String mainId) {
        return Sm4xFile.pathsFor(base, mainId, CONFIG_FILE);
    }

    public static @Nullable String activeMainId() {
        return activeMainId;
    }

    /**
     * 激活与 {@code mp} 对应的世界配置。
     * <p>
     * 缓存 {@link MapProcessor} 引用供 {@link #resolveSeed} 使用，提取 {@code mainId}，
     * 保存旧脏数据并加载新配置。由 {@link WorldSwitchMixin} 在检测到
     * {@code getCurrentWorldId()} 变化时调用（Xaero 处理线程，非渲染线程）。
     */
    public synchronized static void activate(MapProcessor mp) {
        if (mp == null) {
            deactivate();
            return;
        }
        String mainId = mp.getCurrentWorldId();
        if (mainId == null && mp.getMapWorld() != null) {
            mainId = mp.getMapWorld().getMainId();
        }
        if (Objects.equals(activeMainId, mainId)) {
            activeMapProcessor = mp;
            return;
        }
        LOGGER.info("activate: switching {} -> {} (config={})", activeMainId, mainId, activeConfig != null);
        save();
        activeMainId = mainId;
        activeMapProcessor = mp;
        activeConfig = load(mainId);
    }

    /**
     * 停用当前世界，写回脏数据并清空缓存。
     */
    public synchronized static void deactivate() {
        if (activeMainId == null) {
            return;
        }
        save();
        activeMainId = null;
        activeMapProcessor = null;
        activeConfig = null;
    }

    /** 获取当前游玩的服务器配置。 */
    public static @Nullable ConfigData getActiveConfig() {
        return activeConfig;
    }

    public static boolean isStructureEnabled() {
        var cfg = activeConfig;
        return cfg == null || !cfg.isInvisibleStructures();
    }

    public static void setStructureEnabled(boolean enabled) {
        var cfg = activeConfig;
        if (cfg != null)
            cfg.setInvisibleStructures(!enabled);
    }

    public static float getStructureIconSize() {
        var cfg = activeConfig;
        return cfg != null ? cfg.getStructureIconSize() : 1.0f;
    }

    public static void setStructureIconSize(float size) {
        var cfg = activeConfig;
        if (cfg != null)
            cfg.setStructureIconSize(size);
    }

    public static boolean isLootPreviewEnabled() {
        var cfg = activeConfig;
        return cfg != null && cfg.isLootPreview();
    }

    public static void setLootPreviewEnabled(boolean enabled) {
        var cfg = activeConfig;
        if (cfg != null)
            cfg.setLootPreview(enabled);
    }

    public static LootDisplayMode getLootDisplayMode() {
        var cfg = activeConfig;
        return cfg != null ? cfg.getLootDisplayMode() : LootDisplayMode.QUICK_PEEK;
    }

    public static void setLootDisplayMode(LootDisplayMode mode) {
        var cfg = activeConfig;
        if (cfg != null)
            cfg.setLootDisplayMode(mode);
    }

    /** 获取当前世界配置。 */
    public static @Nullable WorldConfig getActiveWorldConfig() {
        var cfg = activeConfig;
        var mp = activeMapProcessor;
        if (cfg == null || mp == null)
            return null;
        var wc = cfg.getOrCreateWorld(mp.getCurrentMWId());
        return wc;
    }

    /**
     * 解析当前玩家所在的世界的种子。
     * <p>
     * 单机：直接返回服务端种子。多人：从当前配置按 (dimId, mwId) 查询。
     *
     * @return 种子值，或 {@code null} 表示未设置
     */
    public static @Nullable Long resolveSeed() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            try {
                return server.getWorldGenSettings().options().seed();
            } catch (Exception e) {
                return null;
            }
        }
        var wc = getActiveWorldConfig();
        return wc != null ? wc.seed() : null;
    }

    /**
     * 解析当前玩家所在的世界的维度ID。
     * 
     * @return {@link Integer#MIN_VALUE} 代表未知, {@code 0}代表主世界, {@code -1}代表下界,
     *         {@code 1}代表末地
     */
    public static int resolveDimId() {
        var mp = activeMapProcessor;
        if (mp == null)
            return Integer.MIN_VALUE;
        try {
            final var dimKey = mp.getMapWorld().getCurrentDimension().getDimId();
            if (dimKey == Level.OVERWORLD) {
                return 0;
            } else if (dimKey == Level.NETHER) {
                return -1;
            } else if (dimKey == Level.END) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 立即将当前配置原子写入磁盘（仅脏时）。流程见 {@link Sm4xFile#save}。
     */
    public synchronized static void save() {
        final var mainId = activeMainId;
        final var cfg = activeConfig;
        if (mainId == null)
            return;
        if (cfg == null || !cfg.dirty.compareAndSet(true, false)) {
            return;
        }
        saveConfig(baseDir(), mainId, cfg);
    }

    /**
     * 将 {@code cfg} 原子写入 {@code base/<mainId>/server_config.sm4x}。
     * base 参数独立注入以便单元测试 (不依赖 Minecraft 客户端)。
     */
    static void saveConfig(Path base, String mainId, ConfigData cfg) {
        try {
            Sm4xFile.save(paths(base, mainId), cfg, ConfigData.CODEC);
        } catch (IOException e) {
            LOGGER.error("Failed to save config for {}", mainId, e);
        }
    }

    // ─── 内部加载 ───────────────────────────────────────────────

    /**
     * 从磁盘加载配置，文件不存在时返回空配置。
     */
    private static ConfigData load(String mainId) {
        return loadConfig(baseDir(), mainId);
    }

    /**
     * 从 {@code base/&lt;mainId>} 加载配置: 主文件 → 损坏则删主文件回退 {@code .old} →
     * 损坏或不存在则新建。base 参数独立注入以便单元测试。
     */
    static ConfigData loadConfig(Path base, String mainId) {
        return Sm4xFile.load(paths(base, mainId), new ConfigData(), ConfigData.CODEC);
    }
}
