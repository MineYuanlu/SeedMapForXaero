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
 *
 * <p>
 * 重载匹配按<strong>精确形参形状</strong>（含每个基本类型位）进行，绝不只看参数个数：
 * 26.1/26.3 上 {@code blitSprite} 同时存在 7 参 {@code (…, float alpha)} 与
 * {@code (…, int color)} 重载，{@code getMethods()} 顺序未定义（issue #32：
 * 26.3 上先命中 int 版 → {@code invoke(float)} 抛 {@code WrongMethodTypeException}）。
 * 优先取 float（alpha）版本；仅有 int（color）版本时降级为
 * {@code color = alpha<<24 | 0xFFFFFF}（ARGB tint，语义等价）。
 */
public final class CompatGui {

	private CompatGui() {
	}

	private static volatile @Nullable MethodHandle tooltipHandle;
	private static volatile boolean tooltipTakesBoolean;
	private static volatile @Nullable MethodHandle blitTextureHandle;
	private static volatile @Nullable MethodHandle blitSpriteHandle;
	private static volatile boolean blitSpriteTakesColor;

	/**
	 * 等价于 {@code g.tooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, null)}
	 * （26.1）/ {@code ..., null, false}（26.3）。
	 */
	public static void tooltip(@NotNull GuiGraphicsExtractor g, @NotNull Font font,
			@NotNull List<ClientTooltipComponent> lines, int x, int y) {
		try {
			final MethodHandle handle = tooltipHandle != null ? tooltipHandle : xsm$resolve();
			if (tooltipTakesBoolean) {
				handle.invoke(g, font, lines, x, y, DefaultTooltipPositioner.INSTANCE, (Identifier) null, false);
			} else {
				handle.invoke(g, font, lines, x, y, DefaultTooltipPositioner.INSTANCE, (Identifier) null);
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

	/**
	 * 等价于 {@code g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, alpha)}；
	 * 若运行时仅有 {@code (…, int color)} 重载，则换算
	 * {@code color = (int)(alpha*255)<<24 | 0xFFFFFF}（ARGB tint，语义等价）。
	 */
	public static void blitSprite(@NotNull GuiGraphicsExtractor g, @NotNull Identifier sprite,
			int x, int y, int w, int h, float alpha) {
		try {
			if (blitSpriteHandle == null)
				xsm$resolveBlit();
			if (blitSpriteTakesColor) {
				final int a = Math.min(255, Math.max(0, Math.round(alpha * 255f)));
				final int color = a << 24 | 0xFFFFFF;
				blitSpriteHandle.invoke(g, CompatTextures.guiTexturedPipeline(), sprite, x, y, w, h, color);
			} else {
				blitSpriteHandle.invoke(g, CompatTextures.guiTexturedPipeline(), sprite, x, y, w, h, alpha);
			}
		} catch (Throwable e) {
			throw new RuntimeException("CompatGui.blitSprite failed", e);
		}
	}

	private static synchronized void xsm$resolveBlit() throws Throwable {
		if (blitTextureHandle != null && blitSpriteHandle != null)
			return;
		final Method[] methods = GuiGraphicsExtractor.class.getMethods();
		blitTextureHandle = MethodHandles.lookup().unreflect(xsm$findBlitTexture(methods));
		final BlitSpriteMatch sprite = xsm$findBlitSprite(methods);
		blitSpriteHandle = MethodHandles.lookup().unreflect(sprite.method());
		blitSpriteTakesColor = sprite.takesColor();
	}

	/**
	 * blitSprite 匹配结果：{@code takesColor=true} 表示命中 {@code (…, int color)} 重载，
	 * 调用方需把 alpha 换算成 ARGB color。
	 */
	record BlitSpriteMatch(@NotNull Method method, boolean takesColor) {
	}

	/**
	 * 按精确形参形状找 {@code blit(pipeline, Identifier, int, int, float, float, int×4)}
	 * （10 参）。pipeline 参数为搬家类型（26.1 blaze3d / 26.3 renderpearl），不校验。
	 */
	static @NotNull Method xsm$findBlitTexture(@NotNull Method[] methods) throws NoSuchMethodException {
		for (final Method m : methods) {
			final Class<?>[] pt = m.getParameterTypes();
			if (m.getName().equals("blit") && pt.length == 10 && pt[1] == Identifier.class
					&& pt[2] == int.class && pt[3] == int.class
					&& pt[4] == float.class && pt[5] == float.class
					&& pt[6] == int.class && pt[7] == int.class && pt[8] == int.class && pt[9] == int.class) {
				return m;
			}
		}
		throw new NoSuchMethodException(
				"GuiGraphicsExtractor.blit(pipeline,Identifier,int,int,float,float,int×4) not found");
	}

	/**
	 * 按精确形参形状找 7 参 {@code blitSprite(pipeline, Identifier, int×4, 末参)}：
	 * 优先 {@code float}（alpha，26.1/26.3 均存在）；否则退到 {@code int}（color）。
	 * 只看参数个数会让 {@code getMethods()} 的未定义顺序决定命中哪个重载（issue #32）。
	 */
	static @NotNull BlitSpriteMatch xsm$findBlitSprite(@NotNull Method[] methods) throws NoSuchMethodException {
		Method color = null;
		for (final Method m : methods) {
			final Class<?>[] pt = m.getParameterTypes();
			if (!m.getName().equals("blitSprite") || pt.length != 7 || pt[1] != Identifier.class
					|| pt[2] != int.class || pt[3] != int.class || pt[4] != int.class || pt[5] != int.class) {
				continue;
			}
			if (pt[6] == float.class)
				return new BlitSpriteMatch(m, false);
			if (pt[6] == int.class)
				color = m;
		}
		if (color != null)
			return new BlitSpriteMatch(color, true);
		throw new NoSuchMethodException(
				"GuiGraphicsExtractor.blitSprite(pipeline,Identifier,int×4,float|int) not found");
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
