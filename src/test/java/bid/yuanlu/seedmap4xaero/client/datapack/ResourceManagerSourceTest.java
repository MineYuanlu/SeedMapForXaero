package bid.yuanlu.seedmap4xaero.client.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;
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

    /**
     * 每 id 按优先级高→低存层; listResources 递归前缀匹配。
     * 经 {@link Proxy} 按方法名分发实现 ResourceManager（26.3 起过滤参数
     * 类型由 Predicate 改 Selector, 显式 @Override 跨版本编译不兼容）。
     */
    private static final class FakeRm {
        final Map<Identifier, List<Resource>> layers = new HashMap<>();

        void put(String id, String... contentsHighToLow) {
            List<Resource> stack = new ArrayList<>();
            for (String content : contentsHighToLow) {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                stack.add(new Resource(null, () -> new java.io.ByteArrayInputStream(bytes)));
            }
            layers.put(Identifier.parse(id), List.copyOf(stack));
        }

        ResourceManager proxy() {
            return (ResourceManager) Proxy.newProxyInstance(
                    ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class },
                    (p, method, args) -> switch (method.getName()) {
                        case "getResource" -> {
                            List<Resource> stack = layers.get(args[0]);
                            yield stack == null ? Optional.empty() : Optional.of(stack.get(0));
                        }
                        case "getResourceStack" -> layers.getOrDefault(args[0], List.of());
                        case "listResources" -> {
                            Map<Identifier, Resource> out = new LinkedHashMap<>();
                            layers.forEach((id, stack) -> {
                                if (id.getPath().startsWith(args[0] + "/") && applyFilter(args[1], id))
                                    out.put(id, stack.get(0));
                            });
                            yield out;
                        }
                        case "getNamespaces" -> Set.of();
                        case "listPacks" -> Stream.empty();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        /**
         * 过滤参数跨版本是 Predicate（test）/ Selector（isIncluded，26.3+，
         * 传入的可能是 CompatResources 的 Proxy 实现），按两个已知方法名
         * 反射调用。
         */
        private static boolean applyFilter(Object filter, Identifier id) {
            for (Method m : filter.getClass().getMethods()) {
                if (m.getParameterCount() == 1
                        && (m.getName().equals("test") || m.getName().equals("isIncluded"))) {
                    try {
                        return (boolean) m.invoke(filter, id);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            throw new IllegalStateException("filter has no single-arg test/isIncluded method: " + filter.getClass());
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

        Map<Identifier, ? extends Reader> out = new ResourceManagerSource(rm.proxy())
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

        Map<Identifier, ? extends Reader> out = new ResourceManagerSource(rm.proxy())
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

        List<? extends Reader> stack = new ResourceManagerSource(rm.proxy())
                .readJsonStack(Identifier.parse("terralith:tags/worldgen/biome/has_structure/x.json"));
        assertNotNull(stack);
        assertEquals(2, stack.size());
        assertEquals("{\"b\":1}", contentOf(stack.get(0)));
        assertEquals("{\"b\":2}", contentOf(stack.get(1)));
    }

    @Test
    void readJsonStackMissingReturnsNull() {
        FakeRm rm = new FakeRm();
        assertNull(new ResourceManagerSource(rm.proxy())
                .readJsonStack(Identifier.parse("terralith:tags/worldgen/biome/none.json")));
    }

    @Test
    void readJsonReturnsTopLayerOrNullOrNullWhenMissing() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure/s.json", "{\"top\":true}", "{\"low\":true}");
        ResourceManagerSource src = new ResourceManagerSource(rm.proxy());

        assertEquals("{\"top\":true}", contentOf(
                src.readJson(Identifier.parse("terralith:worldgen/structure/s.json"))));
        assertNull(src.readJson(Identifier.parse("terralith:worldgen/structure/none.json")));
    }

    @Test
    void readersCarryUtf8Content() throws IOException {
        FakeRm rm = new FakeRm();
        rm.put("terralith:worldgen/structure_set/u.json", "{\"n\":\"组\"}");
        Reader r = new ResourceManagerSource(rm.proxy())
                .readAllJson("worldgen/structure_set")
                .get(Identifier.parse("terralith:worldgen/structure_set/u.json"));
        assertNotNull(r);
        assertTrue(contentOf(r).contains("组"), StandardCharsets.UTF_8.name());
    }
}
