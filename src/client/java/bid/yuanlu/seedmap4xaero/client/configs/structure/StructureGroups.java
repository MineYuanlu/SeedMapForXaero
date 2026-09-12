package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * 标记分组: 内置组常量 + 组色解析。
 * <p>
 * 组别以字符串存储 (见 {@link StructureData})；用户组存于文档
 * {@code userGroups} 表，内置组也可在该表中放颜色覆盖条目。
 * 本类保持纯 Java (无 Minecraft 依赖) 以便 JVM 单测。
 */
public final class StructureGroups {

    /** 未分组 (特殊值: 右键菜单设为该组 = 清除标记记录)。 */
    public static final String DEFAULT = "";
    /** 完成 */
    public static final String DONE = "done";
    /** 隐藏 */
    public static final String HIDDEN = "hidden";
    /** 特殊 */
    public static final String SPECIAL = "special";

    /** 内置组列表 (面板展示顺序)。 */
    public static final List<String> BUILTIN = List.of(DEFAULT, DONE, HIDDEN, SPECIAL);

    /** 内置组默认色 (ARGB); DEFAULT 无色 = 0。 */
    private static final Map<String, Integer> BUILTIN_COLORS = Map.of(
            DONE, 0xFF55FF55,
            HIDDEN, 0xFFA0A0A8,
            SPECIAL, 0xFFFFAA00);

    private StructureGroups() {
    }

    /** 组名翻译 key; 未知组回退为默认组。 */
    public static String translationKey(String group) {
        return switch (group) {
            case DONE -> "xsm.group.done";
            case HIDDEN -> "xsm.group.hidden";
            case SPECIAL -> "xsm.group.special";
            default -> "xsm.group.default";
        };
    }

    /** 是否内置组 (含未分组)。 */
    public static boolean isBuiltin(String group) {
        return DEFAULT.equals(group) || BUILTIN_COLORS.containsKey(group);
    }

    /** 内置组默认色; {@code group} 为默认组或未知组返回 0 (无色)。 */
    public static int builtinColor(String group) {
        var c = BUILTIN_COLORS.get(group);
        return c != null ? c : 0;
    }

    /**
     * 组色解析 (图标遮罩/文字着色): 文档覆盖条目优先, 其次内置默认色。
     * 返回 0 = 无色 (未分组或未设置)。alpha 通道即遮罩不透明度。
     */
    public static int colorOf(@Nullable StructureData doc, String group) {
        if (doc != null) {
            int c = doc.colorOf(group);
            if (c != 0)
                return c;
        }
        return builtinColor(group);
    }

    /** 强制 alpha = FF (文字着色用; 遮罩 alpha 语义仅用于图标)。 */
    public static int opaque(int argb) {
        return argb | 0xFF000000;
    }
}
