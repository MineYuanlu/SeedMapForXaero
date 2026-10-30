package bid.yuanlu.seedmap4xaero.client.datapack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.datapack.DatapackImportsConfig;
import net.minecraft.client.Minecraft;

/**
 * 手动数据包导入统一内核 (v2/M4) —— 命令与 UI 的唯一交互入口。
 * <p>
 * 管理 per-mainId 的导入路径列表（zip/目录，存取见
 * {@link DatapackImportsConfig}），变更后自动触发
 * {@link DatapackStructures#reload()} 并以 {@link Result} 返回
 * i18n 消息键 + 参数——命令转 chat 消息、UI 转 toast/行内提示，
 * 两端体验一致。
 * <p>
 * <b>优先级语义</b>：导入列表非空 → 只用手动源（覆盖单机集成服务器
 * ResourceManager）；为空 → 回退单机 RM；多人且无导入 → 等价清除。
 */
public final class DatapackImports {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/DatapackImports");

    /** 操作结果（i18n 契约）：{@code messageKey} + 格式化参数，由展示层翻译。 */
    public record Result(boolean success, String messageKey, Object[] messageArgs) {
        public static Result ok(String key, Object... args) {
            return new Result(true, key, args);
        }

        public static Result fail(String key, Object... args) {
            return new Result(false, key, args);
        }
    }

    private DatapackImports() {
    }

    /** 当前导入路径列表（add 顺序 = 优先级递增）。 */
    public static @NotNull List<String> entries() {
        return DatapackImportsConfig.entries();
    }

    /**
     * 导入一个数据包（zip 文件或目录）到当前世界根。
     * 校验通过后持久化、立即刷盘并重扫。
     *
     * @param rawPath 用户输入路径（相对路径以游戏目录为基准，支持 {@code ~}）
     */
    public static Result add(String rawPath) {
        if (DatapackImportsConfig.activeMainId() == null)
            return Result.fail("xsm.command.datapack.no_world");
        if (rawPath == null || rawPath.isBlank())
            return Result.fail("xsm.command.datapack.not_found", rawPath == null ? "" : rawPath);
        final Path path = resolvePath(rawPath);
        if (!Files.isRegularFile(path) && !Files.isDirectory(path))
            return Result.fail("xsm.command.datapack.not_found", path);
        if (Files.isRegularFile(path) && !openableZip(path))
            return Result.fail("xsm.command.datapack.bad_zip", path);
        if (containsPath(path))
            return Result.fail("xsm.command.datapack.duplicate", path);

        DatapackImportsConfig.add(path.toString());
        DatapackImportsConfig.flush();
        LOGGER.info("imported datapack {}", path);
        final DatapackStructures.ReloadResult reload = DatapackStructures.reload();
        if (reload.failed())
            return Result.fail("xsm.command.datapack.scan_failed", reload.sets());
        return Result.ok("xsm.command.datapack.add.success", reload.sets(), reload.structures());
    }

    /** 移除指定下标的导入路径（下标见 {@link #entries()}），并重扫。 */
    public static Result remove(int index) {
        if (DatapackImportsConfig.activeMainId() == null)
            return Result.fail("xsm.command.datapack.no_world");
        final List<String> current = DatapackImportsConfig.entries();
        if (index < 0 || index >= current.size())
            return Result.fail("xsm.command.datapack.bad_index", current.size());
        final String removed = current.get(index);
        DatapackImportsConfig.remove(index);
        DatapackImportsConfig.flush();
        LOGGER.info("removed datapack import {}", removed);
        final DatapackStructures.ReloadResult reload = DatapackStructures.reload();
        if (reload.failed())
            return Result.fail("xsm.command.datapack.scan_failed", reload.sets());
        return Result.ok("xsm.command.datapack.remove.success", removed, reload.sets(), reload.structures());
    }

    /**
     * 由导入列表构建数据源；列表为空返回 null（调用方回退集成服务器 RM）。
     * 列表顺序 = 优先级低→高。
     */
    public static @Nullable DatapackSource buildSource() {
        final List<String> entries = DatapackImportsConfig.entries();
        if (entries.isEmpty())
            return null;
        return new PathDatapackSource(entries.stream().map(Path::of).toList());
    }

    // ─── 内部 ──────────────────────────────────────────────────

    private static Path resolvePath(String rawPath) {
        return resolvePath(rawPath, Minecraft.getInstance().gameDirectory.toPath());
    }

    /** 路径解析（纯函数便于 JVM 单测）：{@code ~} 展开 + 相对路径以 gameDir 为基准 + normalize。 */
    static Path resolvePath(String rawPath, Path gameDir) {
        String p = rawPath.strip();
        if (p.startsWith("~")) {
            final String home = System.getProperty("user.home");
            p = home + p.substring(1);
        }
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            path = gameDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean openableZip(Path path) {
        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (IOException e) {
            LOGGER.warn("not a readable zip: {}", path);
            return false;
        }
    }

    private static boolean containsPath(Path path) {
        return DatapackImportsConfig.entries().stream()
                .anyMatch(e -> Path.of(e).equals(path));
    }
}
