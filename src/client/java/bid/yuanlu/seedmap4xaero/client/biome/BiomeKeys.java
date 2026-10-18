package bid.yuanlu.seedmap4xaero.client.biome;

import org.jetbrains.annotations.NotNull;

/**
 * 生物群系的持久化 key（配置文件中的稳定标识）。
 * <p>
 * 统一 {@code "minecraft:" + cubiomes 名称}（如 {@code minecraft:plains}）；
 * 名称注册表（biomes.ini）未覆盖的 id 用 {@code "id:N"} 数字占位。
 * 运行时仍以 int id 索引（cubiomes 枚举），key 只存在于持久层，
 * 由本类在配置读写边界做双向转换。
 * <p>
 * 名称注册表在客户端启动时由 {@link BiomeType#init()} 建立（早于任何配置加载）。
 */
public final class BiomeKeys {

    private static final String PREFIX = "minecraft:";

    private BiomeKeys() {
    }

    /** 生物群系 id → 持久化 key（永不返回 null；未注册 id 返回 {@code id:N} 占位）。 */
    public static @NotNull String idToKey(int biomeId) {
        var bt = BiomeType.byId(biomeId);
        return bt != null ? PREFIX + bt.name : "id:" + biomeId;
    }

    /**
     * 持久化 key → 生物群系 id；无法识别返回 -1
     * （调用方应将未知 key 作为 orphan 保留，数据不丢）。
     * 名称在注册表中不唯一时视为不可信（返回 -1），避免错误归位。
     */
    public static int keyToId(@NotNull String key) {
        if (key.startsWith("id:")) {
            try {
                return Integer.parseInt(key.substring(3));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (key.startsWith(PREFIX)) {
            String name = key.substring(PREFIX.length());
            var values = BiomeType.values();
            if (values != null) {
                BiomeType found = null;
                for (BiomeType bt : values) {
                    if (!bt.name.equals(name))
                        continue;
                    if (found != null)
                        return -1; // 重名 → 名称不可信
                    found = bt;
                }
                if (found != null)
                    return found.id;
            }
        }
        return -1;
    }
}
