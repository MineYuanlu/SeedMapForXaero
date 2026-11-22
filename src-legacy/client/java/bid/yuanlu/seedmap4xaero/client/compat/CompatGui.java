package bid.yuanlu.seedmap4xaero.client.compat;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * GUI 绘制 API 门面（legacy 线 ≤1.21.11，直接强类型调用）。
 *
 * <p>
 * legacy 线由 loom-remap 把编译期引用重映射到 intermediary，mojmap 名字的
 * 反射串在 intermediary 运行时必然失效，故 modern 线 (26.x unobf) 的
 * 跨版本反射门面在此不必要——全部直接强类型调用，重映射交给 loom。
 *
 * <p>
 * 1.21.11 形状：{@code renderTooltip(Font, List<ClientTooltipComponent>, x, y, positioner, id)}
 * 6 参；{@code blit(pipeline, id, x, y, u, v, w, h, texW, texH)} 10 参；
 * {@code blitSprite(pipeline, id, x, y, w, h, int ARGB tint)} 第 7 参为 ARGB tint
 * （RGB=白, A=alpha×255）。
 *
 * <p>
 * 公开 API 与 modern 版 (src-modern) 保持一致，新增方法时两侧同步。
 */
public final class CompatGui {

	private CompatGui() {
	}

	/**
	 * 跨版本读取当前 screen：1.21.11 为 {@code Minecraft.screen} 公开字段
	 * （26.x 的 {@code Minecraft.gui.screen()} / 字段分支见 modern 版）。
	 */
	public static @Nullable Screen currentScreen(@NotNull Minecraft mc) {
		return mc.screen;
	}

	/**
	 * {@link #blitSprite} 成功调用计数（E2E 断言用：群系区展开帧必经此路径，
	 * 计数不增长 = issue #32 渲染路径未被驱动）。生产开销为一次 volatile 自增。
	 */
	public static volatile long blitSpriteCallCount;

	/**
	 * 等价于 {@code g.renderTooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null)}
	 * （1.21.11）/ {@code g.tooltip(…)}（26.x，见 modern 版）。
	 */
	public static void tooltip(@NotNull GuiGraphics g, @NotNull Font font,
			@NotNull List<ClientTooltipComponent> lines, int x, int y) {
		g.renderTooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, (Identifier) null);
	}

	/** 等价于 {@code g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, texW, texH)}。 */
	public static void blitTexture(@NotNull GuiGraphics g, @NotNull Identifier tex,
			int x, int y, int u, int v, int w, int h, int texW, int texH) {
		g.blit(CompatTextures.guiTexturedPipeline(), tex,
				x, y, (float) u, (float) v, w, h, texW, texH);
	}

	/**
	 * 等价于 {@code g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, alpha)}。
	 * 1.21.11 第 7 参 = ARGB tint（传入 BlitRenderState 的顶点色），
	 * float alpha（约定 [0,1]）→ 白色 RGB × alpha 通道换算（clamp 防 >1 时溢出到 RGB 位）。
	 */
	public static void blitSprite(@NotNull GuiGraphics g, @NotNull Identifier sprite,
			int x, int y, int w, int h, float alpha) {
		final int a = Math.min(255, Math.max(0, (int) (alpha * 255.0F + 0.5F)));
		final int tint = (a << 24) | 0xFFFFFF;
		g.blitSprite(CompatTextures.guiTexturedPipeline(), sprite, x, y, w, h, tint);
		blitSpriteCallCount++;
	}
}
