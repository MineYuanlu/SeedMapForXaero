package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;

@Mixin(GameRenderer.class)
public abstract class GameRendererAccessorMixin implements GameRendererAccessor {

    @Accessor("guiRenderState")
    @Override
    public abstract GuiRenderState xsm$guiRenderState();

    @Accessor("mainCamera")
    @Override
    public abstract Camera xsm$mainCamera();
}
