package bid.yuanlu.seedmap4xaero.client.render;

public interface BiomeColorProvider {

	String name();

	default String translationKey() {
		return "xsm.color_provider." + name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	int getColor(int biomeId, int blockX, int blockZ);

	default int getColor(int biomeId) {
		return getColor(biomeId, 0, 0);
	}

	boolean isAquatic(int biomeId);
}
