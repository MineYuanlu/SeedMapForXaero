package bid.yuanlu.seedmap4xaero.client.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.Identifier;

/**
 * PathDatapackSource（zip/目录合并数据源）JVM 单测：
 * 优先级覆盖、布局定位、递归列举、stack 合并、非法 id 跳过。
 */
class PathDatapackSourceTest {

    @TempDir
    Path tmp;

    // ─── fixture 构造 ──────────────────────────────────────────

    /** 标准数据包布局目录: data/<ns>/<path>。 */
    private Path makeDirPack(String name, String... entryAndContent) throws IOException {
        final Path root = tmp.resolve(name);
        for (int i = 0; i < entryAndContent.length; i += 2) {
            final Path file = root.resolve("data").resolve(entryAndContent[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, entryAndContent[i + 1], StandardCharsets.UTF_8);
        }
        return root;
    }

    /** 标准数据包布局 zip: data/<ns>/<path>。 */
    private Path makeZipPack(String name, String... entryAndContent) throws IOException {
        final Path root = tmp.resolve(name + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(root))) {
            for (int i = 0; i < entryAndContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry("data/" + entryAndContent[i]));
                zip.write(entryAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return root;
    }

    private static String contentOf(Reader reader) throws IOException {
        try (reader) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1)
                sb.append((char) c);
            return sb.toString();
        }
    }

    // ─── 目录 root ─────────────────────────────────────────────

    @Test
    void dirPackReadAllJsonRecursesAndFilters() throws IOException {
        final Path pack = makeDirPack("dirpack",
                "terralith/worldgen/structure_set/regular.json", "{\"a\":1}",
                "terralith/worldgen/structure_set/deep/nested.json", "{\"b\":2}",
                "terralith/pack.mcmeta", "{}",
                "terralith/worldgen/structure/x.json", "{\"s\":1}");
        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(pack))
                .readAllJson("worldgen/structure_set");
        assertEquals(2, out.size());
        assertTrue(out.containsKey(Identifier.parse("terralith:worldgen/structure_set/regular.json")));
        assertTrue(out.containsKey(Identifier.parse("terralith:worldgen/structure_set/deep/nested.json")));
        for (Reader r : out.values())
            r.close();
    }

    @Test
    void laterRootOverridesEarlierRoot() throws IOException {
        final Path low = makeDirPack("low",
                "terralith/worldgen/structure_set/s.json", "{\"v\":1}");
        final Path high = makeDirPack("high",
                "terralith/worldgen/structure_set/s.json", "{\"v\":2}");
        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(low, high))
                .readAllJson("worldgen/structure_set");
        assertEquals(1, out.size());
        assertEquals("{\"v\":2}", contentOf(
                out.get(Identifier.parse("terralith:worldgen/structure_set/s.json"))));
    }

    @Test
    void dirReadJsonStackLowToHighAcrossRoots() throws IOException {
        final Path low = makeDirPack("sl",
                "terralith/tags/worldgen/biome/x.json", "{\"b\":1}");
        final Path high = makeDirPack("sh",
                "terralith/tags/worldgen/biome/x.json", "{\"b\":2}");
        final List<? extends Reader> stack = new PathDatapackSource(List.of(low, high))
                .readJsonStack(Identifier.parse("terralith:tags/worldgen/biome/x.json"));
        assertNotNull(stack);
        assertEquals(2, stack.size());
        assertEquals("{\"b\":1}", contentOf(stack.get(0)));
        assertEquals("{\"b\":2}", contentOf(stack.get(1)));
    }

    @Test
    void readJsonTakesHighestRoot() throws IOException {
        final Path low = makeDirPack("jl",
                "c/worldgen/structure_icons.json", "{\"low\":1}");
        final Path high = makeDirPack("jh",
                "c/worldgen/structure_icons.json", "{\"high\":1}");
        final PathDatapackSource src = new PathDatapackSource(List.of(low, high));
        assertEquals("{\"high\":1}", contentOf(
                src.readJson(Identifier.parse("c:worldgen/structure_icons.json"))));
        assertNull(src.readJson(Identifier.parse("c:worldgen/none.json")));
    }

    @Test
    void invalidIdsSkippedAndNonDatapackIgnored() throws IOException {
        // data/ 直接平铺文件 (无命名空间) + 非法大写命名空间 → 均跳过, 不抛异常
        final Path pack = tmp.resolve("badpack");
        Files.createDirectories(pack.resolve("data/loose"));
        Files.writeString(pack.resolve("data/loose/worldgen.json"), "{}");
        Files.createDirectories(pack.resolve("data/BadNs/worldgen/structure_set"));
        Files.writeString(pack.resolve("data/BadNs/worldgen/structure_set/x.json"), "{}");

        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(pack))
                .readAllJson("worldgen/structure_set");
        assertTrue(out.isEmpty());
    }

    // ─── zip root ──────────────────────────────────────────────

    @Test
    void zipPackWorksSameAsDirPack() throws IOException {
        final Path zip = makeZipPack("zippack",
                "terralith/worldgen/structure_set/regular.json", "{\"z\":1}");
        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(zip))
                .readAllJson("worldgen/structure_set");
        assertEquals(1, out.size());
        assertEquals("{\"z\":1}", contentOf(
                out.get(Identifier.parse("terralith:worldgen/structure_set/regular.json"))));
        assertEquals("{\"z\":1}", contentOf(new PathDatapackSource(List.of(zip))
                .readJson(Identifier.parse("terralith:worldgen/structure_set/regular.json"))));
    }

    @Test
    void zipAndDirRootsMixedByPriority() throws IOException {
        final Path zipLow = makeZipPack("mixedzip",
                "terralith/worldgen/structure_set/s.json", "{\"src\":\"zip\"}");
        final Path dirHigh = makeDirPack("mixeddir",
                "terralith/worldgen/structure_set/s.json", "{\"src\":\"dir\"}");
        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(zipLow, dirHigh))
                .readAllJson("worldgen/structure_set");
        assertEquals("{\"src\":\"dir\"}", contentOf(
                out.get(Identifier.parse("terralith:worldgen/structure_set/s.json"))));
    }

    @Test
    void missingRootIsTolerated() {
        final Path gone = tmp.resolve("nonexistent");
        final PathDatapackSource src = new PathDatapackSource(List.of(gone));
        assertTrue(src.readAllJson("worldgen/structure_set").isEmpty());
        assertNull(src.readJsonStack(Identifier.parse("terralith:x.json")));
        assertNull(src.readJson(Identifier.parse("terralith:x.json")));
    }

    @Test
    void utf8ContentSurvives() throws IOException {
        final Path pack = makeDirPack("utf",
                "terralith/worldgen/structure_set/u.json", "{\"n\":\"群系\"}");
        final Map<Identifier, ? extends Reader> out = new PathDatapackSource(List.of(pack))
                .readAllJson("worldgen/structure_set");
        assertTrue(contentOf(out.values().iterator().next()).contains("群系"));
    }
}
