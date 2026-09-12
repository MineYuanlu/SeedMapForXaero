package bid.yuanlu.seedmap4xaero.client.configs.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

class StructureDataTest {

    @TempDir
    Path tmp;

    private static BitSetView enabled(int... ids) {
        var bs = new java.util.BitSet();
        for (int id : ids)
            bs.set(id);
        return new BitSetView(bs);
    }

    // ─── 标记语义 ───────────────────────────────────────────

    @Test
    void markVisitedKeepsMinDistance() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(42L).getOrCreateDim("Multiplayer_a");
        dim.markVisited(StructureType.VILLAGE.id, 100L, 8);
        assertEquals(8, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
        dim.markVisited(StructureType.VILLAGE.id, 100L, 3);
        assertEquals(3, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
        dim.markVisited(StructureType.VILLAGE.id, 100L, 9);
        assertEquals(3, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
    }

    @Test
    void markVisitedPreservesGroup() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.VILLAGE.id, 7L, StructureGroups.DONE);
        dim.markVisited(StructureType.VILLAGE.id, 7L, 5);
        var mark = dim.getMark(StructureType.VILLAGE.id, 7L);
        assertEquals(5, mark.minDist());
        assertEquals(StructureGroups.DONE, mark.group());
    }

    @Test
    void setGroupDefaultRemovesMark() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.SPECIAL);
        assertNotNull(dim.getMark(StructureType.MANSION.id, 9L));
        // 默认组 = 删除组: 清除整条记录 (含访问记录)
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.DEFAULT);
        dim.setGroup(StructureType.MANSION.id, 9L, null);
        assertNull(dim.getMark(StructureType.MANSION.id, 9L));
    }

    @Test
    void setGroupCreatesUnvisitedMark() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.HIDDEN);
        var mark = dim.getMark(StructureType.MANSION.id, 9L);
        assertFalse(mark.visited());
        assertEquals(StructureGroups.HIDDEN, mark.group());
    }

    @Test
    void dirtyFlagOnlyOnRealChange() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.markVisited(StructureType.VILLAGE.id, 1L, 5);
        data.dirty.set(false);
        dim.markVisited(StructureType.VILLAGE.id, 1L, 9); // 更远 → 无变化
        assertFalse(data.dirty.get());
        dim.markVisited(StructureType.VILLAGE.id, 1L, 2);
        assertTrue(data.dirty.get());
        data.dirty.set(false);
        dim.setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE);
        assertTrue(data.dirty.get());
        data.dirty.set(false);
        dim.setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE); // 同组 → 无变化
        assertFalse(data.dirty.get());
    }

    @Test
    void countGroupFiltersByEnabledTypes() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE);
        dim.setGroup(StructureType.VILLAGE.id, 2L, StructureGroups.DONE);
        dim.setGroup(StructureType.MANSION.id, 3L, StructureGroups.DONE);
        dim.setGroup(StructureType.MANSION.id, 4L, StructureGroups.SPECIAL);
        assertEquals(3, dim.countGroup(StructureGroups.DONE,
                enabled(StructureType.VILLAGE.id, StructureType.MANSION.id)));
        assertEquals(2, dim.countGroup(StructureGroups.DONE, enabled(StructureType.VILLAGE.id)));
        assertEquals(0, dim.countGroup(StructureGroups.DONE, enabled()));
    }

    // ─── 组可见性 ───────────────────────────────────────────

    @Test
    void hiddenGroupsToggle() {
        var data = new StructureData();
        assertFalse(data.isGroupHidden(StructureGroups.HIDDEN));
        data.setGroupHidden(StructureGroups.HIDDEN, true);
        assertTrue(data.isGroupHidden(StructureGroups.HIDDEN));
        data.setGroupHidden(StructureGroups.HIDDEN, true); // 重复无变化
        assertTrue(data.isGroupHidden(StructureGroups.HIDDEN));
        data.setGroupHidden(StructureGroups.HIDDEN, false);
        assertFalse(data.isGroupHidden(StructureGroups.HIDDEN));
    }

    // ─── 序列化 ─────────────────────────────────────────────

    private StructureData sample() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(123456789L).getOrCreateDim("Multiplayer_127.0.0.1");
        dim.markVisited(StructureType.VILLAGE.id, 100L, 7);
        dim.setGroup(StructureType.MANSION.id, 200L, StructureGroups.SPECIAL);
        var dim2 = data.getOrCreateSeed(123456789L).getOrCreateDim("Multiplayer_127.0.0.1$dim%-1");
        dim2.setGroup(StructureType.FORTRESS.id, 300L, StructureGroups.DONE);
        data.getOrCreateSeed(-1L).getOrCreateDim("solo").markVisited(
                StructureType.TREASURE.id, 400L, 2);
        data.setGroupHidden(StructureGroups.HIDDEN, true);
        data.setGroupHidden(StructureGroups.DEFAULT, true);
        data.addGroup("mines", 0xFF00AAFF);
        data.setGroupColor(StructureGroups.DONE, 0xFF22CC44);
        return data;
    }

    private void assertEq(StructureData expect, StructureData actual) {
        for (long seed : new long[] { 123456789L, -1L }) {
            for (String mwId : new String[] { "Multiplayer_127.0.0.1",
                    "Multiplayer_127.0.0.1$dim%-1", "solo" }) {
                for (StructureType type : StructureType.values()) {
                    if (type == StructureType.FEATURE)
                        continue;
                    for (long key : new long[] { 100L, 200L, 300L, 400L }) {
                        var e = expect.getSeed(seed) == null ? null
                                : expect.getSeed(seed).getDim(mwId);
                        var a = actual.getSeed(seed) == null ? null
                                : actual.getSeed(seed).getDim(mwId);
                        if (e == null) {
                            assertNull(a);
                            continue;
                        }
                        assertNotNull(a);
                        assertEquals(e.getMark(type.id, key), a.getMark(type.id, key),
                                () -> seed + "/" + mwId + "/" + type.id + "/" + key);
                    }
                }
            }
        }
        for (String g : StructureGroups.BUILTIN)
            assertEquals(expect.isGroupHidden(g), actual.isGroupHidden(g));
        assertEquals(expect.userGroups(), actual.userGroups());
        for (String g : StructureGroups.BUILTIN)
            assertEquals(expect.colorOf(g), actual.colorOf(g));
    }

    @Test
    void binaryRoundTrip() throws IOException {
        StructureData data = sample();
        Path file = tmp.resolve("sub/structure_data.sm4x");
        Files.createDirectories(file.getParent());

        Sm4xFile.writeFrame(file, data, StructureData.CODEC);
        StructureData read = Sm4xFile.readFrame(file, StructureData.CODEC);
        assertEq(data, read);
    }

    @Test
    void saveLoadFallbackChain() throws IOException {
        StructureData data = sample();
        Path base = tmp.resolve("base");
        StructureDataConfig.saveLoadForTest(base, "srv", data);

        StructureData read = StructureDataConfig.load(base, "srv");
        assertEq(data, read);

        // 主文件损坏 → 回退 .old (需先保存两次, 让第一份数据轮替到 .old)
        StructureData first = new StructureData();
        first.getOrCreateSeed(7L).getOrCreateDim("w")
                .setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE);
        StructureDataConfig.saveLoadForTest(base, "srv2", first);
        StructureDataConfig.saveLoadForTest(base, "srv2", new StructureData());
        Files.writeString(StructureDataConfig.targetPathForTest(base, "srv2"), "corrupt");

        StructureData readOld = StructureDataConfig.load(base, "srv2");
        assertEquals(StructureGroups.DONE,
                readOld.getSeed(7L).getDim("w").getMark(StructureType.VILLAGE.id, 1L).group());

        // 全部损坏 → 空文档
        Files.writeString(StructureDataConfig.targetPathForTest(base, "srv2").resolveSibling(
                StructureDataConfig.targetPathForTest(base, "srv2").getFileName() + ".old"),
                "corrupt");
        StructureData fresh = StructureDataConfig.load(base, "srv2");
        assertNull(fresh.getSeed(7L));
    }

    @Test
    void saveWithoutRotationKeepsOldFile() throws IOException {
        var data = sample();
        Path base = tmp.resolve("rot");
        StructureDataConfig.saveLoadForTest(base, "srv", data); // rotate=true, 无 .old 产生
        var paths = StructureDataConfig.pathsForTest(base, "srv");
        assertFalse(Files.exists(paths.old()));

        // 主动刷写 (rotate=false): .old 不被创建/覆盖
        var next = new StructureData();
        next.getOrCreateSeed(1L).getOrCreateDim("w").markVisited(
                StructureType.VILLAGE.id, 1L, 3);
        StructureDataConfig.flushForTest(base, "srv", next);
        assertFalse(Files.exists(paths.old()));
        assertEquals(3, StructureDataConfig.load(base, "srv")
                .getSeed(1L).getDim("w").getMark(StructureType.VILLAGE.id, 1L).minDist());

        // 已有 .old 时刷写同样不覆盖它
        StructureDataConfig.saveLoadForTest(base, "srv", data);
        StructureDataConfig.saveLoadForTest(base, "srv", data); // 轮替 → .old 出现
        assertTrue(Files.exists(paths.old()));
        byte[] oldBytes = Files.readAllBytes(paths.old());
        StructureDataConfig.flushForTest(base, "srv", next);
        assertArrayEquals(oldBytes, Files.readAllBytes(paths.old()));
    }

    @Test
    void perSeedIsolation() {
        var data = new StructureData();
        data.getOrCreateSeed(1L).getOrCreateDim("w")
                .setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE);
        assertNull(data.getSeed(2L));
        assertNotNull(data.getSeed(1L));
    }

    @Test
    void keyOfPacksBlockCoords() {
        assertEquals(0x12345678_9ABCDEF0L, StructureDataConfig.keyOf(0x12345678, 0x9ABCDEF0));
        assertEquals(-1L >>> 32, StructureDataConfig.keyOf(0, -1));
        // 负坐标不串位
        assertEquals(StructureDataConfig.keyOf(-1, -2), StructureDataConfig.keyOf(-1, -2));
        assertTrue(StructureDataConfig.keyOf(-1, -2) != StructureDataConfig.keyOf(-2, -1));
    }

    // ─── 种子级统计与删除 (二阶段: /sm4x history) ──────────────

    @Test
    void seedStatsCountsGroupsAndStructures() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.markVisited(StructureType.VILLAGE.id, 1L, 5); // 仅访问
        dim.setGroup(StructureType.VILLAGE.id, 2L, StructureGroups.DONE); // 仅分组
        dim.markVisited(StructureType.MANSION.id, 3L, 2);
        dim.setGroup(StructureType.MANSION.id, 3L, StructureGroups.DONE); // 访问+分组 = 1 条
        data.getOrCreateSeed(1L).getOrCreateDim("nether")
                .setGroup(StructureType.FORTRESS.id, 4L, StructureGroups.SPECIAL);

        var stats = data.stats(1L);
        assertNotNull(stats);
        assertEquals(2, stats.groups()); // done + special (跨维度去重)
        assertEquals(4, stats.structures()); // 4 条记录 (第 3 条只算一次)
        assertNull(data.stats(2L));
    }

    @Test
    void removeSeedRemovesAllDimsAndCounts() {
        var data = new StructureData();
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.markVisited(StructureType.VILLAGE.id, 1L, 5);
        dim.setGroup(StructureType.MANSION.id, 2L, StructureGroups.DONE);
        data.getOrCreateSeed(1L).getOrCreateDim("nether")
                .setGroup(StructureType.FORTRESS.id, 3L, StructureGroups.SPECIAL);
        data.getOrCreateSeed(2L).getOrCreateDim("w")
                .markVisited(StructureType.VILLAGE.id, 9L, 1);

        assertEquals(3, data.removeSeed(1L));
        assertNull(data.getSeed(1L));
        assertNotNull(data.getSeed(2L));
        assertEquals(0, data.removeSeed(1L)); // 幂等
    }

    @Test
    void seedsSnapshotSortedAscending() {
        var data = new StructureData();
        data.getOrCreateSeed(30L).getOrCreateDim("w");
        data.getOrCreateSeed(-10L).getOrCreateDim("w");
        data.getOrCreateSeed(20L).getOrCreateDim("w");
        assertArrayEquals(new long[] { -10L, 20L, 30L }, data.seedsSnapshot());
    }

    // ─── 用户组 (三阶段) ────────────────────────────────────────

    @Test
    void userGroupCrudValidation() {
        var data = new StructureData();
        assertTrue(data.addGroup("mines", 0xFF00AAFF));
        assertFalse(data.addGroup("mines", 0xFF000000)); // 重名
        assertFalse(data.addGroup("  ", 0)); // 空白
        assertFalse(data.addGroup(null, 0));
        assertFalse(data.addGroup("x".repeat(StructureData.MAX_GROUP_NAME + 1), 0)); // 超长
        assertFalse(data.addGroup(StructureGroups.DONE, 0)); // 内置重名
        assertFalse(data.addGroup(StructureGroups.DEFAULT, 0));
        assertEquals(1, data.userGroups().size());
        assertEquals("mines", data.userGroups().get(0).name());
        assertEquals(0xFF00AAFF, data.userGroups().get(0).color());
    }

    @Test
    void setGroupColorOverridesBuiltin() {
        var data = new StructureData();
        assertEquals(StructureGroups.builtinColor(StructureGroups.DONE),
                StructureGroups.colorOf(data, StructureGroups.DONE));
        assertTrue(data.setGroupColor(StructureGroups.DONE, 0xFF123456));
        assertEquals(0xFF123456, StructureGroups.colorOf(data, StructureGroups.DONE));
        assertTrue(data.clearGroupColor(StructureGroups.DONE));
        assertEquals(StructureGroups.builtinColor(StructureGroups.DONE),
                StructureGroups.colorOf(data, StructureGroups.DONE));
        // 未知组不能经 setGroupColor 创建; clear 仅内置组
        assertFalse(data.setGroupColor("ghost", 0xFF000001));
        assertFalse(data.clearGroupColor("ghost"));
    }

    @Test
    void renameGroupRewritesAllRefs() {
        var data = new StructureData();
        assertTrue(data.addGroup("mines", 0xFF00AAFF));
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.VILLAGE.id, 1L, "mines");
        dim.markVisited(StructureType.MANSION.id, 2L, 6);
        dim.setGroup(StructureType.MANSION.id, 2L, "mines");
        data.getOrCreateSeed(2L).getOrCreateDim("nether")
                .setGroup(StructureType.FORTRESS.id, 3L, "mines");
        data.setGroupHidden("mines", true);

        assertTrue(data.renameGroup("mines", "矿组"));
        assertEquals(1, data.userGroups().size());
        assertEquals("矿组", data.userGroups().get(0).name());
        assertEquals(0xFF00AAFF, data.colorOf("矿组"));
        assertEquals("矿组", dim.getMark(StructureType.VILLAGE.id, 1L).group());
        assertEquals("矿组", dim.getMark(StructureType.MANSION.id, 2L).group());
        assertEquals("矿组", data.getSeed(2L).getDim("nether")
                .getMark(StructureType.FORTRESS.id, 3L).group());
        assertTrue(data.isGroupHidden("矿组"));
        assertFalse(data.isGroupHidden("mines"));
        // 内置组不可改名; 目标重名/默认组名拒绝
        assertFalse(data.renameGroup(StructureGroups.DONE, "x"));
        assertFalse(data.renameGroup("矿组", StructureGroups.DONE));
        assertFalse(data.renameGroup("ghost", "x"));
    }

    @Test
    void removeGroupKeepsVisitClearsGroup() {
        var data = new StructureData();
        data.addGroup("mines", 0xFF00AAFF);
        var dim = data.getOrCreateSeed(1L).getOrCreateDim("w");
        dim.setGroup(StructureType.VILLAGE.id, 1L, "mines"); // 纯分组 → 整条删
        dim.markVisited(StructureType.MANSION.id, 2L, 6);
        dim.setGroup(StructureType.MANSION.id, 2L, "mines"); // 访问+分组 → 保留访问
        data.setGroupHidden("mines", true);

        assertTrue(data.removeGroup("mines"));
        assertNull(dim.getMark(StructureType.VILLAGE.id, 1L));
        var kept = dim.getMark(StructureType.MANSION.id, 2L);
        assertNotNull(kept);
        assertTrue(kept.visited());
        assertEquals(6, kept.minDist());
        assertEquals(StructureGroups.DEFAULT, kept.group());
        assertFalse(data.isGroupHidden("mines"));
        assertTrue(data.userGroups().isEmpty());
        assertFalse(data.removeGroup("mines")); // 幂等
        assertFalse(data.removeGroup(StructureGroups.SPECIAL)); // 内置拒绝
    }

    @Test
    void readsLegacyV0WithoutUserGroups() throws IOException {
        // 手工构造 v0 帧: MAGIC + [ver=0, hiddenGroups, seeds] + MAGIC
        var bos = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bos);
        out.write(Sm4xFile.MAGIC_WORD);
        out.writeInt(0);
        out.writeInt(1);
        out.writeUTF(StructureGroups.HIDDEN);
        out.writeInt(1); // seeds
        out.writeLong(7L);
        out.writeInt(1); // dims
        out.writeUTF("w");
        out.writeInt(0); // DimData version
        out.writeInt(1); // typeCount
        out.writeByte(StructureType.VILLAGE.id);
        out.writeInt(1); // entries
        out.writeLong(100L);
        out.writeInt(4);
        out.writeUTF(StructureGroups.DONE);
        out.write(Sm4xFile.MAGIC_WORD);
        out.flush();
        Path file = tmp.resolve("legacy.sm4x");
        Files.write(file, bos.toByteArray());

        StructureData read = Sm4xFile.readFrame(file, StructureData.CODEC);
        var mark = read.getSeed(7L).getDim("w").getMark(StructureType.VILLAGE.id, 100L);
        assertNotNull(mark);
        assertEquals(4, mark.minDist());
        assertEquals(StructureGroups.DONE, mark.group());
        assertTrue(read.userGroups().isEmpty());
        assertTrue(read.isGroupHidden(StructureGroups.HIDDEN));
    }
}
