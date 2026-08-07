package bid.yuanlu.seedmap4xaero.client.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class StructureIconsTest {

    /**
     * 热循环契约: VisibleIconSink 的抽象方法只接受原始类型, 禁止回归到 record Icon
     * 这种每图标分配一次的写法 (渲染每帧几千图标时会产生大量 young gen 垃圾)。
     */
    @Test
    void visibleIconSinkIsPrimitiveOnly() {
        var abstractMethods = Arrays.stream(StructureIcons.VisibleIconSink.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()) && !m.isDefault())
                .toList();
        assertEquals(1, abstractMethods.size(), "VisibleIconSink must be a single-method functional interface");
        var method = abstractMethods.get(0);
        assertEquals(6, method.getParameterCount());
        for (var type : method.getParameterTypes()) {
            // 数值全原始类型 (禁止装箱/每图标对象); 唯一对象参数是共享枚举常量, 零分配
            assertTrue(type.isPrimitive() || type == StructureType.class,
                    "sink parameter must be primitive: " + type);
        }
    }

    private static StructureIcons.Transform transform(double cameraX, double cameraZ,
            double scale, double screenScale, int winW, int winH) {
        double invScale = 1.0 / screenScale;
        return new StructureIcons.Transform(cameraX, cameraZ, scale, invScale,
                winW * invScale, winH * invScale);
    }

    @Test
    void iconCenteredOnCameraIsScreenCenter() {
        var t = transform(100, -200, 1, 2, 1920, 1080);
        assertEquals(480, t.guiX(100), 1e-9);
        assertEquals(270, t.guiZ(-200), 1e-9);
    }

    @Test
    void positiveOffsetMovesRightDown() {
        var t = transform(0, 0, 1, 1, 1000, 800);
        assertEquals(500 + 25, t.guiX(25), 1e-9);
        assertEquals(400 + 25, t.guiZ(25), 1e-9);
    }

    @Test
    void negativeOffsetMovesLeftUp() {
        var t = transform(0, 0, 1, 1, 1000, 800);
        assertEquals(500 - 25, t.guiX(-25), 1e-9);
        assertEquals(400 - 25, t.guiZ(-25), 1e-9);
    }

    @Test
    void zoomedOutBlocksShrinkToCenter() {
        // scale=0.5: 方块间距在屏幕上的距离减半
        var t = transform(0, 0, 0.5, 1, 1000, 800);
        assertEquals(500 + 12.5, t.guiX(25), 1e-9);
        assertEquals(400 + 12.5, t.guiZ(25), 1e-9);
    }

    @Test
    void screenScaleOnlyScalesOffsetNotCenter() {
        // screenScale=2: 屏幕只有 gui 一半大, 偏移量减半, 中心不变
        var t = transform(0, 0, 1, 2, 1000, 800);
        assertEquals(250 + 12.5, t.guiX(25), 1e-9);
        assertEquals(200 + 12.5, t.guiZ(25), 1e-9);
    }
}
