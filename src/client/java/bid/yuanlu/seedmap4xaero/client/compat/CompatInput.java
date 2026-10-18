package bid.yuanlu.seedmap4xaero.client.compat;

import java.lang.reflect.Field;

/**
 * 鼠标按键编号的运行时解析（跨版本兼容层）。
 * <p>
 * MC 26.3 调整了 {@code com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_*} 的取值：
 *
 * <pre>
 *          26.1.2   26.3
 *   LEFT      0       1
 *   MIDDLE    2       2
 *   RIGHT     1       3
 * </pre>
 *
 * 这些字段是 {@code public static final int} 编译期常量，universal jar 按 26.1.2 编译时
 * javac 会把它们**内联成字面量**；在 26.3 上这些字面量即失效（例如左键 0 与 26.3 的左键 1
 * 不匹配 → 点击穿透）。因此这里改为运行时反射读取，业务代码一律引用本类常量，
 * 禁止直接书写 0/1/2 或 import {@code InputConstants} 常量。
 */
public final class CompatInput {

    /** {@code InputConstants.MOUSE_BUTTON_LEFT}。 */
    public static final int MOUSE_LEFT = resolve("MOUSE_BUTTON_LEFT");

    /** {@code InputConstants.MOUSE_BUTTON_RIGHT}。 */
    public static final int MOUSE_RIGHT = resolve("MOUSE_BUTTON_RIGHT");

    /** {@code InputConstants.MOUSE_BUTTON_MIDDLE}。 */
    public static final int MOUSE_MIDDLE = resolve("MOUSE_BUTTON_MIDDLE");

    private CompatInput() {
    }

    private static int resolve(String name) {
        try {
            final Field field = com.mojang.blaze3d.platform.InputConstants.class.getField(name);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(
                    "Cannot resolve InputConstants." + name + ": " + e);
        }
    }
}
