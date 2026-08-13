package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.configs.LootDisplayMode;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm.ChestLoot;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 结构战利品悬浮框的共享状态 + 生命周期管理 (被 StructureOverlayMixin /
 * StructureClickMixin 共同使用)。
 * <p>
 * 状态机:
 * <ul>
 * <li><b>悬浮快速查看</b>: 鼠标悬停战利品结构图标 → 渲染 ChestLootWidget
 *     (非固定, 鼠标移出图标即关闭)。
 * <li><b>左键固定</b>: 悬浮时左键点击图标 → 固定 widget, 鼠标可移入容器
 *     查看物品 tooltip / 翻页。
 * <li><b>滑出关闭</b>: 固定态鼠标滑出容器矩形 + 一圈缓冲像素 → 退出固定,
 *     若仍在图标上则回落为悬浮快速查看。
 * </ul>
 * 战利品查询结果按 (structureType, blockX, blockZ) 缓存, 世界切换时由
 * {@link #clearCache()} 清空。
 */
public final class LootPreviewState {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/LootPreviewState");

    private LootPreviewState() {
    }

    /** 固定态下允许鼠标进入容器操作 (翻页/物品 tooltip) 的缓冲像素半径。 */
    public static final int PIN_BUFFER = ChestLootWidget.HOVER_BUFFER;

    /** 图标右下角到容器左上角的间隙像素。 */
    private static final int GAP = 4;

    /** 图标半宽像素 (结构图标基础尺寸 20 × 缩放 / 2)。 */
    private static final int ICON_HALF = 10;

    /** C 端 xsmQueryStructureLoot 支持的结构类型 id 集合。 */
    private static final Set<Integer> LOOT_SUPPORTED = Set.of(
            1,  // Desert_Pyramid
            2,  // Jungle_Pyramid
            4,  // Igloo
            7,  // Shipwreck
            10, // Outpost
            11, // Ruined_Portal
            12, // Ruined_Portal_N
            14, // Treasure
            18, // Fortress
            19, // Bastion
            20, // End_City
            25  // Stronghold
    );

    private static final java.util.Map<Long, List<ChestLoot>> LOOT_CACHE = new java.util.HashMap<>();

    /** 当前活跃的悬浮框; null = 无。 */
    private static @Nullable ChestLootWidget activeWidget;
    /** 是否左键固定 (详情模式下悬停即自动固定)。 */
    private static boolean pinned;
    /** 悬浮目标 (用于固定回落与点击命中)。 */
    private static StructureType hoverType;
    private static int hoverBlockX, hoverBlockZ;
    private static double hoverGuiX, hoverGuiZ;
    /** 当前屏幕 (GUI 缩放后) 尺寸, 用于容器右下角定位的钳制。 */
    private static double hoverGuiW, hoverGuiH;
    /** 当前 widget 对应的结构坐标 key; 未变化时避免逐帧重建 (保留翻页状态)。 */
    private static long widgetKey = Long.MIN_VALUE;

    /** 该结构类型是否支持战利品查询。 */
    public static boolean isLootSupported(StructureType type) {
        return LOOT_SUPPORTED.contains(type.id);
    }

    /** 生成缓存的 key (structureType, blockX, blockZ) 压缩为 long。 */
    private static long key(int type, int blockX, int blockZ) {
        return ((long) type << 42) | (((long) blockX & 0x7FFFFFL) << 21)
                | ((long) blockZ & 0x7FFFFFL);
    }

    /** 结构坐标 key (不含类型), 用于判断悬停目标是否变化。 */
    private static long posKey(int blockX, int blockZ) {
        return (((long) blockX & 0x7FFFFFL) << 21) | ((long) blockZ & 0x7FFFFFL);
    }

    /** 查询 (带缓存)。返回 null = native 错误 / 不支持。 */
    public static @Nullable List<ChestLoot> queryLoot(StructureType type, int blockX, int blockZ) {
        long k = key(type.id, blockX, blockZ);
        synchronized (LOOT_CACHE) {
            if (LOOT_CACHE.containsKey(k)) {
                return LOOT_CACHE.get(k);
            }
        }
        List<ChestLoot> loot = Xsm.queryStructureLoot(type.id, blockX, blockZ);
        synchronized (LOOT_CACHE) {
            LOOT_CACHE.put(k, loot);
        }
        return loot;
    }

    /** 世界/配置切换时清空战利品缓存并关闭悬浮框。 */
    public static void clearCache() {
        synchronized (LOOT_CACHE) {
            LOOT_CACHE.clear();
        }
        activeWidget = null;
        pinned = false;
        hoverType = null;
        widgetKey = Long.MIN_VALUE;
    }

    /** 当前是否有可渲染的悬浮框。 */
    public static boolean hasWidget() {
        return activeWidget != null;
    }

    /** 当前活跃的悬浮框 (可能为 null)。 */
    public static @Nullable ChestLootWidget widget() {
        return activeWidget;
    }

    /** 当前是否左键固定。 */
    public static boolean isPinned() {
        return pinned;
    }

    /**
     * 悬浮时鼠标命中战利品结构图标 → 建立/更新快速查看 widget。
     * 若当前为固定态则保持固定 (不因悬停图标而重开悬浮)。
     * <p>
     * 详情模式 ({@link LootDisplayMode#isDetail()}): 悬停即自动进入固定态,
     * 与速览模式的唯一区别, 无需点击即可移入容器浏览/翻页。
     */
    public static void onHover(StructureType type, int blockX, int blockZ,
            double guiX, double guiZ, double guiW, double guiH) {
        if (pinned && !ServerConfig.getLootDisplayMode().isDetail()) {
            return;
        }
        hoverType = type;
        hoverBlockX = blockX;
        hoverBlockZ = blockZ;
        hoverGuiX = guiX;
        hoverGuiZ = guiZ;
        hoverGuiW = guiW;
        hoverGuiH = guiH;
        pinned = ServerConfig.getLootDisplayMode().isDetail();
        // 悬停目标未变化时保留现有 widget (含翻页状态), 仅更新固定态。
        if (activeWidget != null && posKey(blockX, blockZ) == widgetKey) {
            return;
        }
        rebuildWidget();
    }

    /** 悬浮结束时 (鼠标不在图标上) 关闭悬浮框; 固定态不受影响。 */
    public static void onHoverEnd() {
        if (!pinned) {
            activeWidget = null;
            hoverType = null;
            widgetKey = Long.MIN_VALUE;
        }
    }

    /** 关闭悬浮框并解除固定 (面板切换模式/关闭预览时调用)。 */
    public static void close() {
        activeWidget = null;
        pinned = false;
        hoverType = null;
        widgetKey = Long.MIN_VALUE;
    }

    /** 左键点击当前悬浮的图标 → 固定。返回是否消费了该点击。 */
    public static boolean tryPin() {
        if (pinned || activeWidget == null) {
            return false;
        }
        pinned = true;
        rebuildWidget();
        return true;
    }

    /** 固定态下滑出容器+缓冲 → 解除固定, 回落悬浮。 */
    public static void onUnpinIfOutside(double mouseX, double mouseY) {
        if (!pinned || activeWidget == null) {
            return;
        }
        // 鼠标仍在图标上 → 保持固定 (详情模式自动固定时鼠标常驻图标上,
        // 若在此解除会在 onHover/onUnpin 间逐帧抖动, 且移入容器时可能误关)。
        if (isHoverIcon(mouseX, mouseY)) {
            return;
        }
        if (!activeWidget.isMouseOverWithBuffer(mouseX, mouseY, PIN_BUFFER)) {
            pinned = false;
            activeWidget = null;
            hoverType = null;
        }
    }

    private static boolean isHoverIcon(double mouseX, double mouseY) {
        if (hoverType == null) {
            return false;
        }
        double dx = mouseX - hoverGuiX;
        double dy = mouseY - hoverGuiZ;
        return Math.max(Math.abs(dx), Math.abs(dy)) <= 10;
    }

    private static void rebuildWidget() {
        if (hoverType == null) {
            return;
        }
        List<ChestLoot> loot = queryLoot(hoverType, hoverBlockX, hoverBlockZ);
        if (loot == null || loot.isEmpty()) {
            activeWidget = null;
            return;
        }
        final LootDisplayMode mode = ServerConfig.getLootDisplayMode();
        final boolean tiled = mode.isTiled();
        final int w = tiled ? ChestLootWidget.totalWidth(loot.size()) : ChestLootWidget.CONTAINER_WIDTH;
        final int h = tiled ? ChestLootWidget.totalHeight(loot.size()) : ChestLootWidget.CONTAINER_HEIGHT;
        // 容器左上角 = 图标右下角 + 间隙 (不重叠); 钳制到屏幕内可见
        int posX = (int) (hoverGuiX + ICON_HALF * ServerConfig.getStructureIconSize() + GAP);
        int posY = (int) (hoverGuiZ + ICON_HALF * ServerConfig.getStructureIconSize() + GAP);
        if (hoverGuiW > 0) {
            posX = Math.max(0, Math.min(posX, (int) (hoverGuiW - w)));
            posY = Math.max(0, Math.min(posY, (int) (hoverGuiH - h)));
        }
        activeWidget = new ChestLootWidget(posX, posY, hoverType, loot, tiled);
        widgetKey = posKey(hoverBlockX, hoverBlockZ);
    }

    /** 供鼠标点击路由: 点击命中 widget 翻页按钮时消费。 */
    public static boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ChestLootWidget w = activeWidget;
        if (w == null || !w.isMouseOver(event.x(), event.y())) {
            return false;
        }
        return w.mouseClicked(event, doubleClick);
    }
}
