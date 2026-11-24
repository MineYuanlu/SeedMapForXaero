package bid.yuanlu.seedmap4xaero.client.gui;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import xaero.map.gui.GuiMap;

/**
 * 面板区块协议 (SeedMapPanel 的垂直区块: 生物群系/结构/性能)。
 * <p>
 * 宿主按序 walk 三个入口 ({@link #render} / {@link #mouseClicked} /
 * {@link #mouseScrolled})，几何推进的单一来源是 render 每帧回写的
 * {@link #bottom()}（把结构区 {@code structRenderedBottom} 技巧推广为全区块契约:
 * 点击/滚动侧不重算内容底界，折叠态 bottom = headerY + HEADER_H）。
 * <p>
 * 会话级状态（展开/滚动/搜索文本）保持 static，窗口缩放 re-init 后语义不变。
 */
abstract class PanelSection {

    static final int PANEL_WIDTH = 220;
    static final int HEADER_H = 20;
    static final int ITEM_H = 12;
    static final int PADDING = 5;
    static final int MIN_VISIBLE_ITEMS = 4;
    static final int MAX_VISIBLE_ITEMS = 18;
    /** 区块之间的垂直间距。 */
    static final int SECTION_GAP = 5;

    protected GuiMap screen;
    protected Minecraft mc;
    protected Font font;
    protected int scrW, scrH;

    /** 宿主 onInit 转发 (每次 GuiMap init 幂等调用; EditBox 仅在面板开启时重建)。 */
    void onInit(GuiMap screen, int scrW, int scrH) {
        this.screen = screen;
        this.scrW = scrW;
        this.scrH = scrH;
        this.mc = Minecraft.getInstance();
        this.font = mc.font;
    }

    /** 渲染本区 (模板): 回写 {@link #bottom()} 供点击/滚动路由推进。 */
    final int render(GuiGraphicsExtractor g, int mx, int my, int headerY) {
        int bottom = renderContent(g, mx, my, headerY);
        this.lastBottom = bottom;
        return bottom;
    }

    /** 内容渲染, 返回内容底界。 */
    abstract int renderContent(GuiGraphicsExtractor g, int mx, int my, int headerY);

    /**
     * 命中处理 (含本区 EditBox 已由宿主优先路由); 返回 true = 消费。
     * 未命中返回 false, 宿主推进到下一区块。
     */
    boolean mouseClicked(int mx, int my, int button, int headerY) {
        return false;
    }

    /** 滚动处理; 返回 true = 消费。dir = -scrollY。 */
    boolean mouseScrolled(int mx, int my, double dir, int headerY) {
        return false;
    }

    /** 本区可命中的 EditBox (宿主在做区块命中前优先路由焦点); 无则 null。 */
    @Nullable EditBox fieldAt(int mx, int my) {
        return null;
    }

    /** 面板开启 (toggleOpen) 时刷新派生状态 (filter/滑条值)。 */
    void onPanelOpened() {
    }

    /** 面板关闭时解焦本区 EditBox。 */
    void onPanelClosed() {
    }

    /** 任意面板点击的拖拽互斥复位 (原语义: 任意点击清图标滑条/性能滑条拖拽; 颜色滑条除外)。 */
    void onAnyClick() {
    }

    /** 渲染帧逐帧轮询拖拽 (GuiMap 不派发 mouseDragged)。 */
    void tickDrag(int mouseX) {
    }

    /** 释放拖拽 (宿主 mouseReleased 转发); 返回 true = 消费。 */
    boolean mouseReleased(int button) {
        return false;
    }

    private int lastBottom;

    /** 最近一帧渲染的内容底界 (render 每帧回写; 点击/滚动路由的推进单一来源)。 */
    final int bottom() {
        return lastBottom;
    }
}
