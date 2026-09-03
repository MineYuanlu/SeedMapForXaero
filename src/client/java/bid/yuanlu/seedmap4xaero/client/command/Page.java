package bid.yuanlu.seedmap4xaero.client.command;

import java.util.List;

/**
 * 命令列表分页（纯函数，无 Minecraft 依赖，可 JVM 单测）。
 * 页码越界时自动收敛到有效范围（最少 1 页）。
 */
public final class Page {

    public static final int SIZE = 8;

    /** 一页切片: items 永不为 null（空列表 → 空页）。 */
    public record Paged<T>(List<T> items, int page, int total) {
    }

    private Page() {
    }

    public static <T> Paged<T> slice(List<T> all, int page) {
        int total = Math.max(1, (all.size() + SIZE - 1) / SIZE);
        int p = Math.min(Math.max(1, page), total);
        int from = (p - 1) * SIZE;
        if (from >= all.size())
            return new Paged<>(List.of(), p, total);
        int to = Math.min(from + SIZE, all.size());
        return new Paged<>(List.copyOf(all.subList(from, to)), p, total);
    }
}
