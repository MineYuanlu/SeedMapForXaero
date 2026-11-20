package bid.yuanlu.seedmap4xaero.client.compat;

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
 * 不匹配 → 点击穿透）。因此 modern 线改为运行时反射读取；legacy 线（≤1.21.11）
 * 的取值与 GLFW 原生常量一致（GLFW API 冻结，永不变化），直接硬编码——
 * legacy 运行时字段名是 intermediary，mojmap 名反射必然失败。
 * <p>
 * 业务代码一律引用本类常量，禁止直接书写 0/1/2 或 import {@code InputConstants} 常量。
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

    //? if >=26.1 {
    private static int resolve(String name) {
        try {
            final java.lang.reflect.Field field =
                    com.mojang.blaze3d.platform.InputConstants.class.getField(name);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(
                    "Cannot resolve InputConstants." + name + ": " + e);
        }
    }
    //?} else {
    /*/^* 1.21.11 与 GLFW 原生常量一致：LEFT=0, RIGHT=1, MIDDLE=2（冻结不变）。 ^/
    private static int resolve(String name) {
        return switch (name) {
            case "MOUSE_BUTTON_LEFT" -> 0;
            case "MOUSE_BUTTON_RIGHT" -> 1;
            case "MOUSE_BUTTON_MIDDLE" -> 2;
            default -> throw new IllegalArgumentException(
                    "Unknown InputConstants field: " + name);
        };
    }
    *///?}
}
