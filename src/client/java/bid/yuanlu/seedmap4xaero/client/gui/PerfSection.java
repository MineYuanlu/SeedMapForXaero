package bid.yuanlu.seedmap4xaero.client.gui;

import bid.yuanlu.seedmap4xaero.client.compat.CompatInput;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;

/**
 * 性能区 (perf_config.json 全局配置): header + 滑条/复选行。
 * <p>
 * 面板尾部兜底区块: 区域内点击一律消费 ({@link #mouseClicked} 恒 true)。
 * 几何单一来源 = {@link PerfLayout}。无滚动、无 EditBox。
 */
class PerfSection extends PanelSection {

    // 会话级 UI 状态: re-init 后保持
    private static boolean perfExpanded;

    /** 滑条 id: 1=线程 2=缓存 3=预生成半径 4=磁盘上限。 */
    private static final int PERF_SLIDER_THREADS = 1;
    private static final int PERF_SLIDER_CACHE = 2;
    private static final int PERF_SLIDER_RADIUS = 3;
    /** TODO(disk-cache): 磁盘缓存未实现，暂不渲染/命中；保留分支供后续接入。 */
    private static final int PERF_SLIDER_DISK = 4;
    private static final int PERF_SLIDER_H = 12;
    /** 性能区滑条标签固定宽度 (标签列)。 */
    private static final int PERF_TRACK_X0 = PADDING + 60;

    /** 拖拽中的滑条 id; -1 = 无 (松开时 flush)。 */
    private static int perfSliderDrag = -1;

    /** 性能区行几何 (单一来源: render / mouseClicked 共用)。 */
    private record PerfLayout(int contentY, int threadsY, int cacheY, int prefetchY,
            int pregenY, int radiusY,
            // TODO(disk-cache): 磁盘缓存未实现，暂不展示。
            // int diskY, int diskMaxY,
            int debugY, int bottom) {
    }

    private PerfLayout perfLayout(int headerY) {
        int contentY = headerY + HEADER_H + PADDING;
        int y = contentY;
        int threadsY = y;
        y += PERF_SLIDER_H;
        int cacheY = y;
        y += PERF_SLIDER_H;
        int prefetchY = y;
        y += ITEM_H;
        int pregenY = y;
        y += ITEM_H;
        int radiusY = y;
        y += PERF_SLIDER_H;
        // TODO(disk-cache): 磁盘缓存尚未实现，暂不展示开关与上限滑条；
        // 保留 PerfConfig.diskCache* / PERF_SLIDER_DISK 供后续接入。
        // int diskY = y;
        // y += ITEM_H;
        // int diskMaxY = y;
        // y += PERF_SLIDER_H;
        int debugY = y;
        y += ITEM_H;
        return new PerfLayout(contentY, threadsY, cacheY, prefetchY, pregenY,
                radiusY, debugY, y);
    }

    @Override
    void onAnyClick() {
        perfSliderDrag = -1; // 性能区命中会重新置位
    }

    // ─── RENDER ──────────────────────────────────────────────

    @Override
    int renderContent(GuiGraphicsExtractor g, int mx, int my, int headerY) {
        boolean hoverHdr = PanelUi.hitHeader(mx, my, headerY);
        if (hoverHdr)
            g.fill(PADDING, headerY, PANEL_WIDTH - PADDING, headerY + HEADER_H, 0x22_FFFFFF);

        String label = I18n.get("xsm.gui.panel.perf_header");
        g.text(font, label, PADDING + 2, headerY + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        String arrow = perfExpanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        g.text(font, arrow, arrX, headerY + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        if (!perfExpanded)
            return headerY + HEADER_H;

        var P = perfLayout(headerY);
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.min(PerfConfig.generationThreads(), cores);
        renderPerfSlider(g, mx, my, P.threadsY(), PERF_SLIDER_THREADS,
                threads / (float) cores,
                threads == 0 ? I18n.get("xsm.gui.panel.perf_threads_auto") : String.valueOf(threads), true);
        int mb = PerfConfig.cacheCapacityMB();
        renderPerfSlider(g, mx, my, P.cacheY(), PERF_SLIDER_CACHE,
                (mb - 64) / 448f, mb + "MB", true);
        renderPerfCheckboxRow(g, mx, my, P.prefetchY(), "xsm.gui.panel.perf_prefetch",
                PerfConfig.prefetchEnabled());
        renderPerfCheckboxRow(g, mx, my, P.pregenY(), "xsm.gui.panel.perf_pregen",
                PerfConfig.pregenEnabled());
        int radius = PerfConfig.pregenRadiusBlocks();
        renderPerfSlider(g, mx, my, P.radiusY(), PERF_SLIDER_RADIUS,
                Integer.numberOfTrailingZeros(radius / 1024) / 6f,
                String.valueOf(radius), PerfConfig.pregenEnabled());
        // TODO(disk-cache): 磁盘缓存尚未实现，暂不展示。
        // renderPerfCheckboxRow(g, mx, my, P.diskY(), "xsm.gui.panel.perf_disk",
        //         PerfConfig.diskCacheEnabled());
        // int dmb = PerfConfig.diskCacheMaxMB();
        // renderPerfSlider(g, mx, my, P.diskMaxY(), PERF_SLIDER_DISK,
        //         Integer.numberOfTrailingZeros(dmb / 32) / 7f,
        //         dmb + "MB", PerfConfig.diskCacheEnabled());
        renderPerfCheckboxRow(g, mx, my, P.debugY(), "xsm.gui.panel.perf_debug",
                PerfConfig.debugOverlay());
        return P.bottom();
    }

    /** 性能区滑条行 (标签 + 轨道 + thumb + 右侧值)。 */
    private void renderPerfSlider(GuiGraphicsExtractor g, int mx, int my, int y, int id,
            float t, String valueText, boolean enabled) {
        String label = I18n.get(switch (id) {
            case PERF_SLIDER_THREADS -> "xsm.gui.panel.perf_threads";
            case PERF_SLIDER_CACHE -> "xsm.gui.panel.perf_cache";
            case PERF_SLIDER_RADIUS -> "xsm.gui.panel.perf_radius";
            default -> "xsm.gui.panel.perf_disk_max";
        });
        int textCol = enabled ? 0xFFFFFFFF : 0xFF666666;
        g.text(font, label, PADDING, y + (PERF_SLIDER_H - font.lineHeight) / 2, textCol);
        int valW = font.width(valueText);
        int trackEnd = PANEL_WIDTH - PADDING - valW - 5;
        int trackY = y + (PERF_SLIDER_H - 4) / 2;
        g.fill(PERF_TRACK_X0, trackY, trackEnd, trackY + 4, 0xFF444444);
        int thumbX = PERF_TRACK_X0 + (int) ((trackEnd - PERF_TRACK_X0 - 6)
                * Math.max(0, Math.min(1, t)));
        boolean hover = enabled && mx >= PERF_TRACK_X0 && mx <= trackEnd
                && my >= y && my <= y + PERF_SLIDER_H;
        g.fill(thumbX, y, thumbX + 6, y + PERF_SLIDER_H,
                hover || perfSliderDrag == id ? 0xFFAAAAAA : 0xFF888888);
        g.text(font, valueText, PANEL_WIDTH - PADDING - valW,
                y + (PERF_SLIDER_H - font.lineHeight) / 2, textCol);
    }

    private void renderPerfCheckboxRow(GuiGraphicsExtractor g, int mx, int my, int y,
            String labelKey, boolean checked) {
        boolean hover = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                && my >= y && my <= y + ITEM_H;
        PanelUi.renderCheckbox(g, PADDING, y + (ITEM_H - 9) / 2, checked, hover);
        g.text(font, I18n.get(labelKey), PADDING + 12,
                y + (ITEM_H - font.lineHeight) / 2,
                checked ? 0xFFFFFFFF : 0xFF888888);
    }

    // ─── MOUSE ──────────────────────────────────────────────

    @Override
    boolean mouseClicked(int mx, int my, int button, int headerY) {
        // 尾部兜底区块: 始终消费 (面板内点击)
        if (PanelUi.hitHeader(mx, my, headerY)) {
            PanelUi.playClickSound();
            perfExpanded = !perfExpanded;
            return true;
        }
        if (!perfExpanded)
            return true;
        var P = perfLayout(headerY);
        if (hitPerfRow(mx, my, P.prefetchY())) {
            PerfConfig.setPrefetchEnabled(!PerfConfig.prefetchEnabled());
            PerfConfig.flush();
            return true;
        }
        if (hitPerfRow(mx, my, P.pregenY())) {
            PerfConfig.setPregenEnabled(!PerfConfig.pregenEnabled());
            PerfConfig.flush();
            return true;
        }
        // TODO(disk-cache): 磁盘缓存开关未展示。
        // if (hitPerfRow(mx, my, P.diskY())) {
        //     PerfConfig.setDiskCacheEnabled(!PerfConfig.diskCacheEnabled());
        //     PerfConfig.flush();
        //     return true;
        // }
        if (hitPerfRow(mx, my, P.debugY())) {
            PerfConfig.setDebugOverlay(!PerfConfig.debugOverlay());
            PerfConfig.flush();
            return true;
        }
        // 滑条: 半径在预生成关闭时不可交互
        if (PerfConfig.pregenEnabled() && hitPerfSliderRow(mx, my, P.radiusY(), PERF_SLIDER_RADIUS))
            return true;
        // TODO(disk-cache): 磁盘上限滑条未展示。
        // if (PerfConfig.diskCacheEnabled() && hitPerfSliderRow(mx, my, P.diskMaxY(), PERF_SLIDER_DISK))
        //     return true;
        hitPerfSliderRow(mx, my, P.threadsY(), PERF_SLIDER_THREADS);
        hitPerfSliderRow(mx, my, P.cacheY(), PERF_SLIDER_CACHE);
        return true;
    }

    private static boolean hitPerfRow(int mx, int my, int rowY) {
        if (my >= rowY && my <= rowY + ITEM_H) {
            PanelUi.playClickSound();
            return true;
        }
        return false;
    }

    private boolean hitPerfSliderRow(int mx, int my, int rowY, int id) {
        if (my < rowY || my >= rowY + PERF_SLIDER_H)
            return false;
        PanelUi.playClickSound();
        perfSliderDrag = id;
        applyPerfSlider(mx, id);
        return true;
    }

    /** 拖拽/点击轨道时按 mouseX 更新对应配置 (只改内存, mouseReleased 时 flush)。 */
    private void applyPerfSlider(int mx, int id) {
        int[] tb = perfTrackBounds(id);
        float t = Math.max(0, Math.min(1,
                (mx - tb[0]) / (float) Math.max(1, tb[1] - tb[0] - 6)));
        switch (id) {
            case PERF_SLIDER_THREADS -> {
                int cores = Runtime.getRuntime().availableProcessors();
                PerfConfig.setGenerationThreads(perfStopIndex(t, cores + 1));
            }
            case PERF_SLIDER_CACHE -> PerfConfig.setCacheCapacityMB(64 + perfStopIndex(t, 15) * 32);
            case PERF_SLIDER_RADIUS -> PerfConfig.setPregenRadiusBlocks(1024 << perfStopIndex(t, 7));
            case PERF_SLIDER_DISK -> PerfConfig.setDiskCacheMaxMB(32 << perfStopIndex(t, 8));
        }
    }

    /** 滑条轨道边界 [start, end] (与 renderPerfSlider 推进一致; 值文本宽随当前配置)。 */
    private int[] perfTrackBounds(int id) {
        String valueText = switch (id) {
            case PERF_SLIDER_THREADS -> {
                int cores = Runtime.getRuntime().availableProcessors();
                int threads = Math.min(PerfConfig.generationThreads(), cores);
                yield threads == 0 ? I18n.get("xsm.gui.panel.perf_threads_auto") : String.valueOf(threads);
            }
            case PERF_SLIDER_CACHE -> PerfConfig.cacheCapacityMB() + "MB";
            case PERF_SLIDER_RADIUS -> String.valueOf(PerfConfig.pregenRadiusBlocks());
            default -> PerfConfig.diskCacheMaxMB() + "MB";
        };
        return new int[] { PERF_TRACK_X0, PANEL_WIDTH - PADDING - font.width(valueText) - 5 };
    }

    /** t ∈ [0,1] → 离散档位 [0, stops-1]。 */
    private static int perfStopIndex(float t, int stops) {
        return Math.max(0, Math.min(stops - 1, Math.round(t * (stops - 1))));
    }

    @Override
    void tickDrag(int mouseX) {
        // perf slider drag: GuiMap 不派发 mouseDragged，逐帧轮询
        if (perfSliderDrag > 0)
            applyPerfSlider(mouseX, perfSliderDrag);
    }

    @Override
    boolean mouseReleased(int button) {
        if (button == CompatInput.MOUSE_LEFT && perfSliderDrag > 0) {
            perfSliderDrag = -1;
            PerfConfig.flush(); // 拖拽结束落盘
            return true;
        }
        return false;
    }

    // ─── E2E gametest 钩子 (生产路径不调用) ─────────────────────

    /** 展开性能区 (E2E 截图用)。 */
    void setExpandedForTest() {
        perfExpanded = true;
    }
}
