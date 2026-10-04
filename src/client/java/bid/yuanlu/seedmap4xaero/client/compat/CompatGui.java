package bid.yuanlu.seedmap4xaero.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * GUI 绘制 API 的跨版本兼容门面。
 *
 * <p>
 * {@link GuiGraphicsExtractor#tooltip} 的签名跨版本不稳定：26.1 为 6 参
 * {@code tooltip(Font, List, int, int, ClientTooltipPositioner, Identifier)}，
 * 26.3 追加尾部 {@code boolean}（语义为单行 tooltip 的 -2px 垂直微调开关，
 * 多行场景传 false 与原行为一致）。参数类型 FQN（{@code ClientTooltipPositioner} 等）
 * 两端一致，仅参数个数变化，故按"方法名 + 形参形状"扫描重载后反射调用。
 *
 * <p>
 * {@code blit}/{@code blitSprite} 带 pipeline 的重载同理：{@code RenderPipeline}
 * 参数类型 26.3 从 {@code blaze3d.pipeline} 迁到 {@code renderpearl.api.pipeline}，
 * 强类型调用点描述符跨版本断裂，必须经反射（pipeline 常量复用
 * {@link CompatTextures} 的解析缓存）。
 */
public final class CompatGui {

	private CompatGui() {
	}

	private static volatile @Nullable MethodHandle tooltipHandle;
	private static volatile boolean tooltipTakesBoolean;
	private static volatile @Nullable MethodHandle blitTextureHandle;
	private static volatile @Nullable MethodHandle blitSpriteHandle;

	/**
	 * 等价于 {@code g.tooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null)}
	 * （26.1）/ {@code ..., null, false}（26.3）。
	 */
	public static void tooltip(@NotNull GuiGraphicsExtractor g, @NotNull Font font,
			@NotNull List<ClientTooltipComponent> lines, int x, int y) {
		try {
			final MethodHandle handle = tooltipHandle != null ? tooltipHandle : xsm$resolve();
			if (tooltipTakesBoolean) {
				handle.invoke(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null, false);
			} else {
				handle.invoke(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null);
			}
		} catch (Throwable e) {
			throw new RuntimeException("CompatGui.tooltip failed", e);
		}
	}

	/**
	 * 等价于 {@code g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, texW, texH)}。
	 */
	public static void blitTexture(@NotNull GuiGraphicsExtractor g, @NotNull Identifier tex,
			int x, int y, int u, int v, int w, int h, int texW, int texH) {
		try {
			if (blitTextureHandle == null)
				xsm$resolveBlit();
			blitTextureHandle.invoke(g, CompatTextures.guiTexturedPipeline(), tex,
					x, y, (float) u, (float) v, w, h, texW, texH);
		} catch (Throwable e) {
			throw new RuntimeException("CompatGui.blitTexture failed", e);
		}
	}

	/** 等价于 {@code g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, alpha)}。 */
	public static void blitSprite(@NotNull GuiGraphicsExtractor g, @NotNull Identifier sprite,
			int x, int y, int w, int h, float alpha) {
		try {
			if (blitSpriteHandle == null)
				xsm$resolveBlit();
			blitSpriteHandle.invoke(g, CompatTextures.guiTexturedPipeline(), sprite, x, y, w, h, alpha);
		} catch (Throwable e) {
			throw new RuntimeException("CompatGui.blitSprite failed", e);
		}
	}

	private static synchronized void xsm$resolveBlit() throws Throwable {
		if (blitTextureHandle != null && blitSpriteHandle != null)
			return;
		blitTextureHandle = MethodHandles.lookup().unreflect(xsm$findOverlayMethod("blit", 10));
		blitSpriteHandle = MethodHandles.lookup().unreflect(xsm$findOverlayMethod("blitSprite", 7));
	}

	/** 形参形状: blit → (pipeline, Identifier, int, int, float, float, int×4)；blitSprite → (…, int×4, float)。 */
	private static @NotNull Method xsm$findOverlayMethod(@NotNull String name, int paramCount)
			throws NoSuchMethodException {
		for (final Method m : GuiGraphicsExtractor.class.getMethods()) {
			final Class<?>[] pt = m.getParameterTypes();
			if (m.getName().equals(name) && pt.length == paramCount && pt[1] == Identifier.class) {
				return m;
			}
		}
		throw new NoSuchMethodException("GuiGraphicsExtractor." + name + "/" + paramCount + " not found");
	}

	private static @NotNull MethodHandle xsm$resolve() throws Throwable {
		synchronized (CompatGui.class) {
			if (tooltipHandle != null)
				return tooltipHandle;
			for (final int paramCount : new int[] { 6, 7 }) {
				for (final Method m : GuiGraphicsExtractor.class.getMethods()) {
					final Class<?>[] pt = m.getParameterTypes();
					if (m.getName().equals("tooltip") && pt.length == paramCount
							&& pt[0] == Font.class && pt[1] == List.class
							&& pt[2] == int.class && pt[3] == int.class) {
						tooltipTakesBoolean = paramCount == 7;
						return tooltipHandle = MethodHandles.lookup().unreflect(m);
					}
				}
			}
			throw new NoSuchMethodException("GuiGraphicsExtractor.tooltip(Font,List,int,int,...) not found");
		}
	}
}
