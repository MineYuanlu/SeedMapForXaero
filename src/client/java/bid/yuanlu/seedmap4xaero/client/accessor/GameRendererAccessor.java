package bid.yuanlu.seedmap4xaero.client.accessor;

import net.minecraft.client.renderer.state.GameRenderState;

/**
 * 跨版本访问 {@link net.minecraft.client.renderer.GameRenderer#gameRenderState}。
 * 该字段两版本同名 (private final)，仅暴露方法名不同：
 * 26.1.2 为 getGameRenderState()，26.2 起改名为 gameRenderState()。
 * 直接 @Accessor 字段即可双版本通用。
 */
public interface GameRendererAccessor {

    GameRenderState xsm$gameRenderState();
}
