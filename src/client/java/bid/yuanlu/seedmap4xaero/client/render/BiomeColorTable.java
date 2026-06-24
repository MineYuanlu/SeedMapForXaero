package bid.yuanlu.seedmap4xaero.client.render;

import java.util.ArrayList;
import java.util.List;

public final class BiomeColorTable {

	private BiomeColorTable() {
	}

	private static final List<BiomeColorProvider> PROVIDERS = new ArrayList<>();
	private static volatile BiomeColorProvider provider;

	static {
		register(NativeBiomeColor.INSTANCE);
		register(VanillaBiomeColor.INSTANCE);
		register(LegacyBiomeColor.INSTANCE);
		provider = PROVIDERS.get(0);
	}

	public static void register(BiomeColorProvider p) {
		PROVIDERS.add(p);
	}

	public static List<BiomeColorProvider> providers() {
		return List.copyOf(PROVIDERS);
	}

	public static BiomeColorProvider getProvider() {
		return provider;
	}

	public static void setProvider(int index) {
		provider = PROVIDERS.get(index % PROVIDERS.size());
	}

	public static void cycleProvider() {
		int idx = PROVIDERS.indexOf(provider);
		setProvider((idx + 1) % PROVIDERS.size());
	}

	public static int getColor(int biomeId, int blockX, int blockZ) {
		return provider.getColor(biomeId, blockX, blockZ);
	}

	public static int getColor(int biomeId) {
		return provider.getColor(biomeId);
	}

	public static boolean isAquatic(int biomeId) {
		return provider.isAquatic(biomeId);
	}
}
