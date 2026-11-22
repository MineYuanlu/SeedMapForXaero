package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import xaero.map.gui.GuiMap;

/**
 * 种子地图侧面板: 三个垂直区块 (生物群系/结构/性能) 的协调器。
 * <p>
 * 区块实现见 {@link PanelSection} 及其子类 ({@link BiomeSection} /
 * {@link StructSection} / {@link PerfSection})。本类只负责:
 * 生命周期 (开关/GuiMap re-init 复用)、EditBox 焦点优先路由、
 * 事件按区块顺序分发 (y 推进单一来源 = render 每帧回写的区块 bottom)
 * 与 E2E gametest 钩子委托。
 */
public class SeedMapPanel {

    // 会话级 UI 状态: 窗口缩放 re-init 后保持; 地图界面关闭时由 XsmMainPanelMixin 复位
    public static boolean panelOpen;

    private final GuiMap screen;

    private final BiomeSection biomeSection = new BiomeSection();
    private final StructSection structSection = new StructSection();
    private final PerfSection perfSection = new PerfSection();
    /** 区块渲染/命中顺序 (自上而下, 与 y 推进链一致)。 */
    private final List<PanelSection> sections = List.of(biomeSection, structSection, perfSection);

    private int scrW, scrH;

    /** 当前 GuiMap 的面板实例 (onInit 注册; E2E gametest 取用)。 */
    private static SeedMapPanel activePanel;

    public static SeedMapPanel activePanel() {
        return activePanel;
    }

    public SeedMapPanel(GuiMap screen) {
        this.screen = screen;
    }

    public void toggleOpen() {
        panelOpen = !panelOpen;
        for (var s : sections) {
            if (panelOpen)
                s.onPanelOpened();
            else
                s.onPanelClosed();
        }
    }

    public void onInit(int width, int height) {
        this.scrW = width;
        this.scrH = height;
        activePanel = this;
        // recreate section EditBoxes (区块内部仅在面板开启时重建, 幂等)
        for (var s : sections)
            s.onInit(screen, width, height);
    }

    // ─── RENDER ──────────────────────────────────────────────

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!panelOpen)
            return;

        // 拖拽逐帧轮询 (GuiMap 不派发 mouseDragged, 各区块自管)
        for (var s : sections)
            s.tickDrag(mouseX);

        renderPanelBg(g);

        int y = PanelSection.PADDING;
        for (var s : sections)
            y = s.render(g, mouseX, mouseY, y) + PanelSection.SECTION_GAP;
    }

    private void renderPanelBg(GuiGraphicsExtractor g) {
        g.fill(0, 0, PanelSection.PANEL_WIDTH, scrH, 0x77_000000);
    }

    // ─── MOUSE ──────────────────────────────────────────────

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!panelOpen)
            return false;
        // any click ends a slider drag (原语义: 面板外点击同样复位, 性能区命中会重新置位)
        for (var s : sections)
            s.onAnyClick();

        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (mx > PanelSection.PANEL_WIDTH)
            return false;

        // handle EditBox clicks directly (sync screen focus) — 区块申报, 优先于区块命中
        for (var s : sections) {
            var f = s.fieldAt(mx, my);
            if (f != null) {
                screen.setFocused(f);
                return true;
            }
        }

        // clicking panel → unfocus EditBox and clear screen focus
        screen.setFocused(null);

        // 区块顺序命中; y 推进 = 上一区块 render 回写的 bottom + 区块间距
        int y = PanelSection.PADDING;
        for (var s : sections) {
            if (s.mouseClicked(mx, my, button, y))
                return true;
            y = s.bottom() + PanelSection.SECTION_GAP;
        }
        return true; // 面板内一律消费 (尾部兜底 = 性能区)
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (var s : sections)
            if (s.mouseReleased(button))
                return true;
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!panelOpen || mouseX > PanelSection.PANEL_WIDTH || mouseY < 0 || mouseY > scrH)
            return false;
        double dir = -scrollY;
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int y = PanelSection.PADDING;
        for (var s : sections) {
            if (s.mouseScrolled(mx, my, dir, y))
                return true;
            y = s.bottom() + PanelSection.SECTION_GAP;
        }
        return true; // 面板内一律消费 (性能区/图标区不可滚但也吞滚动, 原语义)
    }

    // ─── E2E gametest 钩子 (生产路径不调用) ─────────────────────

    /** 展开结构区 (面板截图前置状态; 保持生物群系折叠, 小屏下结构区才有可见空间)。 */
    public void testExpandSections() {
        structSection.setExpandedForTest();
    }

    /**
     * 展开/收起生物群系区 (E2E 面板截图前置状态)。展开帧每帧走
     * {@code BiomeSection 渲染 → CompatGui.blitSprite}（issue #32 崩溃路径）。
     */
    public void testSetBiomeExpanded(boolean expanded) {
        biomeSection.setExpandedForTest(expanded);
    }

    /** 切换结构区 Tab (0=类型 1=分组 2=图标)。 */
    public void testSelectStructTab(int tab) {
        structSection.selectTabForTest(tab);
    }

    /** 展开性能区 (E2E 截图用)。 */
    public void testExpandPerf() {
        perfSection.setExpandedForTest();
    }

    /** 新建用户组并展开其编辑器; 返回组名 (失败 null)。 */
    public String testCreateGroup() {
        return structSection.createGroupForTest();
    }

    /** 设置生物群系搜索文本并刷新过滤 (批量「只显搜索」前置)。 */
    public void testSetBiomeSearch(String text) {
        biomeSection.setSearchForTest(text);
    }

    /** 执行生物群系批量操作 (all/none/invert/only); 返回已知群系启用数 (断言用)。 */
    public int testBiomeBatch(String action) {
        return biomeSection.batchForTest(action);
    }

    /** 设置结构搜索文本并刷新过滤 (批量「只显搜索」前置)。 */
    public void testSetStructSearch(String text) {
        structSection.setSearchForTest(text);
    }

    /** 执行结构类型 tab 批量操作 (all/none/invert/only); 返回启用类型数 (断言用)。 */
    public int testStructBatch(String action) {
        return structSection.batchForTest(action);
    }
}
