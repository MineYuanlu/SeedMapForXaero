package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.util.List;

/**
 * 内置标记分组常量。组别以字符串存储 (见 {@link StructureData}),
 * 为后续用户自定义组预留扩展空间。
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
}
