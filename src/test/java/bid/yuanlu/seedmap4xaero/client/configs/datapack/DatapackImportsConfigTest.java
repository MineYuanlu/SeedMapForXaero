package bid.yuanlu.seedmap4xaero.client.configs.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;

/**
 * {@code datapack_imports.json}（手动导入路径列表，per-mainId）持久化往返：
 * add 去重保序、remove 越界、JSON roundtrip、损坏容错、优先级语义（add 顺序）。
 */
class DatapackImportsConfigTest {

    @TempDir
    Path tmp;

    private static JsonConfigFile.Paths paths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, "datapack_imports.json", "datapack_imports.json");
    }

    @Test
    void addDedupesAndKeepsPriorityOrder() {
        final var data = new DatapackImportsData();
        assertTrue(data.add("/a/first.zip"));
        assertTrue(data.add("/b/second"));
        assertFalse(data.add("/a/first.zip"), "duplicate add must be a no-op");
        assertEquals(List.of("/a/first.zip", "/b/second"), data.imports());
        // add 顺序 = 优先级递增: 列表末尾覆盖开头 (PathDatapackSource 消费契约)
        assertTrue(data.remove(0));
        assertEquals(List.of("/b/second"), data.imports());
        assertFalse(data.remove(5), "out of range remove must fail");
        assertFalse(data.remove(-1));
    }

    @Test
    void jsonRoundTripPreservesOrder() throws IOException {
        final var data = new DatapackImportsData();
        data.add("/packs/Terralith.zip");
        data.add("/packs/better mineshafts");  // 含空格路径
        data.add("/home/user/ packs /x");      // 原样保留 (不做 normalize, 输入侧职责)

        final Path base = tmp.resolve("base");
        assertTrue(DatapackImportsConfig.saveConfig(base, "Multiplayer_1.2.3.4", data, true));

        final var loaded = DatapackImportsConfig.loadConfig(base, "Multiplayer_1.2.3.4");
        assertEquals(data.imports(), loaded.imports());
    }

    @Test
    void missingFieldFallsBackToEmpty() throws IOException {
        final var loaded = DatapackImportsData.JSON_CODEC.read(
                com.google.gson.JsonParser.parseString("{\"version\":1}").getAsJsonObject());
        assertTrue(loaded.imports().isEmpty());
    }

    @Test
    void malformedEntriesTolerated() throws IOException {
        final var loaded = DatapackImportsData.JSON_CODEC.read(com.google.gson.JsonParser
                .parseString("""
                        {"version":1, "imports":[
                          "keep/me.zip",
                          42,
                          null,
                          {"obj":true},
                          "keep/me.zip",
                          "also/kept"]}
                        """).getAsJsonObject());
        // 非字符串条目丢弃, 字符串去重保序
        assertEquals(List.of("keep/me.zip", "also/kept"), loaded.imports());
    }

    @Test
    void corruptFileFallsBackToFallbackChain() throws IOException {
        final Path base = tmp.resolve("base");
        final Path file = base.resolve("Multiplayer_x").resolve("datapack_imports.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not valid json", StandardCharsets.UTF_8);

        final var loaded = DatapackImportsConfig.loadConfig(base, "Multiplayer_x");
        assertTrue(loaded.imports().isEmpty(), "corrupt file → fresh doc (回退链)");
        // 旧正本已被 load 链删除 (损坏即弃), 可重新保存
        assertTrue(DatapackImportsConfig.saveConfig(base, "Multiplayer_x",
                new DatapackImportsData(), true));
    }

    @Test
    void dirtyFlagContract() throws IOException {
        final var data = new DatapackImportsData();
        assertFalse(data.dirty.get());
        data.add("/x.zip");
        assertTrue(data.dirty.get(), "add 必须标脏");
        data.dirty.set(false);
        assertFalse(data.add("/x.zip"), "重复 add 不标脏 (no-op)");
        data.dirty.set(false);
        assertTrue(data.remove(0));
        assertTrue(data.dirty.get(), "成功 remove 必须标脏");
    }
}
