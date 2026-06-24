package bid.yuanlu.seedmap4xaero.client.render;

public interface BiomeColorProvider {

	String name();

	int getColor(int biomeId, int blockX, int blockZ);

	default int getColor(int biomeId) {
		return getColor(biomeId, 0, 0);
	}

	boolean isAquatic(int biomeId);
}
