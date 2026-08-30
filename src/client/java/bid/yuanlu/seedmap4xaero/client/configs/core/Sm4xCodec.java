package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 一个 .sm4x 配置文档的序列化编解码器。
 * <p>
 * 实现类只负责自身数据体的读写；magic word 信封由 {@link Sm4xFile#writeFrame} /
 * {@link Sm4xFile#readFrame} 统一包装，实现类内部不要再写 magic word。
 * <p>
 * 推荐实现内部做版本号管理，以便后续版本升级。版本号是文档的内部细节，框架不感知：
 * {@link #write} 先写一个 version int 再写数据体，
 * {@link #read} 先读 version int 再按历史版本分发，
 * 遇到不支持的版本自行抛 {@link IOException}。
 *
 * @param <T> 配置文档类型
 */
public interface Sm4xCodec<T> {

    /** 
     * 写入数据体（不含 magic 信封）。
     * <p>
     * 推荐写出时首先写出版本号作为起始，以便版本升级。
     */
    void write(T data, DataOutputStream out) throws IOException;

    /**
     * 从数据体读取（不含 magic 信封）。
     * <p>
     * 推荐支持所有历史版本的 prefix-compatible 读取。
     */
    T read(DataInputStream in) throws IOException;
}
