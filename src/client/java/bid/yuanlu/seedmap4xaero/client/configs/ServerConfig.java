package bid.yuanlu.seedmap4xaero.client.configs;

import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.mixin.WorldSwitchMixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import xaero.map.MapProcessor;

/**
 * 种子地图持久化配置的门面类。
 * <p>
 * 职责：
 * <ul>
 * <li>维护当前活动的 {@code mainId}（即 XWM 的世界根标识，如 {@code Multiplayer_192.168.1.1}）
 * <li>懒加载对应的 {@link ConfigData} 并缓存
 * <li>提供 {@link #resolveSeed} / {@link #setSeed} 等方法
 * <li>原子写入
 * {@code gameDir/xaero/seed-map-for-xaero/&lt;mainId&gt;/server_config.json}
 * </ul>
 * <p>
 * 所有方法设计为在 Minecraft 渲染线程调用，暂无需额外同步。
 */
public final class ServerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/ServerConfig");
    private static final String CONFIG_FILE = "server_config.sm4x";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String OLD_SUFFIX = ".old";

    private static @Nullable String activeMainId;
    private static @Nullable MapProcessor activeMapProcessor;
    private static @Nullable ConfigData activeConfig;

    private ServerConfig() {
    }

    /** {@code gameDir/xaero/seed-map-for-xaero} */
    private static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static Path worldDir(String mainId) {
        return baseDir().resolve(mainId);
    }

    private static Path targetFile(String mainId) {
        return worldDir(mainId).resolve(CONFIG_FILE);
    }

    private static Path tmpFile(String mainId) {
        return worldDir(mainId).resolve(CONFIG_FILE + TMP_SUFFIX);
    }

    private static Path oldFile(String mainId) {
        return worldDir(mainId).resolve(CONFIG_FILE + OLD_SUFFIX);
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

    public static @Nullable ConfigData getActiveConfig() {
        return activeConfig;
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
        var cfg = activeConfig;
        var mp = activeMapProcessor;
        if (cfg == null || mp == null)
            return null;
        var wc = cfg.getWorld(mp.getCurrentMWId());
        if (wc == null)
            return null;
        return wc.seed();
    }

    /**
     * 立即将当前配置原子写入磁盘。
     * <p>
     * 流程：
     * <ol>
     * <li>将 {@code cached} 序列化写入 {@code server_config.json.tmp}
     * <li>若 {@code server_config.json} 存在，移动到 {@code server_config.json.old}
     * <li>将 {@code .tmp} 移动到 {@code .json}（尽力原子操作）
     * </ol>
     */
    public synchronized static void save() {
        final var mainId = activeMainId;
        final var cfg = activeConfig;
        if (mainId == null)
            return;
        if (cfg == null || !cfg.dirty.compareAndSet(true, false)) {
            return;
        }
        try {
            var dir = worldDir(mainId);
            Files.createDirectories(dir);

            var target = targetFile(mainId);
            var tmp = tmpFile(mainId);
            var old = oldFile(mainId);

            // 1. 写入临时文件
            cfg.write(tmp);

            // 2. 轮替旧文件
            if (Files.exists(target)) {
                Files.move(target, old, StandardCopyOption.REPLACE_EXISTING);
            }

            // 3. 提交
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            LOGGER.error("Failed to save config for {}", mainId, e);
        }
    }

    // ─── 内部加载 ───────────────────────────────────────────────

    /**
     * 从磁盘加载配置，文件不存在时返回空配置。
     */
    private static ConfigData load(String mainId) {
        final var target = targetFile(mainId);
        if (Files.exists(target)) {
            try {
                return ConfigData.read(target);
            } catch (IOException e) {
                LOGGER.error("Failed to load config for {}, try load old config instead", mainId, e);
                try {
                    Files.deleteIfExists(target);
                } catch (Throwable ignored) {
                }
            }
        }
        final var old = oldFile(mainId);
        if (Files.exists(old)) {
            try {
                return ConfigData.read(old);
            } catch (IOException e) {
                LOGGER.error("Failed to load old config for {}, create new config instead", mainId, e);
            }
        }
        return new ConfigData();
    }
}
