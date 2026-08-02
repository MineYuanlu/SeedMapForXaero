package bid.yuanlu.seedmap4xaero.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CellKeyTest {

    private static final int TILE = 64;

    @Test
    void worldOriginPositive() {
        var key = new CellCache.CellKey(4, 3, 5);
        assertEquals(3 * TILE * 4, key.worldX());
        assertEquals(5 * TILE * 4, key.worldZ());
        assertEquals(TILE * 4, key.blockSize());
    }

    @Test
    void worldOriginNegativeUsesFloorDiv() {
        // floorDiv(-1, 64) == -1 → 原点应为 -64 (覆盖 [-64, 0))
        var key = new CellCache.CellKey(1, -1, -1);
        assertEquals(-TILE, key.worldX());
        assertEquals(-TILE, key.worldZ());
        assertEquals(TILE, key.blockSize());
    }

    @Test
    void scale0Key() {
        var key = new CellCache.CellKey(64, 0, 0);
        assertEquals(0, key.worldX());
        assertEquals(TILE * 64, key.blockSize());
    }

    @Test
    void cellCoversExactlyBlockSize() {
        // 任意 cell 的 [worldX, worldX+blockSize) 应严格覆盖 64*scale 方块
        for (int scale : new int[] {1, 4, 16, 64, 256}) {
            for (int cell : new int[] {-3, -1, 0, 1, 7}) {
                var key = new CellCache.CellKey(scale, cell, cell);
                assertEquals(key.worldX() % key.blockSize(), 0);
                assertEquals(key.worldX() / key.blockSize(), cell);
            }
        }
    }
}
