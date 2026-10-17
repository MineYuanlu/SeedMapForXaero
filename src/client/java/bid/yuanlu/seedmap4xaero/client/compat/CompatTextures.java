package bid.yuanlu.seedmap4xaero.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;

import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;

import xaero.lib.client.graphics.GpuTextureAndView;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;

/**
 * MC GPU 纹理/管线 API 的跨版本兼容门面。
 *
 * <p>
 * MC 26.3 把 {@code com.mojang.blaze3d.textures.*} 与 {@code blaze3d.systems.GpuDevice}
 * 整体迁到 {@code com.mojang.renderpearl.api.*}（{@code TextureFormat}→{@code GpuFormat}），
 * 且 {@code RenderPipeline}（{@code RenderPipelines.GUI_TEXTURED} 的字段类型）与
 * {@code CommandEncoder} 同步搬家。JVM 方法/字段描述符包含类型 FQN，因此 universal jar
 * （只编一个版本）对这些 API 的<strong>任何强类型调用点</strong>（含返回类型如
 * {@code RenderSystem.getDevice()}、参数类型如 {@code TextureSetup.singleTexture(view, sampler)}、
 * 构造器如 {@code new BlitRenderState(pipeline, ...)}）都会在另一端运行时
 * {@link NoSuchMethodError} / {@link NoSuchFieldError}。本类把这些缝全部收编为
 * 惰性解析的 MethodHandle/Constructor（首次使用时在渲染线程解析，逐帧零额外解析）。
 *
 * <p>
 * 可安全保持强类型的 FQN（26.1↔26.3 均未变）：{@link TextureSetup}、
 * {@link BlitRenderState}、{@link GuiRenderState}、{@link AbstractTexture}、
 * {@link NativeImage}、Xaero {@link GpuTextureAndView}、{@link Matrix3x2f}。
 * 26.3 若再迁移这批 FQN，只需同步更新本类 import 与解析逻辑。
 *
 * <p>
 * 解析策略全部基于<strong>运行时类型扫描</strong>（{@code getMethod(...).getReturnType()}、
 * 按实参运行时类匹配重载），不写死任何一端独有的类型 FQN；Xaero 侧
 * {@code RegionTexture.DEFAULT_INTERNAL_FORMAT} 沿用 CellCache 时代的反射模式
 * （字段名全 Xaero 线稳定，字段类型随 MC 线变化）。
 *
 * <p>
 * 惰性解析：渲染线程首次调用才触发 MC/Xaero 类加载，纯 JVM 单测
 * （{@code CellKeyTest} 等）加载本类无副作用。
 */
public final class CompatTextures {

	private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/CompatTextures");

	private CompatTextures() {
	}

	private static volatile @Nullable Cache cache;

	private static volatile @Nullable GpuTextureAndView placeholder;

	// ------------------------------------------------------------------
	// 公开 API（业务侧只允许经由本类触碰 GPU 纹理）
	// ------------------------------------------------------------------

	/**
	 * 创建 GPU 纹理并上传 CPU 像素，包装为 Xaero 渲染所需的 {@link GpuTextureAndView}。
	 *
	 * @param label      纹理调试名
	 * @param usage      GpuTexture usage 位标志（26.1 占位纹理用 1、瓦片用 15）
	 * @param side       正方形边长（像素）
	 * @param abgrPixels 行优先 ABGR 像素（{@code setPixelABGR} 语义），长度 = side²
	 */
	public static @NotNull GpuTextureAndView createUploadTexture(@NotNull String label, int usage, int side,
			@NotNull int[] abgrPixels) {
		final Cache c = cache();
		try {
			final NativeImage img = new NativeImage(NativeImage.Format.RGBA, side, side, false);
			for (int i = 0; i < abgrPixels.length; i++) {
				img.setPixelABGR(i % side, i / side, abgrPixels[i]);
			}
			final Object device = c.getDevice.invoke();
			final Object gpuTexture = c.createTexture.invoke(device, label, usage, c.internalFormat,
					side, side, 1, 1);
			final Object encoder = c.createCommandEncoder.invoke(device);
			c.writeToTexture.invoke(encoder, gpuTexture, img);
			img.close();
			final Object view = c.createTextureView.invoke(device, gpuTexture);
			return xsm$wrap(c, gpuTexture, view);
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
		final Cache c = cache();
		try {
			return xsm$singleTexture(c,
					c.textureViewGetter.invoke(texture),
					c.samplerGetter.invoke(texture));
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures.singleTexture failed", e);
		}
	}

	/**
	 * 向 GUI 渲染状态当前层追加一个带纹理 blit（图标/精灵图绘制）。
	 * 等价于 26.1 写法
	 * {@code guiRenderState.addBlitToCurrentLayer(new BlitRenderState(
	 * RenderPipelines.GUI_TEXTURED, setup, pose, x0, y0, x1, y1, u0, u1, v0, v1, tint, null))}
	 * —— 26.3 起 {@code RenderPipeline} 类型搬家，构造点必须经反射。
	 */
	public static void blitIcon(@NotNull GuiRenderState guiRenderState, @NotNull TextureSetup setup,
			@NotNull Matrix3x2f pose, int x0, int y0, int x1, int y1,
			float u0, float u1, float v0, float v1, int tint) {
		final Cache c = cache();
		try {
			final Object blit = c.blitCtor.newInstance(c.guiTexturedPipeline, setup, pose,
					x0, y0, x1, y1, u0, u1, v0, v1, tint, null);
			guiRenderState.addBlitToCurrentLayer((BlitRenderState) blit);
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures.blitIcon failed", e);
		}
	}

	/**
	 * 瓦片 quad 绘制入口：等价于 {@code renderer.begin(tex.view)}。
	 *
	 * <p>
	 * {@code GpuTextureAndView.view} 字段与 {@code MultiTextureRenderTypeRenderer.begin}
	 * 参数的类型都是 MC 搬家类型（26.1 {@code blaze3d.textures.GpuTextureView} →
	 * 26.3 {@code renderpearl.api.textures.GpuTextureView}），字段/方法描述符跨版本断裂，
	 * 必须经反射。
	 */
	public static @NotNull BufferBuilder beginMultiTexture(@NotNull MultiTextureRenderTypeRenderer renderer,
			@NotNull GpuTextureAndView tex) {
		final Cache c = cache();
		try {
			return BufferBuilder.class.cast(
					c.multiTextureBegin.invoke(renderer, c.textureAndViewFieldGetter.invoke(tex)));
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures.beginMultiTexture failed", e);
		}
	}

	// ------------------------------------------------------------------
	// 反射缝解析（首次使用时一次性完成）
	// ------------------------------------------------------------------

	private static @NotNull Cache cache() {
		Cache c = cache;
		if (c != null)
			return c;
		return cache = xsm$resolve();
	}

	/** {@code RenderPipelines.GUI_TEXTURED}（类型随 26.3 renderpearl 迁移，供 CompatGui 复用）。 */
	static Object guiTexturedPipeline() {
		return cache().guiTexturedPipeline;
	}

	private static @NotNull GpuTextureAndView xsm$wrap(Cache c, Object gpuTexture, Object view) {
		try {
			return (GpuTextureAndView) c.gpuTextureAndViewCtor.newInstance(gpuTexture, view);
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures: GpuTextureAndView 构造失败", e);
		}
	}

	private static @NotNull TextureSetup xsm$singleTexture(Cache c, Object view, Object sampler) {
		try {
			return TextureSetup.class.cast(c.singleTexture.invoke(view, sampler));
		} catch (Throwable e) {
			throw new RuntimeException("CompatTextures: TextureSetup.singleTexture 失败", e);
		}
	}

	private static @NotNull Cache xsm$resolve() {
		try {
			final MethodHandles.Lookup lookup = MethodHandles.lookup();

			// 1. device / encoder 的运行时类型链（不写死任一端的 FQN）
			final Class<?> deviceClass = Class.forName("com.mojang.blaze3d.systems.RenderSystem")
					.getMethod("getDevice").getReturnType();
			final Class<?> encoderClass = deviceClass.getMethod("createCommandEncoder").getReturnType();

			// 2. createTexture 重载：internal format 参数类型由 Xaero 字段运行时类型决定
			//    （26.1 TextureFormat / 26.2 GpuFormat / 26.3 renderpearl GpuFormat）
			final Class<?> rt = Class.forName("xaero.map.region.texture.RegionTexture");
			final Field fmtField = rt.getField("DEFAULT_INTERNAL_FORMAT");
			final Object internalFormat = fmtField.get(null);
			final Method createTexture = xsm$findMethod(deviceClass, "createTexture", 7,
					// 区分 (String label, …) 与 (Supplier<String> label, …) 两个重载
					m -> m.getParameterTypes()[0] == String.class
							&& m.getParameterTypes()[2] == internalFormat.getClass());
			final Method createTextureView = xsm$findMethod(deviceClass, "createTextureView", 1, m -> true);
			final Method writeToTexture = xsm$findMethod(encoderClass, "writeToTexture", 2,
					m -> m.getParameterTypes()[1] == NativeImage.class);

			// 3. Xaero 包装类型构造器（参数为两端搬家类型，按返回类型可赋值匹配）
			final Class<?> gpuTextureClass = createTexture.getReturnType();
			final Class<?> gpuTextureViewClass = createTextureView.getReturnType();
			Constructor<?> gtaCtor = null;
			for (final Constructor<?> ctor : GpuTextureAndView.class.getConstructors()) {
				final Class<?>[] pt = ctor.getParameterTypes();
				if (pt.length == 2 && pt[0].isAssignableFrom(gpuTextureClass)
						&& pt[1].isAssignableFrom(gpuTextureViewClass)) {
					gtaCtor = ctor;
					break;
				}
			}
			if (gtaCtor == null)
				throw new NoSuchMethodException("GpuTextureAndView 2-arg ctor not found");

			// 4. GUI 管线：AbstractTexture 取 view/sampler、TextureSetup 组装、
			//    GUI_TEXTURED 管线常量与 BlitRenderState 13 参构造器
			final Method texViewGetter = AbstractTexture.class.getMethod("getTextureView");
			final Method samplerGetter = AbstractTexture.class.getMethod("getSampler");
			final Method multiTextureBegin = xsm$findMethod(
					MultiTextureRenderTypeRenderer.class, "begin", 1, m -> true);
			final Field textureAndViewField = GpuTextureAndView.class.getField("view");
			final Method singleTexture = xsm$findMethod(TextureSetup.class, "singleTexture", 2, m -> true);
			final Field pipelineField = RenderPipelines.class.getField("GUI_TEXTURED");
			Constructor<?> blitCtor = null;
			for (final Constructor<?> ctor : BlitRenderState.class.getConstructors()) {
				if (ctor.getParameterTypes().length == 13) {
					blitCtor = ctor;
					break;
				}
			}
			if (blitCtor == null)
				throw new NoSuchMethodException("BlitRenderState 13-arg ctor not found");

			LOGGER.debug("CompatTextures cache resolved (device={}, encoder={})",
					deviceClass.getName(), encoderClass.getName());
			return new Cache(
					lookup.unreflect(Class.forName("com.mojang.blaze3d.systems.RenderSystem")
							.getMethod("getDevice")),
					lookup.unreflect(deviceClass.getMethod("createCommandEncoder")),
					lookup.unreflect(createTexture),
					lookup.unreflect(createTextureView),
					lookup.unreflect(writeToTexture),
					internalFormat,
					gtaCtor,
					lookup.unreflect(texViewGetter),
					lookup.unreflect(samplerGetter),
					lookup.unreflect(multiTextureBegin),
					lookup.unreflectGetter(textureAndViewField),
					lookup.unreflect(singleTexture),
					pipelineField.get(null),
					blitCtor);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new ExceptionInInitializerError("CompatTextures 解析失败（MC/Xaero API 形态变更？）: " + e);
		}
	}

	private static @NotNull Method xsm$findMethod(@NotNull Class<?> clazz, @NotNull String name, int paramCount,
			@NotNull java.util.function.Predicate<Method> extra) {
		for (final Method m : clazz.getMethods()) {
			if (m.getName().equals(name) && m.getParameterTypes().length == paramCount && extra.test(m))
				return m;
		}
		throw new IllegalStateException("Method not found: " + clazz.getName() + "." + name + "/" + paramCount);
	}

	/** 解析产物。字段含义见 {@link #xsm$resolve()}。 */
	private record Cache(
			MethodHandle getDevice,
			MethodHandle createCommandEncoder,
			MethodHandle createTexture,
			MethodHandle createTextureView,
			MethodHandle writeToTexture,
			Object internalFormat,
			Constructor<?> gpuTextureAndViewCtor,
			MethodHandle textureViewGetter,
			MethodHandle samplerGetter,
			MethodHandle multiTextureBegin,
			MethodHandle textureAndViewFieldGetter,
			MethodHandle singleTexture,
			Object guiTexturedPipeline,
			Constructor<?> blitCtor) {
	}
}
