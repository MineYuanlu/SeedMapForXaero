package bid.yuanlu.seedmap4xaero.client.compat;

/**
 * MC legacy Simplex 噪声（原 {@code net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise}）
 * 的自实现副本。该类在 MC 26.3 被彻底移除且无等价 API，此处按 26.1/26.2 的算法逐位复刻，
 * 彻底解除版本耦合。
 *
 * <p>
 * 仅复刻本项目实际使用的形态：{@code new PerlinSimplexNoise(RandomSource.create(seed), List.of(0))}
 * （单 octave 0，swamp 草色噪声）。此时 vanilla 构造退化为：单个 {@code SimplexNoise} 直接消耗
 * {@code LegacyRandomSource(seed)}（无 {@code consumeCount(262)}、无 WorldgenRandom 链），
 * 且 {@code highestFreqInputFactor = 2^0 = 1}、
 * {@code highestFreqValueFactor = 1/(2^1-1) = 1}，
 * 即 {@code getValue(x, y, useNoiseStart) = simplex(x + (useNoiseStart ? xo : 0),
 * y + (useNoiseStart ? yo : 0))}。
 *
 * <p>
 * RNG：vanilla 传入 {@code LegacyRandomSource}，其 {@code setSeed}/{@code next(bits)}/
 * {@code nextInt(bound)}/{@code nextDouble()} 与 {@code java.util.Random} 的 LCG
 * （乘数 0x5DEECE66D、加数 0xB、48bit mask）及算法逐位一致，此处内联同款实现。
 * 算法抄录自 MC 26.1.2 反编译源（26.3 移除前的最后形态），逐位对齐由
 * {@code CompatNoiseTest} 在 MC 类可用时做对照回归。
 */
public final class CompatNoise {

	private static final int[][] GRADIENT = {
			{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
			{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
			{0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
			{1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};

	private static final double SQRT_3 = Math.sqrt(3.0);
	private static final double F2 = 0.5 * (SQRT_3 - 1.0);
	private static final double G2 = (3.0 - SQRT_3) / 6.0;

	private final int[] p = new int[256];
	public final double xo;
	public final double yo;
	public final double zo;

	/** 等价于 {@code new PerlinSimplexNoise(RandomSource.create(seed), List.of(0))}。 */
	public CompatNoise(long seed) {
		LegacyRandom random = new LegacyRandom(seed);
		this.xo = random.nextDouble() * 256.0;
		this.yo = random.nextDouble() * 256.0;
		this.zo = random.nextDouble() * 256.0;
		for (int i = 0; i < 256; i++) {
			this.p[i] = i;
		}
		for (int i = 0; i < 256; i++) {
			int offset = random.nextInt(256 - i);
			int tmp = this.p[i];
			this.p[i] = this.p[offset + i];
			this.p[offset + i] = tmp;
		}
	}

	/**
	 * 等价于单 octave {@code PerlinSimplexNoise.getValue(x, y, useNoiseStart)}
	 * （value factor = 1/(2^1-1) = 1，无缩放）。
	 *
	 * @param useNoiseStart true 时叠加噪声自身的 xo/yo 原点偏移（vanilla 语义）
	 */
	public double getValue(double xin, double yin, boolean useNoiseStart) {
		double x = xin + (useNoiseStart ? this.xo : 0.0);
		double y = yin + (useNoiseStart ? this.yo : 0.0);
		return simplex2D(x, y);
	}

	private double simplex2D(double xin, double yin) {
		double s = (xin + yin) * F2;
		int i = floor(xin + s);
		int j = floor(yin + s);
		double t = (double) (i + j) * G2;
		double x0 = xin - ((double) i - t);
		double y0 = yin - ((double) j - t);
		final int i1;
		final int j1;
		if (x0 > y0) {
			i1 = 1;
			j1 = 0;
		} else {
			i1 = 0;
			j1 = 1;
		}
		double x1 = x0 - (double) i1 + G2;
		double y1 = y0 - (double) j1 + G2;
		double x2 = x0 - 1.0 + 2.0 * G2;
		double y2 = y0 - 1.0 + 2.0 * G2;
		int ii = i & 0xFF;
		int jj = j & 0xFF;
		int gi0 = this.p(ii + this.p(jj)) % 12;
		int gi1 = this.p(ii + i1 + this.p(jj + j1)) % 12;
		int gi2 = this.p(ii + 1 + this.p(jj + 1)) % 12;
		double n0 = cornerNoise(gi0, x0, y0, 0.0);
		double n1 = cornerNoise(gi1, x1, y1, 0.0);
		double n2 = cornerNoise(gi2, x2, y2, 0.0);
		return 70.0 * (n0 + n1 + n2);
	}

	private int p(int x) {
		return this.p[x & 0xFF];
	}

	private static double cornerNoise(int gradientIndex, double x, double y, double z) {
		double t = 0.5 - x * x - y * y - z * z;
		if (t < 0.0) {
			return 0.0;
		}
		t *= t;
		int[] g = GRADIENT[gradientIndex];
		return t * t * ((double) g[0] * x + (double) g[1] * y + (double) g[2] * z);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	/** {@code LegacyRandomSource} 的单线程等价实现（与 java.util.Random 序列逐位一致）。 */
	private static final class LegacyRandom {

		private long seed;

		LegacyRandom(long seed) {
			this.seed = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
		}

		private int next(int bits) {
			this.seed = this.seed * 0x5DEECE66DL + 0xBL & ((1L << 48) - 1);
			return (int) (this.seed >>> 48 - bits);
		}

		int nextInt(int bound) {
			if (bound <= 0) {
				throw new IllegalArgumentException("Bound must be positive");
			}
			if ((bound & bound - 1) == 0) {
				return (int) ((long) bound * (long) this.next(31) >> 31);
			}
			int sample;
			int modulo;
			do {
				sample = this.next(31);
				modulo = sample % bound;
			} while (sample - modulo + (bound - 1) < 0);
			return modulo;
		}

		double nextDouble() {
			// 与 BitRandomSource.nextDouble 逐位一致（先 next(26) 后 next(27)）
			return (double) (((long) this.next(26) << 27) + (long) this.next(27)) * 0x1.0p-53;
		}
	}
}
