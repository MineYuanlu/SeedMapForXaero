package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

//? if >=26.1 {
import net.minecraft.client.renderer.state.GameRenderState;
//?} else {
/*import net.minecraft.client.gui.render.state.GuiRenderState;
*///?}

@Mixin(GameRenderer.class)
public abstract class GameRendererAccessorMixin implements GameRendererAccessor {

    //? if >=26.1 {
    @Accessor("gameRenderState")
    @Override
    public abstract GameRenderState xsm$gameRenderState();
    //?} else {
    /*@Accessor("guiRenderState")
    @Override
    public abstract GuiRenderState xsm$guiRenderState();
    *///?}

    @Accessor("mainCamera")
    @Override
    public abstract Camera xsm$mainCamera();
}
