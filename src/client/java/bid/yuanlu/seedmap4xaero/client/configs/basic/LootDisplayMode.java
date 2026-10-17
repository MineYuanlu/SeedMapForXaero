package bid.yuanlu.seedmap4xaero.client.configs.basic;

/**
 * 战利品预览的显示模式。
 * <p>
 * 两个正交维度:
 * <ul>
 * <li><b>交互</b>: 速览 (鼠标悬停图标显示, 点击进入固定态) vs
 *     详情 (悬停图标即自动进入固定态, 与速览的唯一区别, 无需点击)。</li>
 * <li><b>布局</b>: 单容器 (翻页箭头切换) vs 平铺 (所有容器同时显示)。</li>
 * </ul>
 */
public enum LootDisplayMode {

    /** 速览模式: 悬停显示, 点击固定, 单容器翻页。 */
    QUICK_PEEK,
    /** 详情模式: 悬停即自动固定, 单容器翻页。 */
    DETAIL,
    /** 平铺速览: 悬停显示, 点击固定, 所有容器平铺。 */
    TILED_PEEK,
    /** 平铺详情: 悬停即自动固定, 所有容器平铺。 */
    TILED_DETAIL;

    /** 平铺布局 (同时显示所有容器)。 */
    public boolean isTiled() {
        return this == TILED_PEEK || this == TILED_DETAIL;
    }

    /** 详情交互 (悬停即自动进入固定态)。 */
    public boolean isDetail() {
        return this == DETAIL || this == TILED_DETAIL;
    }

    /** i18n key, 如 {@code xsm.gui.panel.loot_mode.quick_peek}。 */
    public String translationKey() {
        return "xsm.gui.panel.loot_mode." + name().toLowerCase();
    }
}
