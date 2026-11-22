package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * legacy {@code .sm4x} 配置文件的 magic 帧读写。
 * <p>
 * 文件格式：{@code MAGIC_WORD + 数据体 + MAGIC_WORD}。数据体的布局由
 * {@link Sm4xCodec} 实现自定，本类只负责 magic 信封。
 * <p>
 * 定位：仅用于 legacy 二进制格式的迁移读取（新配置一律走 JSON +
 * {@link JsonConfigFile}）。{@code writeFrame} 仅测试 fixture / gametest
 * 构造 legacy 文件用。
 */
public final class Sm4xFile {

    /** 所有 .sm4x 配置文件共用的魔数。 */
    public static final byte[] MAGIC_WORD = "SEEDMAP4XAERO".getBytes(StandardCharsets.UTF_8);

    private Sm4xFile() {
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
