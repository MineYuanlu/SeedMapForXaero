package bid.yuanlu.seedmap4xaero.client.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.GameRenderState;

/**
 * 跨版本访问 {@link net.minecraft.client.renderer.GameRenderer} 的私有字段。
 * 字段两版本同名 (private final)，仅暴露方法名不同，直接 @Accessor 字段即可双版本通用：
 * <ul>
 * <li>gameRenderState: 26.1.2 为 getGameRenderState()，26.2 起改名为 gameRenderState()</li>
 * <li>mainCamera: 26.1.2 为 getMainCamera()，26.2 起改名为 mainCamera()</li>
 * </ul>
 */
public interface GameRendererAccessor {

    GameRenderState xsm$gameRenderState();

    Camera xsm$mainCamera();
}
