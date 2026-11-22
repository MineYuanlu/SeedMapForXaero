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

import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import xaero.map.MapProcessor;

@Mixin(MapProcessor.class)
public class WorldSwitchMixin {

    @Unique
    private static final Logger xsm$LOGGER = LoggerFactory.getLogger("seedmap4xaero/WorldSwitchMixin");

    @Unique
    private String xsm$lastWorldId;


    @Shadow
    public String getCurrentWorldId() {
        return null;
    }

    @Inject(method = "checkForWorldUpdate", at = @At("RETURN"))
    private void xsm$onCheckForWorldUpdate(CallbackInfo ci) {
        String currentId = getCurrentWorldId();
        if (Objects.equals(currentId, xsm$lastWorldId))
            return;
        // 本钩子在同世界内也会被多次调用 (出生点更新/level id 包/面板刷新), 只在真切换时打日志
        xsm$LOGGER.info("checkForWorldUpdate: switching {} -> {}", xsm$lastWorldId, currentId);
        xsm$lastWorldId = currentId;
        if (currentId != null) {
            ServerConfig.activate((MapProcessor) (Object) this);
            StructureDataConfig.activate((MapProcessor) (Object) this);
            Xsm.setBiomeColorTable(BiomeColorTable.resolveProvider());
        } else {
            ServerConfig.deactivate();
            StructureDataConfig.deactivate();
        }
    }
}
