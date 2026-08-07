package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 会话级结构高亮集合 (不持久化)。
 * <p>
 * 渲染线程 (LevelRenderEvents / HUD 回调) 只读, 点击线程写入; {@link ConcurrentHashMap#newKeySet}
 * 保证无锁安全。世界切换/断线时由客户端入口调用 {@link #clear()}。
 */
public final class HighlightedStructures {

    /** 一个高亮: 维度 + 结构锚点方块坐标 + 类型/变种 (HUD 图标需要精灵 UV)。 */
    public record Key(ResourceKey<Level> dim, int blockX, int blockZ,
            StructureType type, int variant) {
    }

    private static final Set<Key> HIGHLIGHTED = ConcurrentHashMap.newKeySet();

    private HighlightedStructures() {
    }

    public static boolean contains(ResourceKey<Level> dim, int blockX, int blockZ,
            StructureType type, int variant) {
        return HIGHLIGHTED.contains(new Key(dim, blockX, blockZ, type, variant));
    }

    /** 切换高亮状态 (已高亮则取消, 否则添加)。 */
    public static void toggle(ResourceKey<Level> dim, int blockX, int blockZ,
            StructureType type, int variant) {
        Key key = new Key(dim, blockX, blockZ, type, variant);
        if (!HIGHLIGHTED.add(key)) {
            HIGHLIGHTED.remove(key);
        }
    }

    /** 渲染线程遍历用 (返回的 set 是并发快照视图)。 */
    public static Set<Key> all() {
        return HIGHLIGHTED;
    }

    public static void clear() {
        HIGHLIGHTED.clear();
    }
}
