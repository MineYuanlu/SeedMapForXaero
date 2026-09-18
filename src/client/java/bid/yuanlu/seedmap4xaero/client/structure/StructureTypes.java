package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * 结构类型注册表: 原版枚举 + 数据包自定义类型的统一解析入口。
 * <p>
 * 三个枚举闭合点 (byId 选择、结构集合遍历、面板列表) 全部收敛到这里:
 * id 透传链路 ({@code StructureCache}/{@code StructureBitFlag}/{@code StructureData})
 * 经 {@link #byId(int)} 解析, 集合遍历经 {@link #all()}。
 * <p>
 * 自定义表在世界切换时由 {@code DatapackStructureIngest} 整体替换;
 * 读侧 volatile 快照, 无锁。
 */
public final class StructureTypes {

    private static volatile List<CustomStructureType> customTypes = List.of();
    private static volatile Int2ObjectMap<CustomStructureType> customById = Int2ObjectMaps.emptyMap();

    private StructureTypes() {
    }

    /** id → 类型; null = 未知 id (含未注入的自定义 id)。 */
    public static @Nullable StructureInfo byId(int id) {
        if (id >= 0 && id < StructureType.FEATURE_NUM)
            return StructureType.byId(id);
        if (id >= CustomStructureType.MIN_ID && id < CustomStructureType.MAX_ID)
            return customById.get(id);
        return null;
    }

    /** 全部类型 (原版枚举序 + 自定义按 id 升序, c:hide_from_map 除外);
     * 每次调用新建列表, 调用方可安全修改。 */
    public static @NotNull List<StructureInfo> all() {
        List<StructureInfo> out = new ArrayList<>(
                StructureType.values().length + customTypes.size());
        for (StructureType t : StructureType.values())
            out.add(t);
        for (CustomStructureType t : customTypes)
            if (!t.isHidden())
                out.add(t);
        return out;
    }

    /** 位集/数组容量: 覆盖全部已注册 id 所需的最小长度。 */
    public static int capacity() {
        int max = StructureType.FEATURE_NUM;
        for (CustomStructureType t : customTypes)
            max = Math.max(max, t.id() + 1);
        return max;
    }

    /** 当前注册的自定义类型 (不可变快照)。 */
    public static @NotNull List<CustomStructureType> customTypes() {
        return customTypes;
    }

    /**
     * 整体替换自定义类型表 (世界切换/数据包重扫描时)。
     *
     * @throws IllegalArgumentException id 越界或重复
     */
    public static void setCustomTypes(@NotNull List<CustomStructureType> types) {
        List<CustomStructureType> sorted = new ArrayList<>(types);
        sorted.sort(Comparator.comparingInt(CustomStructureType::id));
        Int2ObjectOpenHashMap<CustomStructureType> map = new Int2ObjectOpenHashMap<>(sorted.size());
        for (CustomStructureType t : sorted) {
            if (t.id() < CustomStructureType.MIN_ID || t.id() >= CustomStructureType.MAX_ID)
                throw new IllegalArgumentException("custom structure id out of range: " + t.id());
            if (map.put(t.id(), t) != null)
                throw new IllegalArgumentException("duplicate custom structure id: " + t.id());
        }
        customById = map;
        customTypes = List.copyOf(sorted);
    }
}
