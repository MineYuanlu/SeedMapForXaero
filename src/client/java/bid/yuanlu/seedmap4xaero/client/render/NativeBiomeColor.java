package bid.yuanlu.seedmap4xaero.client.render;

public final class NativeBiomeColor implements BiomeColorProvider {

    public static final NativeBiomeColor INSTANCE = new NativeBiomeColor();

    private NativeBiomeColor() {
    }

    @Override
    public String name() {
        return "Native";
    }

    @Override
    public int getColor(int biomeId, int blockX, int blockZ) {
        return 0;
    }

    @Override
    public boolean isAquatic(int biomeId) {
        return false;
    }
}
