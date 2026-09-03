package bid.yuanlu.seedmap4xaero.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PageTest {

    private static List<Integer> range(int n) {
        var out = new ArrayList<Integer>(n);
        for (int i = 0; i < n; i++)
            out.add(i);
        return out;
    }

    @Test
    void slicesIntoPagesOfEight() {
        var p = Page.slice(range(20), 2);
        assertEquals(3, p.total());
        assertEquals(2, p.page());
        assertEquals(8, p.items().size());
        assertEquals(8, p.items().get(0));
        assertEquals(15, p.items().get(7));
    }

    @Test
    void lastPageIsPartial() {
        var p = Page.slice(range(20), 3);
        assertEquals(4, p.items().size());
        assertEquals(16, p.items().get(0));
    }

    @Test
    void clampsOutOfRangePages() {
        assertEquals(1, Page.slice(range(20), 0).page());
        assertEquals(3, Page.slice(range(20), 99).page());
        assertEquals(4, Page.slice(range(20), 99).items().size());
    }

    @Test
    void emptyListIsSingleEmptyPage() {
        var p = Page.slice(List.of(), 1);
        assertEquals(1, p.total());
        assertEquals(1, p.page());
        assertTrue(p.items().isEmpty());
    }
}
