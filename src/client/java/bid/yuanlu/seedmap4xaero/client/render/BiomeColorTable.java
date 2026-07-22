package bid.yuanlu.seedmap4xaero.client.render;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

public final class BiomeColorTable {

	private BiomeColorTable() {
	}

	private static final List<BiomeColorProvider> PROVIDERS = new ArrayList<>();

	static {
		register(NativeBiomeColor.INSTANCE);
		register(VanillaBiomeColor.INSTANCE);
		register(LegacyBiomeColor.INSTANCE);
	}

	public static void register(BiomeColorProvider p) {
		PROVIDERS.add(p);
	}

	public static List<BiomeColorProvider> providers() {
		return List.copyOf(PROVIDERS);
	}

	public static @Nullable BiomeColorProvider byName(String name) {
		for (var p : PROVIDERS) {
			if (p.name().equals(name))
				return p;
		}
		return null;
	}

	public static BiomeColorProvider nextProvider(@Nullable String name) {
		var providers = PROVIDERS;
		if (name != null) {
			for (int i = 0; i < providers.size(); i++) {
				if (providers.get(i).name().equals(name))
					return providers.get((i + 1) % providers.size());
			}
		}
		return providers.get(0);
	}
}
