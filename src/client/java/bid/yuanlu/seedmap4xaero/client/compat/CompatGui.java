package bid.yuanlu.seedmap4xaero.client.compat;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * GUI 绘制 API 门面（mc/1.21.11 单版本分支，直接强类型调用）。
 *
 * <p>
 * 本分支只支持 1.21.11，由 loom-remap 把编译期引用重映射到 intermediary，
 * 无需运行时反射扫描；master(26.x) 的跨版本反射门面在此分支不必要。
 *
 * <p>
 * 1.21.11 形状：{@code renderTooltip(Font, List<ClientTooltipComponent>, x, y, positioner, id)}
 * 6 参；{@code blit(pipeline, id, x, y, u, v, w, h, texW, texH)} 10 参；
 * {@code blitSprite(pipeline, id, x, y, w, h, int ARGB tint)} 第 7 参为 ARGB tint
 * （RGB=白, A=alpha×255）。
 */
public final class CompatGui {

	private CompatGui() {
	}

	/**
	 * 等价于 {@code g.renderTooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null)}
	 * （1.21.11）/ {@code g.tooltip(…)}（26.x）。
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

	/** 等价于 {@code g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, alpha)}。 */
	public static void blitSprite(@NotNull GuiGraphics g, @NotNull Identifier sprite,
			int x, int y, int w, int h, float alpha) {
		// 1.21.11: 第 7 参 = ARGB tint（传入 BlitRenderState 的顶点色），
		// float alpha → 白色 RGB × alpha 通道
		final int tint = ((int) (alpha * 255.0F + 0.5F) << 24) | 0xFFFFFF;
		g.blitSprite(CompatTextures.guiTexturedPipeline(), sprite, x, y, w, h, tint);
	}
}
