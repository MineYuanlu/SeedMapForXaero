package bid.yuanlu.seedmap4xaero.client.compat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * ResourceManager / Resource 的跨版本兼容门面。
 * <p>
 * MC 26.3 把 {@code listResources/listResourceStacks} 的过滤参数从
 * {@code Predicate<Identifier>} 换成了自有的 {@code Selector} 类型（二进制
 * 不兼容：按 26.1 编译的 invokeinterface descriptor 在 26.3 上
 * NoSuchMethodError），{@code getResourceStack} 的归属接口也有变动。
 * 这里按<b>方法名 + 参数个数</b>反射定位，过滤参数经 {@link Proxy} 适配
 * 目标参数类型（Predicate/Selector 均为单抽象方法 {@code test(Identifier)}），
 * 使同一份编译产物跨 26.1–26.3 运行。资源读取优先 {@code openAsReader}，
 * 回退 {@code open} + UTF-8。
 */
public final class CompatResources {

    private CompatResources() {
    }

    /**
     * {@code rm.listResources(folder, filter)}：递归列出 folder 下资源
     * （每 id 最高优先级层）。{@code filter} 在内部经代理适配目标版本参数。
     */
    @SuppressWarnings("unchecked")
    public static @NotNull Map<Identifier, Resource> listResources(
            @NotNull ResourceManager rm, @NotNull String folder, @NotNull Predicate<Identifier> filter) {
        final Method method = findMethod(rm.getClass(), "listResources", 2);
        final Object filterArg = adaptFilter(method.getParameterTypes()[1], filter);
        try {
            return (Map<Identifier, Resource>) method.invoke(rm, folder, filterArg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("listResources failed on " + rm.getClass(), e);
        }
    }

    /** {@code rm.getResourceStack(id)}：全部包层（高优先级在前）；不可用返回空。 */
    public static @NotNull List<Resource> resourceStack(@NotNull ResourceManager rm, @NotNull Identifier id) {
        final Method method = findMethod(rm.getClass(), "getResourceStack", 1);
        if (method == null)
            return List.of();
        try {
            return (List<Resource>) method.invoke(rm, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("getResourceStack failed on " + rm.getClass(), e);
        }
    }

    /** {@code rm.getResource(id)}：最高优先级层；缺失 → empty。 */
    @SuppressWarnings("unchecked")
    public static @NotNull Optional<Resource> resource(@NotNull ResourceManager rm, @NotNull Identifier id) {
        final Method method = findMethod(rm.getClass(), "getResource", 1);
        if (method == null)
            return Optional.empty();
        try {
            return (Optional<Resource>) method.invoke(rm, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("getResource failed on " + rm.getClass(), e);
        }
    }

    /** 读资源为 {@link BufferedReader}：优先 {@code openAsReader}，回退 {@code open} + UTF-8。 */
    public static @NotNull BufferedReader openReader(@NotNull Resource resource) throws IOException {
        try {
            final Method m = resource.getClass().getMethod("openAsReader");
            return (BufferedReader) m.invoke(resource);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof IOException io)
                throw io;
            throw new RuntimeException("openAsReader failed", e);
        }
        try {
            final InputStream in = (InputStream) resource.getClass().getMethod("open").invoke(resource);
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof IOException io)
                throw io;
            throw new RuntimeException("open failed", e);
        }
    }

    // ─── 内部 ──────────────────────────────────────────────────

    /** 沿继承树找指定名字 + 参数个数的方法（26.3 方法可能挂在父接口）。 */
    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> ifc : type.getInterfaces()) {
            final Method m = findMethod(ifc, name, paramCount);
            if (m != null)
                return m;
        }
        return null;
    }

    /**
     * 把 {@code Predicate<Identifier>} 适配为目标版本的过滤参数类型。
     * 类型恰好是 Predicate 时原样返回；否则代理目标接口——26.2- 为
     * {@code Predicate.test}、26.3+ 为 {@code Selector.isIncluded}（抽象方法
     * 名不保证稳定），故对<b>任意单参数方法</b>统一转发到 {@code filter.test}。
     */
    private static Object adaptFilter(Class<?> targetType, Predicate<Identifier> filter) {
        if (targetType.isInstance(filter))
            return filter;
        if (!targetType.isInterface())
            throw new IllegalStateException("filter parameter is not an interface: " + targetType);
        final InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1 && args[0] instanceof Identifier)
                return filter.test((Identifier) args[0]);
            return switch (method.getName()) {
                case "toString" -> "CompatResources.filter";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> {
                    if (method.getReturnType() == boolean.class)
                        yield false;
                    throw new UnsupportedOperationException("filter proxy: " + method.getName());
                }
            };
        };
        return Proxy.newProxyInstance(targetType.getClassLoader(), new Class<?>[] { targetType }, handler);
    }
}
