package bid.yuanlu.seedmap4xaero.client.structure;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.resources.language.I18n;

/**
 * 结构类型的统一抽象: 原版 26 枚举 ({@link StructureType}) 与数据包注入的
 * 动态类型 ({@link CustomStructureType}) 的公共接口。
 * <p>
 * id 约定: [0, {@link StructureType#FEATURE_NUM}) 为原版 (与 C 侧 cubiomes
 * 枚举逐值一致); [100, 1000) 为数据包自定义结构 (C 侧 {@code
 * XSM_CUSTOM_STRUCT_MIN_ID/MAX_ID})。
 * <p>
 * 渲染/缓存/持久化链路以 id 透传, 仅在闭合点 (byId 选择、集合遍历、面板列表)
 * 经 {@link StructureTypes} 解析回本接口。
 */
public interface StructureInfo {

    /** 结构 id (见接口注释的 id 约定)。 */
    int id();

    /** 结构 key: 原版为 snake_case 名; 自定义为完整数据包 id (如 "terralith:spire")。 */
    String key();

    /** 无用户配置时的默认启用状态。 */
    boolean isDefaultEnabled();

    /** 普通结构专用上限: 视口内 region 数超过则整类跳过。 */
    int maxRegionHide();

    /**
     * 每区块实际放置概率 (raw RNG 命中率 × 群系约束通过率); 仅稀疏类型
     * (regionSize=1) 大于 0, -1 表示未定义。自定义结构恒走普通网格路径 (0)。
     */
    float prob();

    /** native 侧结构配置; null = 无配置 (如要塞/地物, 或未注入)。 */
    @Nullable StructureType.Config config();

    /** 变种码对应的精灵图索引; 需先执行 {@code StructureType.init()}。 */
    int getSpriteIndex(int variant);

    /** 整体翻译 key (仅原版类型有 lang 条目)。 */
    @NotNull String translationKey();

    /** 变种码对应的翻译 key; 无变种的类型返回整体 key。 */
    @NotNull String variantTranslationKey(int variant);

    /** 支持的变种码表 (仅 UI 显示遍历用); 自定义结构恒为空。 */
    IntList getVariants();

    /** 是否为数据包注入的动态类型 (渲染/名称路径分流)。 */
    boolean isCustom();

    /** 是否对用户隐藏 (c:hide_from_map; 隐藏类型不查询不渲染)。 */
    default boolean isHidden() {
        return false;
    }

    /** 本地化显示名: 原版走 lang; 自定义为数据包 id 的 prettify 形式。 */
    default @NotNull String localizedName() {
        return I18n.get(translationKey());
    }
}
