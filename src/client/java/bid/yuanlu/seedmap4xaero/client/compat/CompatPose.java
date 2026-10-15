package bid.yuanlu.seedmap4xaero.client.compat;

import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/**
 * 姿态变换的跨版本兼容门面。
 *
 * <p>
 * {@code PoseStack.mulPose(Quaternionf)} 在 MC 26.3 被移除（改为
 * {@code rotate(Quaternionfc)}），而 26.1/26.2 没有 {@code rotate}。两版本的类型交集为
 * {@code mulPose(Matrix4fc)}（26.1/26.3 均存在且描述符一致）——用 {@link Matrix4f}
 * 承载同一旋转即可无反射、双端编译期与运行时安全。若未来版本连该交集也迁移，
 * 再把本类内部改为反射缝。
 */
public final class CompatPose {

	private CompatPose() {
	}

	/** 等价于 {@code pose.mulPose(Axis.YP.rotationDegrees(degrees))}（26.1 语义）。 */
	public static void rotateYDegrees(@NotNull PoseStack pose, float degrees) {
		pose.mulPose(new Matrix4f().rotation(Axis.YP.rotationDegrees(degrees)));
	}
}
