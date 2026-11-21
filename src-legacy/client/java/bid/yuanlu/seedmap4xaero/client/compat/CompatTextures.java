package bid.yuanlu.seedmap4xaero.client.compat;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;

import xaero.lib.client.graphics.GpuTextureAndView;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.region.texture.RegionTexture;

/**
 * MC GPU 纹理/管线 API 门面（mc/1.21.11 单版本分支，直接强类型调用）。
 *
 * <p>
 * 本分支只支持 1.21.11，由 loom-remap 把编译期引用重映射到 intermediary，
 * 无需运行时反射扫描；master(26.x) 26.3 起 blaze3d 搬家 renderpearl 才需要
 * MethodHandle 缝。1.21.11 与 26.x 的语义差异集中处理：
 * <ul>
 * <li>{@code MultiTextureRenderTypeRenderer.begin} 1.21.11 取 {@code GpuTexture}
 *     （26.x 取 {@code GpuTextureView}）</li>
 * <li>{@code GuiRenderState} 挂层方法 1.21.11 为 {@code submitBlitToCurrentLayer}
 *     （26.x 为 {@code addBlitToCurrentLayer}）</li>
 * <li>{@code RegionTexture.DEFAULT_INTERNAL_FORMAT} 为 {@code TextureFormat}</li>
 * </ul>
 *
 * <p>
 * 纯 JVM 单测（{@code CellKeyTest} 等）加载本类无副作用：惰性调用渲染线程才触发
 * MC/Xaero 类初始化。
 */
public final class CompatTextures {

	private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/CompatTextures");

	private CompatTextures() {
	}

	private static volatile @Nullable GpuTextureAndView placeholder;

	// ------------------------------------------------------------------
	// 公开 API（业务侧只允许经由本类触碰 GPU 纹理）
	// ------------------------------------------------------------------

	/**
	 * 创建 GPU 纹理并上传 CPU 像素，包装为 Xaero 渲染所需的 {@link GpuTextureAndView}。
	 *
	 * @param label      纹理调试名
	 * @param usage      GpuTexture usage 位标志（1.21.11 占位纹理用 1、瓦片用 15）
	 * @param side       正方形边长（像素）
	 * @param abgrPixels 行优先 ABGR 像素（{@code setPixelABGR} 语义），长度 = side²
	 */
	public static @NotNull GpuTextureAndView createUploadTexture(@NotNull String label, int usage, int side,
			@NotNull int[] abgrPixels) {
		try {
			final NativeImage img = new NativeImage(NativeImage.Format.RGBA, side, side, false);
			for (int i = 0; i < abgrPixels.length; i++) {
				img.setPixelABGR(i % side, i / side, abgrPixels[i]);
			}
			final GpuDevice device = RenderSystem.getDevice();
			final GpuTexture gpuTexture = device.createTexture(label, usage,
					RegionTexture.DEFAULT_INTERNAL_FORMAT, side, side, 1, 1);
			final CommandEncoder encoder = device.createCommandEncoder();
			encoder.writeToTexture(gpuTexture, img);
			img.close();
			final GpuTextureView view = device.createTextureView(gpuTexture);
			return new GpuTextureAndView(gpuTexture, view);
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures.createUploadTexture failed: " + label, e);
		}
	}

	/** 1×1 灰色占位纹理（瓦片异步生成期间显示），进程内缓存。 */
	public static @NotNull GpuTextureAndView placeholderTexture() {
		GpuTextureAndView p = placeholder;
		if (p != null)
			return p;
		p = createUploadTexture("xsm_placeholder", 1, 1, new int[] { 0xFF808080 });
		placeholder = p;
		return p;
	}

	/** 取精灵图（TextureManager 常驻纹理）的 view+sampler 并组装 {@link TextureSetup}。 */
	public static @NotNull TextureSetup singleTexture(@NotNull AbstractTexture texture) {
		return TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler());
	}

	/**
	 * 向 GUI 渲染状态当前层追加一个带纹理 blit（图标/精灵图绘制）。等价于
	 * {@code guiRenderState.submitBlitToCurrentLayer(new BlitRenderState(
	 * RenderPipelines.GUI_TEXTURED, setup, pose, x0, y0, x1, y1, u0, u1, v0, v1, tint, null))}
	 * （1.21.11；26.x 为 {@code addBlitToCurrentLayer}）。
	 */
	public static void blitIcon(@NotNull GuiRenderState guiRenderState, @NotNull TextureSetup setup,
			@NotNull Matrix3x2f pose, int x0, int y0, int x1, int y1,
			float u0, float u1, float v0, float v1, int tint) {
		guiRenderState.submitBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED,
				setup, pose, x0, y0, x1, y1, u0, u1, v0, v1, tint, (ScreenRectangle) null));
	}

	/**
	 * 瓦片 quad 绘制入口。1.21.11 的 {@code begin} 参数为 {@code GpuTexture}
	 * （26.x 为 {@code GpuTextureView}），故取 {@code tex.texture}。
	 */
	public static @NotNull BufferBuilder beginMultiTexture(@NotNull MultiTextureRenderTypeRenderer renderer,
			@NotNull GpuTextureAndView tex) {
		return renderer.begin(tex.texture);
	}

	// ------------------------------------------------------------------
	// CompatGui 共用的管线常量
	// ------------------------------------------------------------------

	/** {@code RenderPipelines.GUI_TEXTURED}（供 CompatGui blit/blitSprite 复用）。 */
	static @NotNull com.mojang.blaze3d.pipeline.RenderPipeline guiTexturedPipeline() {
		return RenderPipelines.GUI_TEXTURED;
	}
}
