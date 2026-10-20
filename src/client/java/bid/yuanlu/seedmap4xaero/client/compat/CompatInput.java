package bid.yuanlu.seedmap4xaero.client.compat;

/**
 * 鼠标按键编号（跨版本兼容层）。
 * <p>
 * master(26.x) 上 MC 26.3 调整了 {@code InputConstants.MOUSE_BUTTON_*} 的取值
 * （LEFT 0→1、RIGHT 1→3），且这些字段是编译期常量会被 javac 内联，故那边必须
 * 运行时反射读取。1.21.11 的取值与 GLFW 原生常量一致（GLFW API 冻结，永不变化）：
 *
 * <pre>
 *   LEFT = 0, RIGHT = 1, MIDDLE = 2
 * </pre>
 *
 * 本分支直接硬编码；业务代码仍一律引用本类常量，禁止直接书写 0/1/2。
 */
public final class CompatInput {

    /** {@code InputConstants.MOUSE_BUTTON_LEFT} = 0（GLFW 冻结常量）。 */
    public static final int MOUSE_LEFT = 0;

    /** {@code InputConstants.MOUSE_BUTTON_RIGHT} = 1（GLFW 冻结常量）。 */
    public static final int MOUSE_RIGHT = 1;

    /** {@code InputConstants.MOUSE_BUTTON_MIDDLE} = 2（GLFW 冻结常量）。 */
    public static final int MOUSE_MIDDLE = 2;

    private CompatInput() {
    }
}
