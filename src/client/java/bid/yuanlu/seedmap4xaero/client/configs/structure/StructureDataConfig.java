package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.IOException;
import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import net.minecraft.client.Minecraft;

import xaero.map.MapProcessor;

/**
 * structure_data.sm4x 持久标记的门面类，镜像 {@link ServerConfig} 的生命周期：
 * activate/deactivate/save + 原子写 + 损坏回退，磁盘 IO 全部复用 {@link Sm4xFile}。
 * <p>
 * 标记与 (seed, mwId, 结构类型, key) 绑定；key = 结构方块坐标
 * （cubiomes 结构为 2D，同类型下方块坐标唯一），见 {@link #keyOf}。
 */
public final class StructureDataConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureDataConfig");
    private static final String CONFIG_FILE = "structure_data.sm4x";

    /** 结构持久 key: (blockX<<32)|blockZ。cubiomes 结构只有 2D 坐标, 无 Y。 */
    public static long keyOf(int blockX, int blockZ) {
        return (long) blockX << 32 | (blockZ & 0xFFFFFFFFL);
    }

    private static volatile @Nullable String activeMainId;
    private static volatile @Nullable MapProcessor activeMapProcessor;
    private static volatile @Nullable StructureData activeData;

    private StructureDataConfig() {
    }

    private static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static Sm4xFile.Sm4xPaths pathsFor(Path base, String mainId) {
        return Sm4xFile.pathsFor(base, mainId, CONFIG_FILE);
    }

    /**
     * 激活与 {@code mp} 对应的结构标记文档。由 {@link ServerConfig} 相同的生命周期
     * 钩子并排调用 (世界切换 / GuiMap init / 断开连接)。
     */
    public synchronized static void activate(@Nullable MapProcessor mp) {
        if (mp == null) {
            deactivate();
            return;
        }
        String mainId = mp.getCurrentWorldId();
        if (mainId == null && mp.getMapWorld() != null) {
            mainId = mp.getMapWorld().getMainId();
        }
        if (mainId == null)
            return;
        if (!mainId.equals(activeMainId)) {
            save();
            LOGGER.info("activate: switching {} -> {}", activeMainId, mainId);
            activeMainId = mainId;
            activeData = load(baseDir(), mainId);
        }
        activeMapProcessor = mp;
    }

    /** 停用当前世界，写回脏数据并清空缓存。 */
    public synchronized static void deactivate() {
        save();
        activeMainId = null;
        activeMapProcessor = null;
        activeData = null;
    }

    public static @Nullable StructureData getActiveData() {
        return activeData;
    }

    /** 当前 Xaero 维度 (mwId)；null = 未激活。 */
    public static @Nullable String activeMwId() {
        var mp = activeMapProcessor;
        return mp != null ? mp.getCurrentMWId() : null;
    }

    /** 当前种子 (复用 {@link ServerConfig#resolveSeed})；null = 未知。 */
    public static @Nullable Long activeSeed() {
        return activeData != null ? ServerConfig.resolveSeed() : null;
    }

    public synchronized static void save() {
        saveCurrent(true);
    }

    /**
     * 主动刷写: 用户设置分组等关键操作后立即落盘。
     * <p>
     * 跳过 {@code .old} 轮替 ({@code rotate=false})——高频刷写不滚动覆盖 .old，
     * 让 .old 始终保留"上次世界切换时的完整备份"。
     */
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
        try {
            Sm4xFile.save(pathsFor(baseDir(), mainId), data, StructureData.CODEC, rotate);
        } catch (IOException e) {
            LOGGER.error("Failed to save structure data for {}", mainId, e);
        }
    }

    /** base 参数独立注入以便单元测试。 */
    static StructureData load(Path base, String mainId) {
        return Sm4xFile.load(pathsFor(base, mainId), new StructureData(), StructureData.CODEC);
    }

    // ─── 单测辅助 (包私有, 不触碰 Minecraft) ────────────────────

    static void saveLoadForTest(Path base, String mainId, StructureData data) throws IOException {
        Sm4xFile.save(pathsFor(base, mainId), data, StructureData.CODEC);
    }

    static Path targetPathForTest(Path base, String mainId) {
        return pathsFor(base, mainId).target();
    }

    /** 模拟主动刷写 (rotate=false)，供单测验证 .old 不被轮替。 */
    static void flushForTest(Path base, String mainId, StructureData data) throws IOException {
        Sm4xFile.save(pathsFor(base, mainId), data, StructureData.CODEC, false);
    }

    static Sm4xFile.Sm4xPaths pathsForTest(Path base, String mainId) {
        return pathsFor(base, mainId);
    }

    // ─── 便捷访问 (基于当前激活的 seed + mwId) ───────────────────

    private static @Nullable StructureData.SeedData activeSeedData() {
        final var data = activeData;
        final var seed = activeSeed();
        if (data == null || seed == null)
            return null;
        final var mwId = activeMwId();
        if (mwId == null)
            return null;
        return data.getSeed(seed);
    }

    /** 当前 (seed, mwId) 的维度标记表；未激活/无记录返回 null。 */
    public static @Nullable StructureData.DimData activeDimData() {
        var sd = activeSeedData();
        return sd == null ? null : sd.getDim(activeMwId());
    }

    /** 查询当前 (seed, mwId) 下某结构的标记；无记录/未激活返回 null。 */
    public static @Nullable StructureMark getMark(StructureType type, long key) {
        var dd = activeDimData();
        return dd == null ? null : dd.getMark(type.id, key);
    }

    /** 记录访问 (历史最小距离)；未激活/种子未知时忽略。 */
    public static void markVisited(StructureType type, long key, int dist) {
        final var data = activeData;
        final var seed = activeSeed();
        final var mwId = activeMwId();
        if (data == null || seed == null || mwId == null)
            return;
        data.getOrCreateSeed(seed).getOrCreateDim(mwId).markVisited(type.id, key, dist);
    }

    /** 设置分组 ({@code null}/默认组 = 清除记录)；未激活/种子未知时忽略。 */
    public static void setGroup(StructureType type, long key, @Nullable String group) {
        final var data = activeData;
        final var seed = activeSeed();
        final var mwId = activeMwId();
        if (data == null || seed == null || mwId == null)
            return;
        data.getOrCreateSeed(seed).getOrCreateDim(mwId).setGroup(type.id, key, group);
    }

    /** 组是否被隐藏 (面板 checkbox)；未激活返回 false。 */
    public static boolean isGroupHidden(@NotNull String group) {
        var data = activeData;
        return data != null && data.isGroupHidden(group);
    }

    public static void setGroupHidden(@NotNull String group, boolean hidden) {
        var data = activeData;
        if (data != null)
            data.setGroupHidden(group, hidden);
    }

    // ─── /sm4x 命令入口 ─────────────────────────────────────────

    /**
     * 删除某种子的全部历史数据并立即落盘（不轮替 .old）。
     *
     * @return 删除的记录数; -1 = 该种子正在使用中（拒绝删除）; 0 = 无数据
     */
    public synchronized static int removeSeedForCommand(long seed) {
        final var data = activeData;
        if (data == null)
            return 0;
        final var active = activeSeed();
        if (active != null && active == seed)
            return -1;
        int n = data.removeSeed(seed);
        if (n > 0)
            flush();
        return n;
    }
}
