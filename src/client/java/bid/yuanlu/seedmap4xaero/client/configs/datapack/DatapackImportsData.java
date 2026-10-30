package bid.yuanlu.seedmap4xaero.client.configs.datapack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;

/**
 * {@code datapack_imports.json} 文档体：手动导入的数据包路径列表
 * （zip 或目录），per-mainId（每个多人服务器/单机世界根独立）。
 * <p>
 * <b>列表顺序 = 优先级</b>：先加入的优先级低，后加入的覆盖先加入的
 * （对齐 vanilla 数据包"后加载覆盖"语义）。
 */
public final class DatapackImportsData {

    /** 手动导入路径（add 顺序 = 优先级递增）。 */
    private final List<String> imports = new ArrayList<>();

    /** 脏标志（flush/save 契约，见 configs/core/AGENTS.md）。 */
    public final transient AtomicBoolean dirty = new AtomicBoolean(false);

    public List<String> imports() {
        return List.copyOf(imports);
    }

    /** 追加导入路径（保持 add 顺序）；已存在（字符串相等）则不重复。 */
    public boolean add(String path) {
        if (imports.contains(path))
            return false;
        imports.add(path);
        dirty.set(true);
        return true;
    }

    /** 移除指定下标的导入路径；下标越界返回 false。 */
    public boolean remove(int index) {
        if (index < 0 || index >= imports.size())
            return false;
        imports.remove(index);
        dirty.set(true);
        return true;
    }

    private synchronized JsonObject writeJson() {
        final var json = new JsonObject();
        json.addProperty("version", 1);
        final var arr = new JsonArray();
        for (String path : imports)
            arr.add(path);
        json.add("imports", arr);
        return json;
    }

    /** 缺失字段落默认值，未知字段忽略；非字符串条目丢弃，条目去重保序（单字段容错）。 */
    private static DatapackImportsData readJson(JsonObject json) throws IOException {
        try {
            final var data = new DatapackImportsData();
            if (json.has("imports") && json.get("imports").isJsonArray()) {
                final Set<String> seen = new LinkedHashSet<>();
                for (JsonElement el : json.getAsJsonArray("imports")) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
                        seen.add(el.getAsString());
                }
                data.imports.addAll(seen);
            }
            return data;
        } catch (RuntimeException e) {
            throw new IOException("Malformed datapack imports config", e);
        }
    }

    /** datapack_imports.json 的 JSON 编解码器，配合 {@code JsonConfigFile} 使用。 */
    public static final JsonCodec<DatapackImportsData> JSON_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(DatapackImportsData data) {
            return data.writeJson();
        }

        @Override
        public DatapackImportsData read(JsonObject json) throws IOException {
            return DatapackImportsData.readJson(json);
        }
    };
}
