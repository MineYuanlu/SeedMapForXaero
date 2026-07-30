package bid.yuanlu.seedmap4xaero.client.render;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;

import java.util.List;

public final class VanillaBiomeColor implements BiomeColorProvider {

	public static final VanillaBiomeColor INSTANCE = new VanillaBiomeColor();

	private VanillaBiomeColor() {
	}

	private static final int UNKNOWN_COLOR = 0x555555;

	private static final int GRASS = 0;
	private static final int WATER = 1;
	private static final int FIXED = 2;
	private static final int[] TYPE = new int[256];

	private static final float[] TEMP = new float[256];
	private static final float[] DOWNFALL = new float[256];
	private static final int[] WATER_COLOR = new int[256];

	private static final int[] GRASS_OVERRIDE = new int[256];
	private static final boolean[] DARK_FOREST = new boolean[256];
	private static final boolean[] SWAMP = new boolean[256];

	private static final int[] FALLBACK = new int[256];

	private static final int[] COMPUTED = new int[256];
	private static final int NOT_COMPUTED = -1;

	private static final boolean[] AQUATIC = new boolean[256];

	private static PerlinSimplexNoise SWAMP_NOISE;

	static {
		for (int i = 0; i < 256; i++) {
			TYPE[i] = FIXED;
			TEMP[i] = 0.5f;
			DOWNFALL[i] = 0.5f;
			WATER_COLOR[i] = 0x3F76E4;
			GRASS_OVERRIDE[i] = -1;
			FALLBACK[i] = UNKNOWN_COLOR;
			COMPUTED[i] = NOT_COMPUTED;
		}
		initData();
		initAquatic();
		for (int i = 0; i < 256; i++) {
			if (FALLBACK[i] == UNKNOWN_COLOR && TYPE[i] != GRASS) {
				FALLBACK[i] = UNKNOWN_COLOR;
			}
		}
	}

	private static void grass(int id, float temp, float downfall, int fallbackRgb) {
		TYPE[id] = GRASS;
		TEMP[id] = temp;
		DOWNFALL[id] = downfall;
		FALLBACK[id] = fallbackRgb;
	}

	private static void darkForest(int id, float temp, float downfall, int fallbackRgb) {
		grass(id, temp, downfall, fallbackRgb);
		DARK_FOREST[id] = true;
	}

	private static void swamp(int id, float temp, float downfall, int fallbackRgb) {
		grass(id, temp, downfall, fallbackRgb);
		SWAMP[id] = true;
	}

	private static void water(int id, int waterRgb, int fallbackRgb) {
		TYPE[id] = WATER;
		WATER_COLOR[id] = waterRgb;
		FALLBACK[id] = fallbackRgb;
	}

	private static void fixed(int id, int rgb) {
		TYPE[id] = FIXED;
		FALLBACK[id] = rgb;
	}

	private static void setAquatic(int... ids) {
		for (int id : ids) {
			AQUATIC[id] = true;
		}
	}

	private static void initData() {
		water(0, 0x000070, 0x000070);
		water(24, 0x000040, 0x000040);
		water(44, 0x004080, 0x004080);
		water(45, 0x006080, 0x006080);
		water(46, 0x003070, 0x003070);
		water(47, 0x002050, 0x002050);
		water(48, 0x004060, 0x004060);
		water(49, 0x001040, 0x001040);
		water(50, 0x202540, 0x202540);
		water(10, 0xA0C4D6, 0xA0C4D6);

		water(7, 0x0000FF, 0x0000FF);
		water(11, 0xA0CBFF, 0xA0CBFF);

		grass(1, 0.8f, 0.4f, 0x91BD59);
		grass(129, 0.8f, 0.4f, 0xA5C962);
		grass(177, 0.5f, 0.8f, 0x7FA86F);

		grass(4, 0.7f, 0.8f, 0x4E6E38);
		grass(27, 0.6f, 0.6f, 0x6BAA5E);
		grass(28, 0.6f, 0.6f, 0x6E9E60);
		grass(155, 0.6f, 0.6f, 0x5C8E52);
		grass(156, 0.6f, 0.6f, 0x55844B);
		grass(132, 0.7f, 0.8f, 0x699E48);
		darkForest(29, 0.7f, 0.8f, 0x3E5840);
		darkForest(157, 0.7f, 0.8f, 0x385C3A);

		grass(5, 0.25f, 0.8f, 0x2B5E38);
		grass(30, -0.5f, 0.4f, 0x628268);
		grass(31, -0.5f, 0.4f, 0x58745E);
		grass(32, 0.3f, 0.8f, 0x3A5C3C);
		grass(33, 0.3f, 0.8f, 0x3D5A3F);
		grass(160, 0.3f, 0.8f, 0x445E44);
		grass(161, 0.3f, 0.8f, 0x405A40);
		grass(133, 0.25f, 0.8f, 0x2A5032);
		grass(158, -0.5f, 0.4f, 0x48604E);

		fixed(3, 0x6B6B6B);
		fixed(131, 0x868686);
		grass(34, 0.2f, 0.3f, 0x5A6B5A);
		fixed(162, 0x7A7A7A);
		fixed(20, 0x707070);
		fixed(140, 0xDFDFDF);

		fixed(17, 0xCABF74);
		grass(18, 0.7f, 0.8f, 0x486828);
		grass(19, 0.25f, 0.8f, 0x2A4E32);
		grass(22, 0.95f, 0.9f, 0x285828);

		fixed(2, 0xFAD98F);
		fixed(130, 0xEFC274);

		grass(35, 2.0f, 0.0f, 0xBDA565);
		grass(36, 2.0f, 0.0f, 0xA58C52);
		grass(163, 2.0f, 0.0f, 0x9E8046);
		grass(164, 2.0f, 0.0f, 0x8A7340);

		fixed(37, 0xD67B34);
		fixed(38, 0xB7523A);
		fixed(39, 0xDB7437);
		fixed(165, 0xC76B2E);
		fixed(166, 0x9E3E2B);
		fixed(167, 0xC96430);

		swamp(6, 0.8f, 0.9f, 0x4B6B3A);
		swamp(134, 0.8f, 0.9f, 0x3F5E2E);
		swamp(184, 0.8f, 0.9f, 0x5C7346);

		grass(21, 0.95f, 0.9f, 0x2C6E18);
		grass(23, 0.95f, 0.8f, 0x3A7D28);
		grass(149, 0.95f, 0.9f, 0x247010);
		grass(151, 0.95f, 0.8f, 0x366E26);
		grass(168, 0.95f, 0.9f, 0x339E2E);
		grass(169, 0.95f, 0.9f, 0x2E8C2A);

		fixed(12, 0xF0F4F8);
		fixed(13, 0xDBE4EC);
		fixed(179, 0xEBF0F4);
		fixed(180, 0xDDE5EB);
		fixed(181, 0xD8E0E8);
		fixed(26, 0xD2DDE8);
		grass(178, -0.2f, 0.8f, 0xD0E0D8);

		fixed(16, 0xE8E09C);
		fixed(25, 0x7A7A7A);

		fixed(14, 0x8C788C);
		fixed(15, 0xA08EA0);

		fixed(8, 0x7A2A1F);
		fixed(170, 0x58433F);
		fixed(171, 0x8A2020);
		fixed(172, 0x3A4B4B);
		fixed(173, 0x4A4848);

		fixed(9, 0xC9C992);
		fixed(40, 0xBDBD7A);
		fixed(41, 0xBFBF80);
		fixed(42, 0xB8B870);
		fixed(43, 0xC4C488);

		fixed(174, 0x5E5A50);
		grass(175, 0.5f, 0.5f, 0x506844);
		fixed(182, 0x8A8680);
		fixed(183, 0x1A1A22);
		fixed(187, 0x8B8C60);

		fixed(185, 0xFFC0CB);
		fixed(186, 0x6B6B55);

		grass(51, 0.7f, 0.8f, 0x588A38);
		grass(52, 0.95f, 0.9f, 0x247010);
		grass(53, 0.8f, 0.4f, 0x6E885A);

		fixed(127, 0x000000);
	}

	private static void initAquatic() {
		setAquatic(0, 7, 10, 11, 24, 44, 45, 46, 47, 48, 49, 50);
	}

	@Override
	public String name() {
		return "Minecraft Native";
	}

	@Override
	public String translationKey() {
		return "xsm.color_provider.minecraft_native";
	}

	@Override
	public int getColor(int biomeId) {
		return getColor(biomeId, 0, 0);
	}

	@Override
	public int getColor(int biomeId, int blockX, int blockZ) {
		if (biomeId < 0 || biomeId >= 256) {
			return UNKNOWN_COLOR;
		}
		if (SWAMP[biomeId]) {
			return computeSwampColor(blockX, blockZ);
		}
		int c = COMPUTED[biomeId];
		if (c != NOT_COMPUTED) {
			return c;
		}
		c = compute(biomeId);
		COMPUTED[biomeId] = c;
		return c;
	}

	@Override
	public boolean isAquatic(int biomeId) {
		return biomeId >= 0 && biomeId < AQUATIC.length && AQUATIC[biomeId];
	}

	public static float getTemperature(int biomeId) {
		if (biomeId < 0 || biomeId >= 256)
			return 0.5f;
		return Mth.clamp(TEMP[biomeId], 0.0f, 1.0f);
	}

	public static float getDownfall(int biomeId) {
		if (biomeId < 0 || biomeId >= 256)
			return 0.5f;
		return Mth.clamp(DOWNFALL[biomeId], 0.0f, 1.0f);
	}

	private static int compute(int id) {
		return switch (TYPE[id]) {
			case WATER -> WATER_COLOR[id];
			case FIXED -> FALLBACK[id];
			case GRASS -> computeGrassColor(id);
			default -> FALLBACK[id];
		};
	}

	private static int computeGrassColor(int id) {
		if (GRASS_OVERRIDE[id] != -1) {
			return GRASS_OVERRIDE[id];
		}

		float temp = Mth.clamp(TEMP[id], 0.0f, 1.0f);
		float rain = Mth.clamp(DOWNFALL[id], 0.0f, 1.0f);
		int base = GrassColor.get(temp, rain) & 0xFFFFFF;

		if (base == 0xFF00FF) {
			return FALLBACK[id];
		}

		if (DARK_FOREST[id]) {
			base = ((base & 0xFEFEFE) + 0x28340A) >> 1;
		}

		return base;
	}

	private static int computeSwampColor(int x, int z) {
		if (SWAMP_NOISE == null) {
			SWAMP_NOISE = new PerlinSimplexNoise(
					RandomSource.create(0L),
					List.of(0));
		}
		double noise = SWAMP_NOISE.getValue(x * 0.0225, z * 0.0225, false);
		return noise < -0.1 ? 0x4C763C : 0x6A7039;
	}
}
