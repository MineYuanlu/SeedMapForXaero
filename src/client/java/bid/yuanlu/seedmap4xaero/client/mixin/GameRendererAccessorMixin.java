package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;

@Mixin(GameRenderer.class)
public abstract class GameRendererAccessorMixin implements GameRendererAccessor {

    @Accessor("gameRenderState")
    @Override
    public abstract GameRenderState xsm$gameRenderState();
}
