package bid.yuanlu.seedmap4xaero.client.configs.datapack;

import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile.Paths;
import bid.yuanlu.seedmap4xaero.client.mixin.WorldSwitchMixin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import xaero.map.MapProcessor;

/**
 * {@code datapack_imports.json} 门面：手动导入数据包路径列表的生命周期与读写。
 * <p>
 * per-mainId（每个多人服务器/单机世界根独立，与 {@code server_config.json} 同级）；
 * 由 {@link WorldSwitchMixin} 在世界切换时 activate/deactivate。
 * 列表语义见 {@link DatapackImportsData}；外部交互（校验/reload 反馈）统一走
 * {@code client.datapack.DatapackImports} 内核，本类只管存取。
 */
public final class DatapackImportsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/DatapackImportsConfig");
    private static final String CONFIG_FILE = "datapack_imports.json";

    private static volatile @Nullable String activeMainId;
    private static volatile @Nullable DatapackImportsData activeData;

    private DatapackImportsConfig() {
    }

    private static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static Paths paths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, CONFIG_FILE, CONFIG_FILE);
    }

    public static @Nullable String activeMainId() {
        return activeMainId;
    }

    /**
     * 激活与 {@code mp} 对应的世界配置（由 {@link WorldSwitchMixin} 在世界切换时调用）。
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
        if (Objects.equals(activeMainId, mainId))
            return;
        LOGGER.info("activate: switching {} -> {}", activeMainId, mainId);
        save();
        activeMainId = mainId;
        activeData = load(mainId);
    }

    /** 停用当前世界，写回脏数据并清空缓存。 */
    public synchronized static void deactivate() {
        if (activeMainId == null)
            return;
        save();
        activeMainId = null;
        activeData = null;
    }

    /** 当前导入路径列表（add 顺序 = 优先级递增）；未激活世界返回空列表。 */
    public static List<String> entries() {
        final var data = activeData;
        return data != null ? data.imports() : List.of();
    }

    /** 追加导入路径（去重）；未激活世界返回 false。 */
    public static boolean add(String path) {
        final var data = activeData;
        return data != null && data.add(path);
    }

    /** 移除指定下标的导入路径；未激活世界/越界返回 false。 */
    public static boolean remove(int index) {
        final var data = activeData;
        return data != null && data.remove(index);
    }

    /**
     * 生命周期保存（rotate=true，仅 deactivate 等检查点调用）。
     */
    public synchronized static void save() {
        saveCurrent(true);
    }

    /** 会话内刷写（rotate=false）。add/remove 后由调用方（内核）触发。 */
    public synchronized static void flush() {
        saveCurrent(false);
    }

    private synchronized static void saveCurrent(boolean rotate) {
        final var mainId = activeMainId;
        final var data = activeData;
        if (mainId == null)
            return;
        if (data == null || !data.dirty.compareAndSet(true, false))
            return;
        if (!saveConfig(baseDir(), mainId, data, rotate))
            data.dirty.set(true); // 写盘失败恢复脏标志, 本轮变更不丢
    }

    /** base 参数独立注入以便单元测试（不依赖 Minecraft 客户端）。 */
    static boolean saveConfig(Path base, String mainId, DatapackImportsData data, boolean rotate) {
        try {
            JsonConfigFile.save(paths(base, mainId), data, DatapackImportsData.JSON_CODEC, rotate);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save datapack imports config for {}", mainId, e);
            return false;
        }
    }

    private static DatapackImportsData load(String mainId) {
        return loadConfig(baseDir(), mainId);
    }

    /** base 参数独立注入以便单元测试。无 legacy 前身（新文件，回退链 .json → .json.old → 新建）。 */
    static DatapackImportsData loadConfig(Path base, String mainId) {
        return JsonConfigFile.load(paths(base, mainId), new DatapackImportsData(),
                DatapackImportsData.JSON_CODEC, null);
    }
}
