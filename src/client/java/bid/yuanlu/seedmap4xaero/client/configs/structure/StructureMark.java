package bid.yuanlu.seedmap4xaero.client.configs.structure;

import org.jetbrains.annotations.Nullable;

/**
 * 单个结构的持久标记: 访问记录 + 分组。
 *
 * @param minDist 历史最小访问距离 (整数方块, Chebyshev); {@code -1} = 未访问 (仅分组)
 * @param group   分组字符串; 空串 = 未分组 ({@link StructureGroups#DEFAULT})
 */
public record StructureMark(int minDist,@Nullable String group) {

    /** 未访问时 {@link #minDist} 的哨兵值。 */
    public static final int NO_VISIT = -1;

    public StructureMark {
        if (group == null)
            group = StructureGroups.DEFAULT;
    }

    /** 是否已访问 (进入过阈值范围)。 */
    public boolean visited() {
        return minDist != NO_VISIT;
    }

    /** 返回记录了更近访问距离的副本; 当前未访问时直接记录 dist。 */
    public StructureMark withVisit(int dist) {
        if (visited() && minDist <= dist)
            return this;
        return new StructureMark(dist, group);
    }
}
