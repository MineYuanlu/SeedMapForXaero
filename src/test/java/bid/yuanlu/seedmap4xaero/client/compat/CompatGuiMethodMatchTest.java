package bid.yuanlu.seedmap4xaero.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/**
 * CompatGui 重载匹配（issue #32）。
 *
 * <p>
 * 26.1/26.3 的 {@code GuiGraphicsExtractor} 同时存在 7 参 {@code blitSprite(…, float)}
 * 与 {@code blitSprite(…, int)} 重载，{@code getMethods()} 顺序未定义 —— 只按
 * "方法名 + 参数个数" 匹配时 26.3 上会命中 int 版本，运行期
 * {@code WrongMethodTypeException} 崩溃。这里用模拟方法表（覆盖两种排序）+
 * 真实 26.1 类路径表验证匹配器只认精确形参形状。
 */
class CompatGuiMethodMatchTest {

	/** 模拟 GuiGraphicsExtractor 的相关重载集合（形状对齐 26.1.2/26.3 javap）。 */
	public static class Overloads {
		public void blitSprite(Object pipeline, Identifier sprite, int x, int y, int w, int h, float alpha) {
		}

		public void blitSprite(Object pipeline, Identifier sprite, int x, int y, int w, int h, int color) {
		}

		// TextureAtlasSprite 变体：pt[1] 不是 Identifier，必须被排除
		public void blitSprite(Object pipeline, Object atlasSprite, int x, int y, int w, int h, int color) {
		}

		public void blit(Object pipeline, Identifier tex, int x, int y, float u, float v,
				int w, int h, int texW, int texH) {
		}

		// 11 参变体：参数个数不同，必须被排除
		public void blit(Object pipeline, Identifier tex, int x, int y, float u, float v,
				int w, int h, int texW, int texH, int extra) {
		}

		// 形状诱饵：同为 10 参但 u/v 是 int，必须被排除
		public void blit(Object pipeline, Identifier tex, int x, int y, int u, int v,
				int w, int h, int texW, int texH) {
		}

		// 无 pipeline 的旧式 blit，必须被排除
		public void blit(Identifier tex, int x, int y, int w, int h, float u, float v, float u2, float v2) {
		}
	}

	/** 按「方法名 + 末参类型 + pt[1]==Identifier」定位诱饵类上的目标方法。 */
	private static Method method(String name, Class<?> lastParam) {
		for (final Method m : Overloads.class.getMethods()) {
			final Class<?>[] pt = m.getParameterTypes();
			if (m.getName().equals(name) && pt[1] == Identifier.class && pt[pt.length - 1] == lastParam) {
				return m;
			}
		}
		throw new AssertionError("decoy method missing: " + name + "(…" + lastParam + ")");
	}

	/** getMethods() 顺序未定义：同一种序（正/反）下都必须命中 float 版本。 */
	@Test
	void blitSpritePrefersFloatRegardlessOfOrder() throws Exception {
		final Method floatVersion = method("blitSprite", float.class);
		final List<Method> all = new ArrayList<>(List.of(Overloads.class.getMethods()));
		for (final boolean reversed : new boolean[] { false, true }) {
			final List<Method> order = new ArrayList<>(all);
			if (reversed)
				java.util.Collections.reverse(order);
			final CompatGui.BlitSpriteMatch match = CompatGui.xsm$findBlitSprite(order.toArray(Method[]::new));
			assertFalse(match.takesColor());
			assertEquals(floatVersion, match.method());
		}
	}

	/** 仅有 int（color）重载时降级命中并置 takesColor 标志。 */
	@Test
	void blitSpriteFallsBackToIntColor() throws Exception {
		final Method intVersion = method("blitSprite", int.class);
		final List<Method> onlyInt = new ArrayList<>();
		for (final Method m : Overloads.class.getMethods()) {
			final Class<?>[] pt = m.getParameterTypes();
			if (!m.getName().equals("blitSprite") || pt[1] != Identifier.class || pt[6] != int.class) {
				continue;
			}
			onlyInt.add(m);
		}
		final CompatGui.BlitSpriteMatch match = CompatGui.xsm$findBlitSprite(onlyInt.toArray(Method[]::new));
		assertTrue(match.takesColor());
		assertEquals(intVersion, match.method());
	}

	@Test
	void blitSpriteAtlasSpriteVariantExcluded() {
		// 只留 TextureAtlasSprite 形状的诱饵 → 匹配器必须拒绝而不是错拿
		final List<Method> decoys = new ArrayList<>();
		for (final Method m : Overloads.class.getMethods()) {
			if (m.getName().equals("blitSprite") && m.getParameterTypes()[1] != Identifier.class) {
				decoys.add(m);
			}
		}
		assertThrows(NoSuchMethodException.class, () -> CompatGui.xsm$findBlitSprite(decoys.toArray(Method[]::new)));
	}

	@Test
	void blitTextureExactShape() throws Exception {
		final Method tenArg = CompatGui.xsm$findBlitTexture(Overloads.class.getMethods());
		assertEquals(10, tenArg.getParameterCount());
		final Class<?>[] pt = tenArg.getParameterTypes();
		assertEquals(float.class, pt[4]);
		assertEquals(float.class, pt[5]);
	}

	/** 真实 26.1 类路径上的 GuiGraphicsExtractor：float 版本必须存在且被优先命中。 */
	@Test
	void realMc26ClassResolvesToFloatBlitSprite() throws Exception {
		final CompatGui.BlitSpriteMatch match = CompatGui.xsm$findBlitSprite(
				Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor").getMethods());
		assertFalse(match.takesColor());
		final Class<?>[] pt = match.method().getParameterTypes();
		assertEquals(7, pt.length);
		assertEquals(float.class, pt[6]);
	}
}
