package bid.yuanlu.seedmap4xaero.client.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@link DatapackImports} 可 JVM 单测的纯函数部分（路径解析）。
 * 交互全流程（add/remove/reload）依赖 MC 客户端，由 E2E 覆盖。
 */
class DatapackImportsTest {

    private static final Path GAME_DIR = Path.of("/game").toAbsolutePath().normalize();

    @Test
    void relativePathResolvedAgainstGameDir() {
        assertEquals(GAME_DIR.resolve("packs/x.zip").normalize().toString(),
                DatapackImports.resolvePath("packs/x.zip", GAME_DIR).toString());
        assertEquals(GAME_DIR.resolve("packs/x.zip").normalize().toString(),
                DatapackImports.resolvePath("./packs/x.zip", GAME_DIR).toString());
    }

    @Test
    void absolutePathKeptAndNormalized() {
        assertEquals("/data/packs/x.zip",
                DatapackImports.resolvePath("/data/packs/../packs/x.zip", GAME_DIR).toString());
    }

    @Test
    void homeExpansion() {
        final String home = System.getProperty("user.home");
        assertEquals(home + "/packs/x.zip",
                DatapackImports.resolvePath("~/packs/x.zip", GAME_DIR).toString());
        assertEquals(home,
                DatapackImports.resolvePath("~", GAME_DIR).toString());
    }

    @Test
    void blankAndSpacesStripped() {
        assertEquals(GAME_DIR.resolve("a.zip").normalize().toString(),
                DatapackImports.resolvePath("  a.zip  ", GAME_DIR).toString());
    }
}
