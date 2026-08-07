package bid.yuanlu.seedmap4xaero.client.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import bid.yuanlu.seedmap4xaero.client.accessor.DropDownWidgetTitleAccessor;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.structure.StructureIcons;
import bid.yuanlu.seedmap4xaero.client.structure.StructureRightClick;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.GuiRightClickMenu;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.misc.Misc;
import xaero.map.mods.SupportMods;

/**
 * 结构图标的右键交互: 右键命中结构图标 → 合成菜单 (Xaero 默认地图菜单 + 追加
 * 结构标题/转换路标/高亮项)。取消 Xaero 自身的地图菜单以免出现两个菜单。
 * <p>
 * 命中判定复用 {@link StructureIcons} (与 {@link StructureOverlayMixin} 渲染完全一致
 * 的过滤 + 坐标几何), 在 {@code mouseClicked} (按下) 时快照目标, 在 {@code mapClicked}
 * (释放) 时打开合成菜单并取消 Xaero 自身的地图菜单。
 */
@Mixin(GuiMap.class)
public class StructureClickMixin {
    @Shadow
    private double cameraX, cameraZ, scale, screenScale;

    @Shadow
    private MapProcessor mapProcessor;

    @Shadow
    private GuiRightClickMenu rightClickMenu;

    @Unique
    private static final int ICON_SIZE = 20;

    /** 按下时命中的结构图标; {@code mapClicked} 时消费并清空。 */
    @Unique
    private StructureRightClick xsm$rightClickTarget;

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void xsm$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> cir) {
        xsm$rightClickTarget = null;
        if (event.button() != 1 || mapProcessor == null || !StructureIcons.enabled())
            return;
        final Minecraft mc = Minecraft.getInstance();
        final double winX = Misc.getMouseX(mc, SupportMods.vivecraft);
        final double winY = Misc.getMouseY(mc, SupportMods.vivecraft);
        xsm$rightClickTarget = xsm$hitTest(winX / screenScale, winY / screenScale);
    }

    @Inject(method = "mapClicked", at = @At("HEAD"), cancellable = true)
    private void xsm$onMapClicked(int button, int x, int y, CallbackInfo ci) {
        if (button == 1 && xsm$rightClickTarget != null) {
            final StructureRightClick target = xsm$rightClickTarget;
            xsm$rightClickTarget = null;
            // 与 Xaero handleRightClick 一致: 先关旧菜单再开新菜单
            if (rightClickMenu != null)
                rightClickMenu.setClosed(true);
            final GuiMap guiMap = (GuiMap) (Object) this;
            rightClickMenu = GuiRightClickMenu.getMenu(xsm$combinedBase(target, guiMap),
                    guiMap, (int) (x / screenScale), (int) (y / screenScale), 150);
            // 结构标题是我们追加段的第一项, 索引 = Xaero 选项数; 把该行注册为标题行,
            // drawSlot 的 fill 颜色即被 @ModifyArg 换成 selectedBackground (标题背景色, 灰底)。
            // "Choose an Option" 仍是 selected (索引 0), 天然用 selectedBackground → 两行都灰。
            final int titleIndex = guiMap.getRightClickOptions().size();
            ((DropDownWidgetTitleAccessor) rightClickMenu).xsm$addTitleRow(titleIndex);
            ci.cancel();
        }
    }

    /**
     * 合成右键菜单元素: Xaero 默认地图菜单 (GuiMap.getRightClickOptions) 在前,
     * 我们的结构菜单项 (标题 + 转路标 + 高亮) 追加在最后。
     * <p>
     * 选项选取按列表位置 (GuiRightClickMenu.selectId 用 actionOptions.get(id)),
     * 且每个 RightClickOption 自带 target 做 isRightClickValid, 拼接后各自回调正常;
     * RightClickOption 内部 index 字段菜单不使用, 重叠无影响。
     */
    @Unique
    private IRightClickableElement xsm$combinedBase(StructureRightClick structure, GuiMap guiMap) {
        return new IRightClickableElement() {
            @Override
            public ArrayList<RightClickOption> getRightClickOptions() {
                ArrayList<RightClickOption> options = new ArrayList<>();
                options.addAll(guiMap.getRightClickOptions());
                options.addAll(structure.getRightClickOptions());
                return options;
            }

            @Override
            public boolean isRightClickValid() {
                return true;
            }

            @Override
            public int getRightClickTitleBackgroundColor() {
                return guiMap.getRightClickTitleBackgroundColor();
            }
        };
    }

    /**
     * 命中检测: 与渲染相同的屏幕坐标, 取距光标最近且 Chebyshev 距离 < 半图标宽度的图标。
     */
    @Unique
    private StructureRightClick xsm$hitTest(double scaledMouseX, double scaledMouseY) {
        final ResourceKey<Level> dimId = xsm$currentDimId();
        if (dimId == null)
            return null;
        final double dimScale = xsm$currentDimScale();

        final Minecraft mc = Minecraft.getInstance();
        final double invScale = 1.0 / screenScale;
        final double guiW = mc.getWindow().getWidth() * invScale;
        final double guiH = mc.getWindow().getHeight() * invScale;
        final float iconHalf = ICON_SIZE * ServerConfig.getStructureIconSize() * 0.5f;

        final StructureIcons.Transform t = new StructureIcons.Transform(
                cameraX, cameraZ, scale, invScale, guiW, guiH);

        final double[] best = { iconHalf };
        final StructureRightClick[] found = { null };
        StructureIcons.forEachVisible((type, variant, blockX, blockZ, guiX, guiZ) -> {
            final double dist = Math.max(Math.abs(scaledMouseX - guiX),
                    Math.abs(scaledMouseY - guiZ));
            if (dist < best[0]) {
                best[0] = dist;
                found[0] = new StructureRightClick((GuiMap) (Object) this,
                        type, variant, blockX, blockZ, dimId, dimScale);
            }
        }, t);
        return found[0];
    }

    @Unique
    private ResourceKey<Level> xsm$currentDimId() {
        final var world = mapProcessor.getMapWorld();
        return world == null ? null : world.getCurrentDimensionId();
    }

    @Unique
    private double xsm$currentDimScale() {
        final var world = mapProcessor.getMapWorld();
        final var dim = world == null ? null : world.getCurrentDimension();
        if (dim == null)
            return 1.0;
        return dim.calculateDimScale(mapProcessor.getWorldDimensionTypeRegistry());
    }
}
