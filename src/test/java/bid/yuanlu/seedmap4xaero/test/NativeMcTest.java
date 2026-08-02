package bid.yuanlu.seedmap4xaero.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

/**
 * 依赖 native 库 (libxsmcore) 已加载且已设置游戏版本/世界的测试基类。
 * <p>
 * native 不可用时 (如 {@code -PskipNativeBuild} 或未安装工具链) 整个套件
 * assumption 跳过; native 可用时执行真实 cubiomes 查询。C 状态为全局,
 * {@link #seed()} 保证各子类用例可复现。
 */
public abstract class NativeMcTest extends McBootstrap {

    /** 各子类共享的种子; 子类可在调用 Xsm API 前自行 setWorld 覆盖。 */
    protected static final long SEED = 123456789L;

    @BeforeAll
    static void initNative() {
        try {
            // 触发 Xsm 静态初始化 (System.load) + 校验 native 已链接
            Xsm.getStructFEATURE_NUM();
            Xsm.setGameVersion();
            Xsm.setBiomeColorTable();
            Xsm.setWorld(SEED, 0);
        } catch (Throwable t) {
            Assumptions.abort("native library unavailable, skipping: " + t.getMessage());
        }
    }
}
