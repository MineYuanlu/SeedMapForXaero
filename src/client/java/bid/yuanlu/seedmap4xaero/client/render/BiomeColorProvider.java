package bid.yuanlu.seedmap4xaero.client.render;

public interface BiomeColorProvider {

	String name();

	/** lang key: {@code xsm.color_provider.<id>}，实现方必须显式提供合法 key 段。 */
	String translationKey();

	int getColor(int biomeId, int blockX, int blockZ);

	default int getColor(int biomeId) {
		return getColor(biomeId, 0, 0);
	}

	boolean isAquatic(int biomeId);
}
