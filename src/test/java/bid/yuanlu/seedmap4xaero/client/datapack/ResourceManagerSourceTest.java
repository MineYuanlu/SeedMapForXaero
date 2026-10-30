package bid.yuanlu.seedmap4xaero.client.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * ResourceManagerSource 对 ResourceManager 的适配语义 (JVM, fake ResourceManager):
 * <ul>
 * <li>readAllJson: 递归列出 + 只收 .json 后缀 + 每 id 最高优先级层</li>
 * <li>readJsonStack: 高→低 反转为 低→高 (tag 合并顺序)</li>
 * <li>readJson: 最高优先级层 / 缺失 → null</li>
 * </ul>
 */
class ResourceManagerSourceTest {

    /** 每 id 按优先级高→低存层; listResources 递归前缀匹配, 只暴露给被测代码语义。 */
    private static final class FakeRm implements ResourceManager {
        final Map<Identifier, List<Resource>> layers = new HashMap<>();

        void put(String id, String... contentsHighToLow) {
            List<Resource> stack = new ArrayList<>();
            for (String content : contentsHighToLow) {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                stack.add(new Resource(null, () -> new java.io.ByteArrayInputStream(bytes)));
            }
            layers.put(Identifier.parse(id), List.copyOf(stack));
        }

        private String read(Resource r) {
            try (BufferedReader br = r.openAsReader()) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = br.read()) != -1)
                    sb.append((char) c);
                return sb.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of();
        }

        @Override
        public Optional<Resource> getResource(Identifier id) {
            List<Resource> stack = layers.get(id);
            return stack == null ? Optional.empty() : Optional.of(stack.get(0));
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return layers.getOrDefault(id, List.of());
        }

        @Override
        public Map<Identifier, Resource> listResources(String folder, Predicate<Identifier> filter) {
            Map<Identifier, Resource> out = new LinkedHashMap<>();
            layers.forEach((id, stack) -> {
                if (id.getPath().startsWith(folder + "/") && filter.test(id))
                    out.put(id, stack.get(0));
            });
            return out;
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String folder, Predicate<Identifier> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
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

    @Test
    void readAllJsonFiltersNonJsonAndRecurses() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure_set/regular.json", "{}");
        rm.put("terralith:worldgen/structure_set/deep/nested.json", "{}");
        rm.put("terralith:worldgen/structure_set/pack.mcmeta", "{}");
        rm.put("terralith:worldgen/structure/txt.json", "{}");
        rm.put("terralith:worldgen/biome/other.json", "{}");

        Map<Identifier, ? extends Reader> out = new ResourceManagerSource(rm)
                .readAllJson("worldgen/structure_set");
        assertEquals(Set.of(
                "terralith:worldgen/structure_set/regular.json",
                "terralith:worldgen/structure_set/deep/nested.json"),
                out.keySet().stream().map(Identifier::toString).collect(java.util.stream.Collectors.toSet()));
        for (Reader r : out.values())
            r.close();
    }

    @Test
    void readAllJsonTakesHighestLayer() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure_set/regular.json", "{\"v\":2}", "{\"v\":1}");

        Map<Identifier, ? extends Reader> out = new ResourceManagerSource(rm)
                .readAllJson("worldgen/structure_set");
        assertEquals(1, out.size());
        Reader reader = out.get(Identifier.parse("terralith:worldgen/structure_set/regular.json"));
        assertNotNull(reader);
        assertEquals("{\"v\":2}", contentOf(reader));
    }

    @Test
    void readJsonStackReversesToLowFirst() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:tags/worldgen/biome/has_structure/x.json", "{\"b\":2}", "{\"b\":1}");

        List<? extends Reader> stack = new ResourceManagerSource(rm)
                .readJsonStack(Identifier.parse("terralith:tags/worldgen/biome/has_structure/x.json"));
        assertNotNull(stack);
        assertEquals(2, stack.size());
        assertEquals("{\"b\":1}", contentOf(stack.get(0)));
        assertEquals("{\"b\":2}", contentOf(stack.get(1)));
    }

    @Test
    void readJsonStackMissingReturnsNull() {
        FakeRm rm = new FakeRm();
        assertNull(new ResourceManagerSource(rm)
                .readJsonStack(Identifier.parse("terralith:tags/worldgen/biome/none.json")));
    }

    @Test
    void readJsonReturnsTopLayerOrNullOrNullWhenMissing() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure/s.json", "{\"top\":true}", "{\"low\":true}");
        ResourceManagerSource src = new ResourceManagerSource(rm);

        assertEquals("{\"top\":true}", contentOf(
                src.readJson(Identifier.parse("terralith:worldgen/structure/s.json"))));
        assertNull(src.readJson(Identifier.parse("terralith:worldgen/structure/none.json")));
    }

    @Test
    void readersCarryUtf8Content() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure_set/u.json", "{\"n\":\"组\"}");
        Reader r = new ResourceManagerSource(rm)
                .readAllJson("worldgen/structure_set")
                .get(Identifier.parse("terralith:worldgen/structure_set/u.json"));
        assertNotNull(r);
        assertTrue(contentOf(r).contains("组"), StandardCharsets.UTF_8.name());
    }
}
