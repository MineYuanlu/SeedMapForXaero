package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

/**
 * 结构类型的持久化 key（配置文件中的稳定标识）。
 * <p>
 * 原版结构统一 {@code "minecraft:" + 枚举 key}（如 {@code minecraft:village}）；
 * 数据包自定义结构即其完整 id（{@code terralith:xxx} 形态，天然带命名空间）；
 * 无法识别的 id 用 {@code "id:N"} 数字占位（防御性，正常不会出现）。
 * 运行时仍以 int id 索引（渲染/缓存热路径），key 只存在于持久层，
 * 由本类在配置读写边界做双向转换。
 * <p>
 * 查表经 {@link StructureTypes} 注册表（原版枚举 + 动态注入的自定义类型）。
 * 自定义 id 是<b>会话级动态分配</b>（不同数据包组合下同 id 可能对应不同结构），
 * 因此只有原版段进 {@code KEY_CACHE}；自定义段每次实时查表，注册表替换即生效。
 */
public final class StructureKeys {

    private static final ConcurrentHashMap<Integer, String> KEY_CACHE = new ConcurrentHashMap<>();

    private StructureKeys() {
    }

    /** 结构 id → 持久化 key（永不返回 null；未知 id 返回 {@code id:N} 占位）。 */
    public static @NotNull String persistedKey(int structureId) {
        if (structureId >= 0 && structureId < StructureType.FEATURE_NUM) {
            return KEY_CACHE.computeIfAbsent(structureId, StructureKeys::computeKey);
        }
        return computeKey(structureId);
    }

    private static String computeKey(int structureId) {
        var t = StructureTypes.byId(structureId);
        if (t == null)
            return "id:" + structureId;
        return t.isCustom() ? t.key() : "minecraft:" + t.key();
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
            return -1;
        }
        // 数据包自定义结构: 完整 id 直接匹配注册表
        for (CustomStructureType t : StructureTypes.customTypes()) {
            if (t.key().equals(key))
                return t.id();
        }
        return -1;
    }
}
