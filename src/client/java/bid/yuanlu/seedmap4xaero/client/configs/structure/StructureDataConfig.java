package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureInfo;
import net.minecraft.client.Minecraft;

import xaero.map.MapProcessor;

/**
 * 结构持久化门面，镜像 {@link ServerConfig} 的生命周期：
 * activate/deactivate/save/flush，磁盘 IO 分两份——
 * <ul>
 * <li>{@code structure_settings.json}：组可见性 + 用户组（{@link StructureData}）
 * <li>{@code marks/<seed>/<mwId>/r.<x>.<z>.json}：结构标记分片（{@link MarksStore}）
 * </ul>
 * 标记与 (seed, mwId, 结构类型, key) 绑定；key = 结构方块坐标
 * （cubiomes 结构为 2D，同类型下方块坐标唯一），见 {@link #keyOf}。
 * <p>
 * 旧版单文件 {@code structure_data.sm4x} 在加载链发现时自动向上迁移
 * （{@link StructureDataLegacy} 解码 → 设置落盘 + 标记分片 → 改名 .legacy）。
 */
public final class StructureDataConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureDataConfig");
    private static final String SETTINGS_FILE = "structure_settings.json";
    private static final String LEGACY_FILE = "structure_data.sm4x";
    private static final String MARKS_DIR = "marks";

    /** 结构持久 key: (blockX<<32)|blockZ。cubiomes 结构只有 2D 坐标, 无 Y。 */
    public static long keyOf(int blockX, int blockZ) {
        return (long) blockX << 32 | (blockZ & 0xFFFFFFFFL);
    }

    private static volatile @Nullable String activeMainId;
    private static volatile @Nullable MapProcessor activeMapProcessor;
    private static volatile @Nullable StructureData activeData; // 设置文档
    private static volatile @Nullable MarksStore marksStore;

    private StructureDataConfig() {
    }

    private static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("xaero")
                .resolve("seed-map-for-xaero");
    }

    private static JsonConfigFile.Paths settingsPaths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, SETTINGS_FILE, LEGACY_FILE);
    }

    /**
     * 激活与 {@code mp} 对应的结构持久化。由 {@link ServerConfig} 相同的生命周期
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
            marksStore = new MarksStore(baseDir().resolve(mainId).resolve(MARKS_DIR));
        }
        activeMapProcessor = mp;
    }

    /** 停用当前世界，写回脏数据并清空缓存。 */
    public synchronized static void deactivate() {
        save();
        activeMainId = null;
        activeMapProcessor = null;
        activeData = null;
        marksStore = null;
    }

    /**
     * 设置文档（组可见性/用户组）——{@code StructureGroups.colorOf} 等消费者用。
     * 标记查询请走 {@link #activeDimData()} / {@link #getMark}。
     */
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
        return activeMainId != null ? ServerConfig.resolveSeed() : null;
    }

    /** 生命周期保存（rotate=true，仅世界切换等读取验证场景调用）。 */
    public synchronized static void save() {
        saveCurrent(true);
    }

    /** 会话内主动刷写（rotate=false，不轮替 .old）。 */
    public synchronized static void flush() {
        saveCurrent(false);
    }

    private synchronized static void saveCurrent(boolean rotate) {
        final var mainId = activeMainId;
        final var data = activeData;
        final var marks = marksStore;
        if (mainId == null)
            return;
        // 设置文档: CAS-claim, 写盘失败恢复脏标志
        if (data != null && data.dirty.compareAndSet(true, false)) {
            try {
                JsonConfigFile.save(settingsPaths(baseDir(), mainId), data,
                        StructureData.JSON_CODEC, rotate);
            } catch (IOException e) {
                LOGGER.error("Failed to save structure settings for {}", mainId, e);
                data.dirty.set(true);
            }
        }
        // 标记分片: 仅写脏 region (内部按成功与否保持脏标志)
        if (marks != null)
            marks.flush(rotate);
    }

    /**
     * 加载设置文档（含 legacy structure_data.sm4x 自动向上迁移：
     * 设置落盘 + 标记经 {@link MarksStore#importSnapshot} 分片 + legacy 改名 .legacy）。
     * base 参数独立注入以便单元测试。
     */
    static StructureData load(Path base, String mainId) {
        var paths = settingsPaths(base, mainId);
        var preloaded = migrateLegacyIfNeeded(base, mainId, paths);
        if (preloaded != null)
            return preloaded;
        return JsonConfigFile.load(paths, new StructureData(), StructureData.JSON_CODEC, null);
    }

    /**
     * 发现 legacy 文件则迁移；返回解码出的设置（供本次会话直接使用），无 legacy
     * 或 legacy 损坏返回 null（继续正常加载链）。
     * <p>
     * 失败语义与 {@code JsonConfigFile.tryLegacy} 一致：解码失败（损坏）返回
     * null 回退链；<b>落盘/分片失败不丢数据</b>——仍返回解码出的设置供会话使用，
     * legacy 不改名，下次启动重试迁移。
     */
    private static @Nullable StructureData migrateLegacyIfNeeded(Path base, String mainId,
            JsonConfigFile.Paths paths) {
        Path legacy = null;
        if (Files.exists(paths.legacy()))
            legacy = paths.legacy();
        else if (Files.exists(paths.legacyOld()))
            legacy = paths.legacyOld();
        if (legacy == null)
            return null;
        final StructureDataLegacy.Snapshot snap;
        final StructureData settings;
        try {
            snap = Sm4xFile.readFrame(legacy, StructureDataLegacy.LEGACY_CODEC);
            settings = new StructureData();
            for (String g : snap.hiddenGroups())
                settings.setGroupHidden(g, true);
            for (StructureData.UserGroup ug : snap.userGroups()) {
                if (StructureGroups.isBuiltin(ug.name()))
                    settings.setGroupColor(ug.name(), ug.color()); // 内置组 = 颜色覆盖条目
                else
                    settings.addGroup(ug.name(), ug.color()); // 用户组 = 新建条目
            }
            settings.dirty.set(false); // 刚构造, 迁移写出后再由保存流程管理
        } catch (IOException e) {
            LOGGER.error("Failed to load legacy structure data {}", legacy, e);
            return null;
        }
        try {
            JsonConfigFile.save(paths, settings, StructureData.JSON_CODEC, false);
            new MarksStore(base.resolve(mainId).resolve(MARKS_DIR)).importSnapshot(snap);
            JsonConfigFile.retireLegacy(paths);
            LOGGER.info("Migrated legacy structure data {} -> {} + marks/", legacy, paths.target());
        } catch (IOException e) {
            LOGGER.error("Legacy structure data {} decoded but persisting failed; "
                    + "keeping legacy file for retry, using decoded data this session", legacy, e);
        }
        return settings;
    }

    // ─── 单测辅助 (包私有, 不触碰 Minecraft) ────────────────────

    static JsonConfigFile.Paths settingsPathsForTest(Path base, String mainId) {
        return settingsPaths(base, mainId);
    }

    // ─── 便捷访问 (基于当前激活的 seed + mwId) ───────────────────

    /** 当前 (seed, mwId) 的维度标记表；未激活返回 null（惰性加载分片）。 */
    public static @Nullable MarksStore.DimData activeDimData() {
        final var marks = marksStore;
        final var seed = activeSeed();
        if (marks == null || seed == null)
            return null;
        final var mwId = activeMwId();
        if (mwId == null)
            return null;
        return marks.doc(seed, mwId);
    }

    /** 查询当前 (seed, mwId) 下某结构的标记；无记录/未激活返回 null。 */
    public static @Nullable StructureMark getMark(StructureInfo type, long key) {
        var dd = activeDimData();
        return dd == null ? null : dd.getMark(type.id(), key);
    }

    /** 记录访问 (历史最小距离)；未激活/种子未知时忽略。 */
    public static void markVisited(StructureInfo type, long key, int dist) {
        final var marks = marksStore;
        final var seed = activeSeed();
        final var mwId = activeMwId();
        if (marks == null || seed == null || mwId == null)
            return;
        marks.doc(seed, mwId).markVisited(type.id(), key, dist);
    }

    /** 设置分组 ({@code null}/默认组 = 清除记录)；未激活/种子未知时忽略。 */
    public static void setGroup(StructureInfo type, long key, @Nullable String group) {
        final var marks = marksStore;
        final var seed = activeSeed();
        final var mwId = activeMwId();
        if (marks == null || seed == null || mwId == null)
            return;
        marks.doc(seed, mwId).setGroup(type.id(), key, group);
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

    // ─── 用户组管理 (三阶段; 全部立即落盘) ──────────────────────

    /** 新建用户组; false = 名称非法/重名/未激活。 */
    public synchronized static boolean addGroup(String name, int color) {
        var data = activeData;
        if (data == null || !data.addGroup(name, color))
            return false;
        flush();
        return true;
    }

    /** 设置任意组颜色 (内置组 = 创建覆盖条目); false = 未知组/无变化/未激活。 */
    public synchronized static boolean setGroupColor(String name, int color) {
        var data = activeData;
        if (data == null || !data.setGroupColor(name, color))
            return false;
        flush();
        return true;
    }

    /**
     * 拖拽中的实时颜色预览: 只改内存并标脏, 不落盘
     * (由调用方在编辑器 {@code 完成} 提交或离开编辑器时 {@link #flush()},
     * 避免拖拽期间逐帧写盘)。
     */
    public synchronized static void previewGroupColor(String name, int color) {
        var data = activeData;
        if (data != null)
            data.setGroupColor(name, color);
    }

    /** 恢复内置组默认色 (移除覆盖条目); false = 非内置组/无覆盖/未激活。 */
    public synchronized static boolean clearGroupColor(String name) {
        var data = activeData;
        if (data == null || !data.clearGroupColor(name))
            return false;
        flush();
        return true;
    }

    /** 重命名用户组 (同步重写全部标记引用, 跨全部分片); false = 名称非法/重名/未激活。 */
    public synchronized static boolean renameGroup(String from, String to) {
        var data = activeData;
        if (data == null || !data.renameGroup(from, to))
            return false;
        var marks = marksStore;
        if (marks != null)
            marks.rewriteGroupRefs(from, to);
        flush();
        return true;
    }

    /** 删除用户组 (引用标记保留访问、组清默认); false = 内置组/不存在/未激活。 */
    public synchronized static boolean removeGroup(String name) {
        var data = activeData;
        if (data == null || !data.removeGroup(name))
            return false;
        var marks = marksStore;
        if (marks != null)
            marks.rewriteGroupRefs(name, StructureGroups.DEFAULT);
        flush();
        return true;
    }

    /** 用户组快照 (含内置组颜色覆盖); 未激活返回空。 */
    public static List<StructureData.UserGroup> userGroups() {
        var data = activeData;
        return data != null ? data.userGroups() : List.of();
    }

    // ─── /sm4x 命令入口 ─────────────────────────────────────────

    /** 有标记数据的种子快照（升序）；未激活返回空。 */
    public static long[] seedsSnapshot() {
        var marks = marksStore;
        return marks != null ? marks.seedsSnapshot() : new long[0];
    }

    /** 某种子的统计（组数/记录数）；无数据返回 null。 */
    public static @Nullable MarksStore.SeedStats stats(long seed) {
        var marks = marksStore;
        return marks != null ? marks.stats(seed) : null;
    }

    /**
     * 删除某种子的全部历史标记并立即落盘（不轮替 .old）。
     *
     * @return 删除的记录数; -1 = 该种子正在使用中（拒绝删除）; 0 = 无数据
     */
    public synchronized static int removeSeedForCommand(long seed) {
        var marks = marksStore;
        if (marks == null)
            return 0;
        final var active = activeSeed();
        if (active != null && active == seed)
            return -1;
        return marks.removeSeed(seed);
    }
}
