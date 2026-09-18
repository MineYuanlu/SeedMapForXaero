package bid.yuanlu.seedmap4xaero.client.datapack;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 单机实现: 集成服务器的 {@link ResourceManager}。
 * <p>
 * 该资源管理器已合并当前世界启用的全部数据包 (含 overlay 与 Fabric 内置包),
 * {@code data/} 下的 worldgen 定义按包优先级取最高层, tag 取全部层。
 */
public final class ResourceManagerSource implements DatapackSource {

    private final ResourceManager rm;

    public ResourceManagerSource(@NotNull ResourceManager rm) {
        this.rm = rm;
    }

    /** 当前集成服务器的数据源; 未运行单机世界返回 null。 */
    public static @Nullable ResourceManagerSource ofIntegratedServer() {
        final MinecraftServer server = net.minecraft.client.Minecraft.getInstance()
                .getSingleplayerServer();
        if (server == null)
            return null;
        return new ResourceManagerSource(server.getResourceManager());
    }

    private static @NotNull Reader open(Resource resource) {
        try {
            return resource.openAsReader();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open resource", e);
        }
    }

    @Override
    public @NotNull Map<Identifier, ? extends Reader> readAllJson(@NotNull String folder) {
        // 只收 .json 后缀 (与 vanilla listResources 的常规用法一致)
        Map<Identifier, Resource> found = rm.listResources(folder,
                id -> id.getPath().endsWith(".json"));
        Map<Identifier, Reader> out = new LinkedHashMap<>(found.size());
        found.forEach((id, resource) -> out.put(id, open(resource)));
        return out;
    }

    @Override
    public @Nullable List<? extends Reader> readJsonStack(@NotNull Identifier id) {
        List<Resource> stack = rm.getResourceStack(id);
        if (stack.isEmpty())
            return null;
        // ResourceManager 语义: 高优先级在前 → 反转为低优先级在前 (tag 合并顺序)
        List<Reader> out = new ArrayList<>(stack.size());
        for (int i = stack.size() - 1; i >= 0; i--)
            out.add(open(stack.get(i)));
        return out;
    }

    @Override
    public @Nullable Reader readJson(@NotNull Identifier id) {
        return rm.getResource(id).map(ResourceManagerSource::open).orElse(null);
    }

    /** 静默关闭读取器 (扫描循环用)。 */
    static void closeQuietly(@Nullable Reader reader) {
        if (reader == null)
            return;
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }
}
