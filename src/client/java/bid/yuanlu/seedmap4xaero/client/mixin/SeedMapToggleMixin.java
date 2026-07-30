package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import xaero.map.gui.GuiMap;

@Mixin(GuiMap.class)
public abstract class SeedMapToggleMixin implements SeedMapToggleAccessor {

    @Unique
    private boolean xsm$isLoadedWorldInfo;

    @Override
    public boolean xsm$isSeedMapEnabled() {
        if (!this.xsm$isLoadedWorldInfo)
            return false;
        var cfg = ServerConfig.getActiveConfig();
        if (cfg != null)
            return !cfg.isInvisibleBiomes();
        return true;
    }

    @Override
    public void xsm$setSeedMapEnabled(boolean enabled) {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg != null)
            cfg.setInvisibleBiomes(!enabled);
    }

    @Override
    public void xsm$setSeedMapLoadedWorldInfo(boolean loaded) {
        this.xsm$isLoadedWorldInfo = loaded;
    }
}
