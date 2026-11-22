package bid.yuanlu.seedmap4xaero;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

public class XaeroSeedMap implements ModInitializer {
	public static final String MOD_ID = "seed-map-for-xaero";

	@Override
	public void onInitialize() {
		// client 侧初始化在 XaeroSeedMapClient (fabric.mod.json entrypoints.client);
		// common 侧目前仅提供 Identifier 辅助。
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
