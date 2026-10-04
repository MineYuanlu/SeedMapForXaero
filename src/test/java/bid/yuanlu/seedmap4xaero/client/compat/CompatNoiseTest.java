package bid.yuanlu.seedmap4xaero.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.util.RandomSource;

/**
 * {@link CompatNoise} 与 vanilla {@code PerlinSimplexNoise} 的逐位对照回归。
 *
 * <p>
 * MC 26.1/26.2 类路径上存在真实实现时逐位比对（副本算法抄录自 26.1.2 反编译）；
 * 26.3+ 该类已被移除，副本即唯一事实来源，退化为快照/确定性断言。
 */
class CompatNoiseTest {

	@Test
	void matchesVanillaPerlinSimplexNoise() throws Exception {
		final Class<?> vanilla;
		try {
			vanilla = Class.forName("net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise");
		} catch (ClassNotFoundException e) {
			// MC 26.3+：类已移除，无对照目标
			return;
		}
		final Object mcNoise = vanilla.getConstructor(RandomSource.class, List.class)
				.newInstance(RandomSource.create(0L), List.of(0));
		final Method getValue = vanilla.getMethod("getValue", double.class, double.class, boolean.class);
		final CompatNoise ours = new CompatNoise(0L);

		// 覆盖正负坐标（含 swamp 阈值 -0.1 附近的值域），worldX*0.0225 的实际值域
		int checked = 0;
		for (int x = -4000; x <= 4000; x += 137) {
			for (int z = -4000; z <= 4000; z += 149) {
				final double xin = x * 0.0225;
				final double zin = z * 0.0225;
				assertEquals((Double) getValue.invoke(mcNoise, xin, zin, false),
						ours.getValue(xin, zin, false), 0.0,
						"bit-exact mismatch at worldX=" + x + " worldZ=" + z);
				checked++;
			}
		}
		assertTrue(checked >= 3000, "coverage too small: " + checked);
	}

	@Test
	void deterministicAndBounded() {
		final CompatNoise n = new CompatNoise(0L);
		for (int x = -100; x <= 100; x += 7) {
			final double v = n.getValue(x * 0.0225, (x * 31) * 0.0225, false);
			assertEquals(v, n.getValue(x * 0.0225, (x * 31) * 0.0225, false), 0.0);
			assertTrue(v >= -1.0 && v <= 1.0, "simplex value out of range: " + v);
		}
	}

	@Test
	void useNoiseStartMatchesVanillaOffsetSemantics() throws Exception {
		final Class<?> vanilla;
		try {
			vanilla = Class.forName("net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise");
		} catch (ClassNotFoundException e) {
			return;
		}
		final Object mcNoise = vanilla.getConstructor(RandomSource.class, List.class)
				.newInstance(RandomSource.create(0L), List.of(0));
		final Method getValue = vanilla.getMethod("getValue", double.class, double.class, boolean.class);
		final CompatNoise ours = new CompatNoise(0L);
		for (int x = -2000; x <= 2000; x += 173) {
			final double xin = x * 0.0225;
			assertEquals((Double) getValue.invoke(mcNoise, xin, xin * 0.5, true),
					ours.getValue(xin, xin * 0.5, true), 0.0, "useNoiseStart=true mismatch at " + x);
		}
	}
}
