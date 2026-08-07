package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.ArrayList;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.SupportMods;

/**
 * 结构图标的右键菜单元素。
 * <p>
 * 复用 Xaero 的 {@link IRightClickableElement} 机制: 菜单由
 * {@code GuiRightClickMenu.getMenu} 创建后自动注册到 GuiMap 的 openDropdown,
 * 渲染/点击/关闭全部由 ScreenBase 接管, 无需额外注入。
 */
public final class StructureRightClick implements IRightClickableElement {

    /** 结构无 Y 坐标, 用 Xaero 的 32767 约定标记 "无 Y"。 */
    public static final int NO_Y = 32767;

    /** 菜单标题栏背景色 (深绿)。 */
    public static final int TITLE_BACKGROUND_COLOR = 0xFF2A5C3A;

    private final GuiMap guiMap;
    private final StructureType type;
    private final int variant;
    private final int blockX;
    private final int blockZ;
    private final ResourceKey<Level> dimId;
    private final double dimScale;

    public StructureRightClick(GuiMap guiMap, StructureType type, int variant,
            int blockX, int blockZ, ResourceKey<Level> dimId, double dimScale) {
        this.guiMap = guiMap;
        this.type = type;
        this.variant = variant;
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.dimId = dimId;
        this.dimScale = dimScale;
    }

    public StructureType getType() {
        return type;
    }

    public int getVariant() {
        return variant;
    }

    public int getBlockX() {
        return blockX;
    }

    public int getBlockZ() {
        return blockZ;
    }

    public ResourceKey<Level> getDimId() {
        return dimId;
    }

    @Override
    public ArrayList<RightClickOption> getRightClickOptions() {
        ArrayList<RightClickOption> options = new ArrayList<>();
        // 标题行 (onAction 空, 纯显示)
        options.add(new RightClickOption(displayName(), options.size(), this) {
            @Override
            public void onAction(Screen screen) {
            }
        });
        // 转换为路标: 仅在 minimap 存在时提供 (createWaypoint 打开 GuiAddWaypoint)
        if (SupportMods.minimap()) {
            options.add(new RightClickOption("xsm.menu.to_waypoint", options.size(), this) {
                @Override
                public void onAction(Screen screen) {
                    SupportMods.xaeroMinimap.createWaypoint(guiMap, blockX, NO_Y, blockZ,
                            dimScale, true);
                }
            });
        }
        // 在世界中高亮 / 取消高亮
        final boolean highlighted = HighlightedStructures.contains(dimId, blockX, blockZ, type, variant);
        options.add(new RightClickOption(
                highlighted ? "xsm.menu.unhighlight" : "xsm.menu.highlight",
                options.size(), this) {
            @Override
            public void onAction(Screen screen) {
                HighlightedStructures.toggle(dimId, blockX, blockZ, type, variant);
            }
        });
        return options;
    }

    @Override
    public boolean isRightClickValid() {
        return true;
    }

    @Override
    public int getRightClickTitleBackgroundColor() {
        return TITLE_BACKGROUND_COLOR;
    }

    /** 结构显示名, 如 "Village (Plains)"。 */
    private String displayName() {
        String name = I18n.get(type.translationKey());
        String vk = type.variantTranslationKey(variant);
        if (vk != null)
            name += " (" + I18n.get(vk) + ")";
        return name;
    }
}
