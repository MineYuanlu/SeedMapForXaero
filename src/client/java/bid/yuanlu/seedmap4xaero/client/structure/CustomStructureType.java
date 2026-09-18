package bid.yuanlu.seedmap4xaero.client.structure;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import net.minecraft.resources.Identifier;

/**
 * 数据包注入的动态结构类型 (路线 A: 结构位置预测)。
 * <p>
 * 由 {@code DatapackStructures} 在世界切换时从数据包 structure_set
 * 解析构造, 经 {@link StructureTypes#setCustomTypes} 注册、C 侧
 * {@code xsmSetCustomStructures} 注入。预测 = random_spread 网格 +
 * 多结构集合加权掷骰取首选, 不做 biome tag 校验 (已知假阳性)。
 * <p>
 * 不可变; 世界切换时整体替换 (引用随之刷新, 旧实例不再被注册表持有)。
 * {@code hidden} 类型 (c:hide_from_map) 仍注入 C 表 (保证同集合其它结构的
 * 掷骰正确) 但不进 {@link StructureTypes#all()} (不查询不渲染)。
 */
public final class CustomStructureType implements StructureInfo {

    /** 与 C 侧 XSM_CUSTOM_STRUCT_MIN_ID/MAX_ID 一致。 */
    public static final int MIN_ID = 100;
    public static final int MAX_ID = 1000;

    private static final IntList NO_VARIANTS = IntLists.EMPTY_LIST;

    private final int id;
    /** 完整数据包结构 id (如 "terralith:spire")。 */
    private final String key;
    /** 面板/悬浮显示名 (key 的 prettify 形式, 如 "Spire")。 */
    private final String displayName;
    /** 注入时给定的网格配置 (salt/spacing/separation/spread/dim)。 */
    private final StructureType.Config config;
    /** 所属 structure_set 中的权重 (仅掷骰用, 注入后不再变化)。 */
    private final int weight;
    /** c:worldgen/structure_icons.json 给的图标物品 (M3 渲染用); null = 通用图标。 */
    private final @Nullable Identifier iconItem;
    /** c:hide_from_map: 不查询不渲染 (仅保留在 C 表保证掷骰正确)。 */
    private final boolean hidden;
    /**
     * 动态 sprite sheet 槽位, 由 {@code CustomStructureIcons.ensureLoaded()} 在
     * 渲染线程赋值 (0 = 通用回退图标); getSpriteIndex 消费。
     */
    private volatile int iconSlot;

    public CustomStructureType(int id, @NotNull String key, @NotNull String displayName,
            @NotNull StructureType.Config config, int weight,
            @Nullable Identifier iconItem, boolean hidden) {
        this.id = id;
        this.key = key;
        this.displayName = displayName;
        this.config = config;
        this.weight = weight;
        this.iconItem = iconItem;
        this.hidden = hidden;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean isDefaultEnabled() {
        return true;
    }

    @Override
    public int maxRegionHide() {
        return StructureType.MAX_REGION_HIDE;
    }

    @Override
    public float prob() {
        return 0f;
    }

    @Override
    public StructureType.@NotNull Config config() {
        return config;
    }

    @Override
    public int getSpriteIndex(int variant) {
        return iconSlot;
    }

    @Override
    public @NotNull String translationKey() {
        return "xsm.structure.custom";
    }

    @Override
    public @NotNull String variantTranslationKey(int variant) {
        return translationKey();
    }

    @Override
    public IntList getVariants() {
        return NO_VARIANTS;
    }

    @Override
    public boolean isCustom() {
        return true;
    }

    @Override
    public boolean isHidden() {
        return hidden;
    }

    @Override
    public @NotNull String localizedName() {
        return displayName;
    }

    /** 所属集合中的权重 (>= 1)。 */
    public int weight() {
        return weight;
    }

    /** c:structure_icons 指定的图标物品; null = 回退通用图标。 */
    public @Nullable Identifier iconItem() {
        return iconItem;
    }

    /** 动态 sheet 槽位赋值 (仅 CustomStructureIcons 渲染线程调用)。 */
    void setIconSlot(int slot) {
        this.iconSlot = slot;
    }
}
