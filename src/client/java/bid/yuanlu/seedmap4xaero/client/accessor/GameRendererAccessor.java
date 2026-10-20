package bid.yuanlu.seedmap4xaero.client.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.gui.render.state.GuiRenderState;

/**
 * 跨版本访问 {@link net.minecraft.client.renderer.GameRenderer} 的私有字段。
 * 字段两线同名 (private final)，直接 @Accessor 字段即可通用：
 * <ul>
 * <li>guiRenderState: legacy 1.21.11 为 GuiRenderState (public 字段)；26.x 的
 * GameRenderState 是另一类型——26.x 分支自行维护本接口的另一形态</li>
 * <li>mainCamera: private final（public getter getMainCamera() 不经 accessor）</li>
 * </ul>
 */
public interface GameRendererAccessor {

    GuiRenderState xsm$guiRenderState();

    Camera xsm$mainCamera();
}
