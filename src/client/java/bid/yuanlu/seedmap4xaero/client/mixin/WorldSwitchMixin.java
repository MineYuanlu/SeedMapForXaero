package bid.yuanlu.seedmap4xaero.client.mixin;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorProvider;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import xaero.map.MapProcessor;

@Mixin(MapProcessor.class)
public class WorldSwitchMixin {

    @Unique
    private static final Logger xsm$LOGGER = LoggerFactory.getLogger("seedmap4xaero/WorldSwitchMixin");

    @Unique
    private String xsm$lastWorldId;

    @Unique
    private static BiomeColorProvider xsm$resolveProvider() {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg == null)
            return BiomeColorTable.providers().get(0);
        var name = cfg.getTheme();
        if (name != null) {
            var p = BiomeColorTable.byName(name);
            if (p != null)
                return p;
        }
        return BiomeColorTable.providers().get(0);
    }

    @Shadow
    public String getCurrentWorldId() {
        return null;
    }

    @Inject(method = "checkForWorldUpdate", at = @At("RETURN"))
    private void xsm$onCheckForWorldUpdate(CallbackInfo ci) {
        String currentId = getCurrentWorldId();
        xsm$LOGGER.info("checkForWorldUpdate: currentId={}, lastWorldId={}", currentId, xsm$lastWorldId);
        if (Objects.equals(currentId, xsm$lastWorldId))
            return;
        xsm$lastWorldId = currentId;
        if (currentId != null) {
            ServerConfig.activate((MapProcessor) (Object) this);
            Xsm.setBiomeColorTable(xsm$resolveProvider());
        } else {
            ServerConfig.deactivate();
        }
    }
}
