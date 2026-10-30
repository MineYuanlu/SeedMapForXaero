package bid.yuanlu.seedmap4xaero.client.datapack;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

/**
 * 手动导入实现：N 个本地 root（zip 文件或目录）合并成一个 {@link DatapackSource}。
 * <p>
 * root 顺序即优先级（<b>低→高</b>，与 {@code DatapackImportsData.imports()} 的
 * add 顺序一致）：同 id 资源后 root 覆盖先 root（对齐 vanilla 数据包"后加载覆盖"）。
 * 资源定位为标准数据包布局 {@code data/<namespace>/<path>}。
 * <p>
 * {@link DatapackSource} 的 id 语义与 vanilla ResourceManager 的文件级 id 一致
 * （path 含 {@code worldgen/...} 目录前缀与 {@code .json} 后缀）。
 * zip 句柄按需打开（每次读取重新打开并关闭，不做生命周期管理）；
 * 不校验 pack.mcmeta / pack_format——只读 {@code data/} 下 JSON。
 */
public final class PathDatapackSource implements DatapackSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/PathDatapackSource");

    private static final String DATA_PREFIX = "data/";
    private static final String JSON_SUFFIX = ".json";

    /** 优先级低→高的 root 列表。 */
    private final List<Path> rootsLowToHigh;

    /** @param rootsLowToHigh 优先级低→高（add 顺序） */
    public PathDatapackSource(@NotNull List<Path> rootsLowToHigh) {
        this.rootsLowToHigh = List.copyOf(rootsLowToHigh);
    }

    // ─── 资源定位 ──────────────────────────────────────────────

    /** zip/目录中 {@code data/<ns>/<resPath>} 资源的原始字节; 不存在返回 null。 */
    private byte @Nullable [] readBytes(Path root, boolean zip, String namespace, String resPath) {
        if (zip) {
            try (ZipFile zf = new ZipFile(root.toFile())) {
                ZipEntry entry = zf.getEntry(DATA_PREFIX + namespace + "/" + resPath);
                if (entry == null)
                    return null;
                return zf.getInputStream(entry).readAllBytes();
            } catch (IOException e) {
                LOGGER.warn("failed to read {} from {}: {}", resPath, root, e.toString());
                return null;
            }
        }
        Path file = root.resolve(DATA_PREFIX).resolve(namespace).resolve(resPath);
        if (!Files.isRegularFile(file))
            return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.warn("failed to read {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * 列出某 root 中 folder 下的资源：完整资源 path（带 .json）→ namespace。
     * 目录递归 {@code data/}；zip 遍历条目名。非法 id 的条目静默跳过。
     */
    private Map<String, String> listResources(Path root, boolean zip, String folder) {
        final Map<String, String> out = new HashMap<>();
        if (zip) {
            try (ZipFile zf = new ZipFile(root.toFile())) {
                zf.stream().forEach(entry -> collect(entry.getName(), folder, out));
            } catch (IOException e) {
                LOGGER.warn("failed to list datapack {}: {}", root, e.toString());
            }
            return out;
        }
        final Path data = root.resolve("data");
        if (!Files.isDirectory(data))
            return out;
        try (Stream<Path> walk = Files.walk(data)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                final String name = root.relativize(file).toString().replace('\\', '/');
                collect(name, folder, out);
            });
        } catch (IOException e) {
            LOGGER.warn("failed to list datapack {}: {}", root, e.toString());
        }
        return out;
    }

    private static void collect(String entryName, String folder, Map<String, String> out) {
        if (!entryName.startsWith(DATA_PREFIX) || !entryName.endsWith(JSON_SUFFIX))
            return;
        final String rest = entryName.substring(DATA_PREFIX.length());
        final int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1)
            return;
        final String namespace = rest.substring(0, slash);
        final String resPath = rest.substring(slash + 1);
        if (!resPath.startsWith(folder + "/"))
            return;
        out.putIfAbsent(resPath, namespace);
    }

    private static Reader readerOf(byte @Nullable [] bytes) {
        if (bytes == null)
            return null;
        return new StringReader(new String(bytes, StandardCharsets.UTF_8));
    }

    // ─── DatapackSource ────────────────────────────────────────

    @Override
    public @NotNull Map<Identifier, ? extends Reader> readAllJson(@NotNull String folder) {
        // 低优先级 root 先 put, 高优先级后 put 覆盖 (值=最高层, 顺序=首次出现)
        final Map<Identifier, Reader> out = new LinkedHashMap<>();
        for (Path root : rootsLowToHigh) {
            final boolean zip = Files.isRegularFile(root);
            for (Map.Entry<String, String> e : listResources(root, zip, folder).entrySet()) {
                final Identifier id = parseId(e.getValue(), e.getKey());
                if (id == null)
                    continue;
                final Reader reader = readerOf(readBytes(root, zip, e.getValue(), e.getKey()));
                if (reader != null)
                    ResourceManagerSource.closeQuietly(out.put(id, reader));
            }
        }
        return out;
    }

    @Override
    public @Nullable List<? extends Reader> readJsonStack(@NotNull Identifier id) {
        final List<Reader> stack = new ArrayList<>(rootsLowToHigh.size());
        for (Path root : rootsLowToHigh) {
            final Reader reader = readerOf(readBytes(
                    root, Files.isRegularFile(root), id.getNamespace(), id.getPath()));
            if (reader != null)
                stack.add(reader);
        }
        return stack.isEmpty() ? null : stack;
    }

    @Override
    public @Nullable Reader readJson(@NotNull Identifier id) {
        // 高优先级在前查找
        for (int i = rootsLowToHigh.size() - 1; i >= 0; i--) {
            final Path root = rootsLowToHigh.get(i);
            final Reader reader = readerOf(readBytes(
                    root, Files.isRegularFile(root), id.getNamespace(), id.getPath()));
            if (reader != null)
                return reader;
        }
        return null;
    }

    private static @Nullable Identifier parseId(String namespace, String resPath) {
        try {
            return Identifier.fromNamespaceAndPath(namespace, resPath);
        } catch (Exception e) {
            LOGGER.warn("skipping resource with invalid id {}:{}", namespace, resPath);
            return null;
        }
    }
}
