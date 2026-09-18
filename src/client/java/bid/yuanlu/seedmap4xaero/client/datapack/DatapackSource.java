package bid.yuanlu.seedmap4xaero.client.datapack;

import java.io.Reader;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.Identifier;

/**
 * 数据包数据源抽象 (v1 仅单机 ResourceManager 实现; 多人 zip/目录导入留 v2)。
 * <p>
 * 约定:
 * <ul>
 * <li>{@link #readAllJson} 返回 folder 下每个 id 的<b>最高优先级</b>资源
 *     (多包覆盖语义, 适用于 worldgen 定义)</li>
 * <li>{@link #readJsonStack} 返回 id 的<b>全部包层</b> (低优先级在前),
 *     供 tag 合并 (replace:false 语义)</li>
 * </ul>
 * 读出的内容以 {@link Reader} 惰性提供, 由调用方关闭。
 */
public interface DatapackSource {

    /**
     * 列出 folder 下全部 JSON 资源 (每 id 取最高优先级层)。
     *
     * @param folder 目录前缀, 如 "worldgen/structure_set" (不含斜杠尾)
     * @return id (含 folder 前缀, 如 "terralith:worldgen/structure_set/regular") → 资源读取器
     */
    @NotNull Map<Identifier, ? extends Reader> readAllJson(@NotNull String folder);

    /**
     * 读单个资源的所有包层 (低优先级在前, 供 tag 合并); 无任何层返回 null。
     *
     * @param id 完整资源 id, 如 "terralith:tags/worldgen/biome/has_structure/village.json"
     */
    @Nullable java.util.List<? extends Reader> readJsonStack(@NotNull Identifier id);

    /** 读单个资源的最高优先级层; 无返回 null。 */
    @Nullable Reader readJson(@NotNull Identifier id);
}
