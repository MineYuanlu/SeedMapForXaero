package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * .sm4x 配置文件的通用磁盘 IO：原子保存、损坏回退。
 * <p>
 * 文件格式：{@code MAGIC_WORD + 数据体 + MAGIC_WORD}。
 * 数据体的布局（含是否使用版本号）由 {@link Sm4xCodec} 实现自定，本类只负责
 * magic 信封与文件轮替。与具体配置语义无关，可被任意新增的 .sm4x 配置文件复用。
 */
public final class Sm4xFile {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/Sm4xFile");

    /** 所有 .sm4x 配置文件共用的魔数。 */
    public static final byte[] MAGIC_WORD = "SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8);

    private static final String TMP_SUFFIX = ".tmp";
    private static final String OLD_SUFFIX = ".old";

    private Sm4xFile() {
    }

    /** 一个配置文件在磁盘上的三件套路径。 */
    public record Sm4xPaths(Path target, Path tmp, Path old) {
    }

    /**
     * 计算配置文件路径：{@code baseDir/<mainId>/<fileName>}（含 .tmp/.old 变体）。
     * <p>
     * 目录按 mainId 平铺隔离，每个配置文件一个独立文件名。
     */
    public static Sm4xPaths pathsFor(Path baseDir, String mainId, String fileName) {
        var dir = baseDir.resolve(mainId);
        return new Sm4xPaths(
                dir.resolve(fileName),
                dir.resolve(fileName + TMP_SUFFIX),
                dir.resolve(fileName + OLD_SUFFIX));
    }

    /**
     * 原子写入 {@code data} 到 {@code paths.target()}。
     * <p>
     * 流程：
     * <ol>
     * <li>序列化写入 {@code .tmp}
     * <li>若主文件存在，移动到 {@code .old}
     * <li>将 {@code .tmp} 移动到主文件（ATOMIC_MOVE，尽力原子）
     * </ol>
     */
    public static <T> void save(Sm4xPaths paths, T data, Sm4xCodec<T> codec) throws IOException {
        Files.createDirectories(paths.target().getParent());

        // 1. 写入临时文件
        writeFrame(paths.tmp(), data, codec);

        // 2. 轮替旧文件
        if (Files.exists(paths.target())) {
            Files.move(paths.target(), paths.old(), StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. 提交
        Files.move(paths.tmp(), paths.target(), StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * 从磁盘加载配置：主文件 → 损坏则删主文件回退 {@code .old} → 损坏或不存在则返回
     * {@code fallback}。
     */
    public static <T> T load(Sm4xPaths paths, T fallback, Sm4xCodec<T> codec) {
        if (Files.exists(paths.target())) {
            try {
                return readFrame(paths.target(), codec);
            } catch (IOException e) {
                LOGGER.error("Failed to load config {}, try load old config instead", paths.target(), e);
                try {
                    Files.deleteIfExists(paths.target());
                } catch (Throwable ignored) {
                }
            }
        }
        if (Files.exists(paths.old())) {
            try {
                return readFrame(paths.old(), codec);
            } catch (IOException e) {
                LOGGER.error("Failed to load old config {}, create new config instead", paths.old(), e);
            }
        }
        return fallback;
    }

    /** 写入完整帧：magic + 数据体（布局由 codec 自定）+ magic。 */
    public static <T> void writeFrame(Path file, T data, Sm4xCodec<T> codec) throws IOException {
        try (var out = new DataOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.write(MAGIC_WORD);
            codec.write(data, out);
            out.write(MAGIC_WORD);
        }
    }

    /** 读取完整帧：校验头尾 magic，数据体布局由 codec 自行解释。 */
    public static <T> T readFrame(Path file, Sm4xCodec<T> codec) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(file, StandardOpenOption.READ))) {
            final byte[] magicWord = new byte[MAGIC_WORD.length];
            in.readFully(magicWord);
            if (!Arrays.equals(magicWord, MAGIC_WORD))
                throw new IOException("Invalid magic word at start");
            final var data = codec.read(in);
            in.readFully(magicWord);
            if (!Arrays.equals(magicWord, MAGIC_WORD))
                throw new IOException("Invalid magic word at end");
            return data;
        }
    }
}
