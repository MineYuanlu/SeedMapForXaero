package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

/**
 * 结构类型的持久化 key（配置文件中的稳定标识）。
 * <p>
 * 原版结构统一 {@code "minecraft:" + 枚举 key}（如 {@code minecraft:Village}）；
 * 无法识别的 id 用 {@code "id:N"} 数字占位（防御性，正常不会出现）。
 * 运行时仍以 int id 索引（渲染/缓存热路径），key 只存在于持久层，
 * 由本类在配置读写边界做双向转换。
 * <p>
 * <b>数据包结构预留</b>：自定义结构的持久化 key 即其完整 id
 * （{@code terralith:xxx} 形态，天然带命名空间）。数据包支持落地时，
 * 将本类的枚举查表替换为 {@code StructureTypes} 注册表查表即可，
 * 持久化格式无需变更。
 */
public final class StructureKeys {

    private static final ConcurrentHashMap<Integer, String> KEY_CACHE = new ConcurrentHashMap<>();

    private StructureKeys() {
    }

    /** 结构 id → 持久化 key（永不返回 null；未知 id 返回 {@code id:N} 占位）。 */
    public static @NotNull String persistedKey(int structureId) {
        var cached = KEY_CACHE.get(structureId);
        if (cached != null)
            return cached;
        String key = computeKey(structureId);
        KEY_CACHE.put(structureId, key);
        return key;
    }

    private static String computeKey(int structureId) {
        if (structureId < 0 || structureId >= StructureType.FEATURE_NUM)
            return "id:" + structureId;
        return "minecraft:" + StructureType.byId(structureId).key;
    }

    /**
     * 持久化 key → 结构 id；无法识别返回 -1
     * （调用方应将未知 key 作为 orphan 保留，数据不丢）。
     */
    public static int resolveId(@NotNull String key) {
        if (key.startsWith("id:")) {
            try {
                return Integer.parseInt(key.substring(3));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (key.startsWith("minecraft:")) {
            String name = key.substring("minecraft:".length());
            for (StructureType t : StructureType.values()) {
                if (t.key.equals(name))
                    return t.id;
            }
        }
        return -1;
    }
}
