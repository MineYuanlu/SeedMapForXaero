package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * legacy {@code .sm4x} 配置文档的序列化编解码器（迁移读取专用）。
 * <p>
 * 实现类只负责自身数据体的读写；magic word 信封由 {@link Sm4xFile#readFrame} /
 * {@link Sm4xFile#writeFrame}（仅测试 fixture）统一包装，实现类内部不要再写
 * magic word。现有实现均为 frozen 的 legacy 只读迁移（write 仅测试 fixture 用），
 * 新配置格式请使用 {@link JsonCodec}。
 *
 * @param <T> 配置文档类型
 */
public interface Sm4xCodec<T> {

    /** 写入数据体（不含 magic 信封）。 */
    void write(T data, DataOutputStream out) throws IOException;

    /**
     * 从数据体读取（不含 magic 信封）。
     * <p>
     * 须支持全部历史版本的读取；遇到不支持的版本抛 {@link IOException}
     * （调用方按迁移失败语义回退，见 {@code JsonConfigFile.tryLegacy}）。
     */
    T read(DataInputStream in) throws IOException;
}
