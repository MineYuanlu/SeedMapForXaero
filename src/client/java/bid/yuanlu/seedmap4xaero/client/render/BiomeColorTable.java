package bid.yuanlu.seedmap4xaero.client.render;

import java.util.ArrayList;
import java.util.List;

import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import org.jetbrains.annotations.NotNull;
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

	public static @NotNull BiomeColorProvider resolveProvider() {
		var cfg = ServerConfig.getActiveConfig();
		if (cfg == null)
			return PROVIDERS.get(0);
		var name = cfg.getTheme();
		if (name != null) {
			var p = byName(name);
			if (p != null)
				return p;
		}
		return PROVIDERS.get(0);
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
