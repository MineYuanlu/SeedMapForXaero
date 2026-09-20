package bid.yuanlu.seedmap4xaero.client.configs.core;

import java.io.IOException;

import com.google.gson.JsonObject;

/**
 * 一个 JSON 配置文档的编解码器（配合 {@link JsonConfigFile} 使用）。
 * <p>
 * 实现类基于 Gson 树模型（{@link JsonObject}）手工读写，不使用反射映射——
 * 字段增删显式可控：读端对缺失字段落默认值、对未知字段忽略（双向前向兼容），
 * 因此 JSON 文档内部不需要版本分发的读取分支（{@code version} 字段仅作记录）。
 * <p>
 * 约定：实现只写文档体；文件级布局（原子写、轮替、回退链、legacy 迁移）由
 * {@link JsonConfigFile} 统一处理。
 *
 * @param <T> 配置文档类型
 */
public interface JsonCodec<T> {

    /** 写出文档体（手工构建 JSON 树；{@code null} 值字段直接省略）。 */
    JsonObject write(T data);

    /**
     * 从文档体读取。字段缺失时使用默认值；遇到结构性错误（类型不符等）
     * 抛 {@link IOException}，由上层回退到下一个候选文件。
     */
    T read(JsonObject json) throws IOException;
}
