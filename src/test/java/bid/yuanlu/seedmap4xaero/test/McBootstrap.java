package bid.yuanlu.seedmap4xaero.test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;

/**
 * 需要 MC 注册表 / 静态初始化 (如 GrassColor) 的测试基类。
 * <p>
 * fabric-loader-junit 已引导 loader 并探测游戏版本，但不会构造 MinecraftClient；
 * 此处手动 Bootstrap 以初始化纯数据类 (Registry、颜色表等)。
 */
public abstract class McBootstrap {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
