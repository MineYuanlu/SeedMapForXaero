package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

/**
 * JSON 配置文件（{@code *.json}）的通用磁盘 IO：原子保存、损坏回退、
 * 旧版 {@code .sm4x} 二进制格式的自动向上迁移。
 * <p>
 * 文件为纯 JSON 文本（无 magic 信封），文档体由 {@link JsonCodec} 构建；
 * 磁盘布局沿用 {@link Sm4xFile} 的三件套约定：{@code .tmp} 暂存 →
 * （可选轮替正本到 {@code .old}）→ {@code ATOMIC_MOVE} 提交。
 * <p>
 * <b>轮替契约（调用点手动保证）</b>：{@code rotate=true} 仅用于"数据源自磁盘
 * 读取验证"的生命周期保存（如世界切换）；会话内的主动刷写 / 周期保存必须
 * {@code rotate=false}——只有经过读取验证的数据才适合覆盖 {@code .old}，
 * 否则会话内快速多次写出会用未经验证的数据覆盖可能有用的 {@code .old} 备份。
 * <p>
 * <b>加载回退链</b>：{@code .json} → {@code .json.old} →
 * {@code .sm4x}（legacy，读出即迁移）→ {@code .sm4x.old}（legacy）→ fallback。
 * 只支持自动向上升级；回退旧版本不在支持范围内（旧文件保留为
 * {@code *.legacy} 可手动恢复）。
 */
public final class JsonConfigFile {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/JsonConfigFile");

    /** 写出用：pretty + 不转义（用户组名等非 ASCII 保持可读）。 */
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    /** 读取用：宽容手工编辑（注释/尾逗号等非严格语法）。 */
    private static final Gson LENIENT = new GsonBuilder().setStrictness(Strictness.LENIENT).create();

    /** 一个配置文件在磁盘上的五件套路径（JSON 三件套 + legacy 二件套）。 */
    public record Paths(Path target, Path tmp, Path old, Path legacy, Path legacyOld) {
    }

    private JsonConfigFile() {
    }

    /**
     * 计算配置文件路径：{@code baseDir/<mainId>/<jsonFileName>} +
     * 同目录下旧版 {@code legacyFileName}（含各变体）。
     */
    public static Paths pathsFor(Path baseDir, String mainId, String jsonFileName, String legacyFileName) {
        var dir = baseDir.resolve(mainId);
        return new Paths(
                dir.resolve(jsonFileName),
                dir.resolve(jsonFileName + ".tmp"),
                dir.resolve(jsonFileName + ".old"),
                dir.resolve(legacyFileName),
                dir.resolve(legacyFileName + ".old"));
    }

    /**
     * 原子写入 {@code data} 到 {@code paths.target()}。
     * <p>
     * 流程：写 {@code .tmp} → {@code rotate=true} 且正本存在时正本移到 {@code .old}
     * → {@code .tmp} {@code ATOMIC_MOVE} 提交。轮替契约见类 javadoc。
     */
    public static <T> void save(Paths paths, T data, JsonCodec<T> codec, boolean rotate) throws IOException {
        Files.createDirectories(paths.target().getParent());

        // 1. 写入临时文件
        writeJson(paths.tmp(), data, codec);

        // 2. 轮替旧文件（契约: rotate=true 只在数据经读取验证的生命周期保存使用）
        if (rotate && Files.exists(paths.target())) {
            Files.move(paths.target(), paths.old(), StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. 提交（跳过轮替时正本可能已存在, 需 REPLACE_EXISTING）
        Files.move(paths.tmp(), paths.target(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 从磁盘加载配置，按类 javadoc 的回退链依次尝试。
     * <p>
     * legacy 候选读出成功即视为迁移点：立即以新格式落盘（不轮替——正本缺失或
     * 已因损坏删除，{@code .old} 不受影响），随后把 legacy 文件改名为
     * {@code *.legacy} 退出回退链。
     *
     * @param legacyCodec 旧版 {@code .sm4x} 解码器；{@code null} = 不做 legacy 迁移
     *                    （由调用方自行处理特殊迁移流程）
     */
    public static <T> T load(Paths paths, T fallback, JsonCodec<T> codec,
            @Nullable Sm4xCodec<T> legacyCodec) {
        if (Files.exists(paths.target())) {
            try {
                return readJson(paths.target(), codec);
            } catch (IOException e) {
                LOGGER.error("Failed to load config {}, try load .old instead", paths.target(), e);
                try {
                    Files.deleteIfExists(paths.target());
                } catch (Throwable ignored) {
                }
            }
        }
        if (Files.exists(paths.old())) {
            try {
                return readJson(paths.old(), codec);
            } catch (IOException e) {
                LOGGER.error("Failed to load old config {}", paths.old(), e);
            }
        }
        if (legacyCodec != null) {
            var fromLegacy = tryLegacy(paths, codec, legacyCodec, paths.legacy());
            if (fromLegacy != null)
                return fromLegacy;
            fromLegacy = tryLegacy(paths, codec, legacyCodec, paths.legacyOld());
            if (fromLegacy != null)
                return fromLegacy;
        }
        return fallback;
    }

    /** 读一个 legacy 候选并完成迁移（写新格式 + retire）；失败返回 null 继续回退链。 */
    private static <T> @Nullable T tryLegacy(Paths paths, JsonCodec<T> jsonCodec,
            Sm4xCodec<T> legacyCodec, Path legacyPath) {
        if (!Files.exists(legacyPath))
            return null;
        try {
            T data = Sm4xFile.readFrame(legacyPath, legacyCodec);
            // 迁移落盘: rotate=false —— 正本不存在或已因损坏删除, .old 不受影响
            save(paths, data, jsonCodec, false);
            LOGGER.info("Migrated legacy config {} -> {}", legacyPath, paths.target());
            retireLegacy(paths);
            return data;
        } catch (IOException e) {
            LOGGER.error("Failed to load legacy config {}", legacyPath, e);
            return null;
        }
    }

    /**
     * 将 legacy 文件改名退出回退链：{@code .sm4x → .sm4x.legacy}、
     * {@code .sm4x.old → .sm4x.old.legacy}。失败仅告警（下次加载若新格式
     * 完好则不会再次触碰 legacy 文件）。
     */
    public static void retireLegacy(Paths paths) {
        retire(paths.legacy());
        retire(paths.legacyOld());
    }

    private static void retire(Path legacy) {
        if (!Files.exists(legacy))
            return;
        var retired = legacy.resolveSibling(legacy.getFileName() + ".legacy");
        try {
            Files.move(legacy, retired, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Failed to retire legacy file {} -> {}", legacy, retired, e);
        }
    }

    /** 写出纯 JSON 文本（pretty、UTF-8、无信封）。 */
    public static <T> void writeJson(Path file, T data, JsonCodec<T> codec) throws IOException {
        JsonObject json = codec.write(data);
        Files.writeString(file, PRETTY.toJson(json), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 读取纯 JSON 文本并交给 codec；语法/结构错误统一包装为 {@link IOException}。 */
    public static <T> T readJson(Path file, JsonCodec<T> codec) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = LENIENT.fromJson(text, JsonObject.class);
        } catch (JsonParseException e) {
            throw new IOException("Invalid JSON document: " + file, e);
        }
        if (json == null)
            throw new IOException("Empty JSON document: " + file);
        return codec.read(json);
    }
}
