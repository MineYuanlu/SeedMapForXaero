package bid.yuanlu.seedmap4xaero.client.configs.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

class StructureDataTest {

    @TempDir
    Path tmp;

    private static long key(int x, int z) {
        return MarksStore.keyOf(x, z);
    }

    private static BitSetView enabled(int... ids) {
        var bs = new java.util.BitSet();
        for (int id : ids)
            bs.set(id);
        return new BitSetView(bs);
    }

    private static JsonConfigFile.Paths settingsPaths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, "structure_settings.json", "structure_data.sm4x");
    }

    private static Path regionFile(Path marksDir, long seed, String mwId, String name) {
        return marksDir.resolve(Long.toHexString(seed)).resolve(mwId).resolve(name);
    }

    // ─── 标记语义 (DimData) ─────────────────────────────────

    @Test
    void markVisitedKeepsMinDistance() {
        var dim = new MarksStore.DimData();
        dim.markVisited(StructureType.VILLAGE.id, 100L, 8);
        assertEquals(8, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
        dim.markVisited(StructureType.VILLAGE.id, 100L, 3);
        assertEquals(3, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
        dim.markVisited(StructureType.VILLAGE.id, 100L, 9);
        assertEquals(3, dim.getMark(StructureType.VILLAGE.id, 100L).minDist());
    }

    @Test
    void markVisitedPreservesGroup() {
        var dim = new MarksStore.DimData();
        dim.setGroup(StructureType.VILLAGE.id, 7L, StructureGroups.DONE);
        dim.markVisited(StructureType.VILLAGE.id, 7L, 5);
        var mark = dim.getMark(StructureType.VILLAGE.id, 7L);
        assertEquals(5, mark.minDist());
        assertEquals(StructureGroups.DONE, mark.group());
    }

    @Test
    void setGroupDefaultRemovesMark() {
        var dim = new MarksStore.DimData();
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.SPECIAL);
        assertNotNull(dim.getMark(StructureType.MANSION.id, 9L));
        // 默认组 = 删除组: 清除整条记录 (含访问记录)
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.DEFAULT);
        dim.setGroup(StructureType.MANSION.id, 9L, null);
        assertNull(dim.getMark(StructureType.MANSION.id, 9L));
    }

    @Test
    void setGroupCreatesUnvisitedMark() {
        var dim = new MarksStore.DimData();
        dim.setGroup(StructureType.MANSION.id, 9L, StructureGroups.HIDDEN);
        var mark = dim.getMark(StructureType.MANSION.id, 9L);
        assertFalse(mark.visited());
        assertEquals(StructureGroups.HIDDEN, mark.group());
    }

    @Test
    void countGroupFiltersByEnabledTypes() {
        var dim = new MarksStore.DimData();
        dim.setGroup(StructureType.VILLAGE.id, 1L, StructureGroups.DONE);
        dim.setGroup(StructureType.VILLAGE.id, 2L, StructureGroups.DONE);
        dim.setGroup(StructureType.MANSION.id, 3L, StructureGroups.DONE);
        dim.setGroup(StructureType.MANSION.id, 4L, StructureGroups.SPECIAL);
        assertEquals(3, dim.countGroup(StructureGroups.DONE,
                enabled(StructureType.VILLAGE.id, StructureType.MANSION.id)));
        assertEquals(2, dim.countGroup(StructureGroups.DONE, enabled(StructureType.VILLAGE.id)));
        assertEquals(0, dim.countGroup(StructureGroups.DONE, enabled()));
    }

    @Test
    void mutationsMarkExactlyOneRegionDirty() {
        var dim = new MarksStore.DimData();
        dim.markVisited(StructureType.VILLAGE.id, key(100, 200), 5); // region (0,0)
        assertArrayEquals(new long[] { MarksStore.regionIdxOf(key(100, 200)) },
                dim.dirtyRegionsForTest());
        // 无变化不标脏
        dim.markVisited(StructureType.VILLAGE.id, key(100, 200), 9);
        assertEquals(1, dim.dirtyRegionsForTest().length);
        // 跨 region 边界 → 第二个脏 region
        dim.setGroup(StructureType.MANSION.id, key(1024, 0), StructureGroups.DONE); // region (1,0)
        assertEquals(2, dim.dirtyRegionsForTest().length);
        // 删除也标脏
        dim.setGroup(StructureType.MANSION.id, key(1024, 0), null);
        assertEquals(2, dim.dirtyRegionsForTest().length);
    }

    @Test
    void keyOfPacksBlockCoords() {
        assertEquals(0x12345678_9ABCDEF0L, StructureDataConfig.keyOf(0x12345678, 0x9ABCDEF0));
        assertEquals(-1L >>> 32, StructureDataConfig.keyOf(0, -1));
        // 负坐标不串位
        assertTrue(StructureDataConfig.keyOf(-1, -2) != StructureDataConfig.keyOf(-2, -1));
    }

    // ─── 组可见性 (设置文档) ────────────────────────────────

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

    // ─── 设置文档 JSON roundtrip ────────────────────────────

    @Test
    void settingsJsonRoundTrip() throws IOException {
        var data = new StructureData();
        data.setGroupHidden(StructureGroups.HIDDEN, true);
        data.setGroupHidden(StructureGroups.DEFAULT, true);
        data.addGroup("mines", 0xFF00AAFF);
        data.setGroupColor(StructureGroups.DONE, 0xFF22CC44);

        var paths = settingsPaths(tmp, "srv");
        Files.createDirectories(paths.target().getParent());
        JsonConfigFile.save(paths, data, StructureData.JSON_CODEC, true);
        StructureData read = JsonConfigFile.readJson(paths.target(), StructureData.JSON_CODEC);

        assertTrue(read.isGroupHidden(StructureGroups.HIDDEN));
        assertTrue(read.isGroupHidden(StructureGroups.DEFAULT));
        // "mines" 用户组 + DONE 内置组颜色覆盖 = 2 条
        assertEquals(2, read.userGroups().size());
        assertEquals("mines", read.userGroups().get(0).name());
        assertEquals(0xFF00AAFF, read.userGroups().get(0).color());
        assertEquals(0xFF22CC44, read.colorOf(StructureGroups.DONE));

        String text = Files.readString(paths.target(), StandardCharsets.UTF_8);
        assertTrue(text.contains("mines"), "非 ASCII/自定义组名可读 (disableHtmlEscaping)");
    }

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
    void settingsRenameAndRemoveOnlyTouchGroupTable() {
        // 设置文档的 rename/remove 只改组表+隐藏表; 标记引用重写在 MarksStore (由门面编排)
        var data = new StructureData();
        data.addGroup("mines", 0xFF00AAFF);
        data.setGroupHidden("mines", true);
        assertTrue(data.renameGroup("mines", "矿组"));
        assertEquals("矿组", data.userGroups().get(0).name());
        assertTrue(data.isGroupHidden("矿组"));
        assertFalse(data.isGroupHidden("mines"));
        assertFalse(data.renameGroup(StructureGroups.DONE, "x")); // 内置拒绝
        assertFalse(data.renameGroup("ghost", "x"));
        assertTrue(data.removeGroup("矿组"));
        assertTrue(data.userGroups().isEmpty());
        assertFalse(data.isGroupHidden("矿组"));
        assertFalse(data.removeGroup(StructureGroups.SPECIAL)); // 内置拒绝
    }

    // ─── marks 分片 ─────────────────────────────────────────

    @Test
    void regionShardRoundTrip() throws IOException {
        var marksDir = tmp.resolve("marks");
        var store = new MarksStore(marksDir);
        var dd = store.doc(42L, "w");
        dd.markVisited(StructureType.VILLAGE.id, key(100, 200), 7);
        dd.setGroup(StructureType.MANSION.id, key(-300, 400), StructureGroups.SPECIAL);
        store.flush(true);

        assertTrue(Files.exists(regionFile(marksDir, 42L, "w", "r.0.0.json")),
                "hex(42)=2a, block(100,200) → region (0,0)");
        String text = Files.readString(regionFile(marksDir, 42L, "w", "r.0.0.json"),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("\"minecraft:village\""), "key 形态带 minecraft: 前缀:\n" + text);

        // 新 store (同目录) 惰性加载读回
        var store2 = new MarksStore(marksDir);
        var dd2 = store2.doc(42L, "w");
        assertEquals(7, dd2.getMark(StructureType.VILLAGE.id, key(100, 200)).minDist());
        assertEquals(StructureGroups.SPECIAL,
                dd2.getMark(StructureType.MANSION.id, key(-300, 400)).group());
    }

    @Test
    void regionBoundarySeparatesFiles() {
        var marksDir = tmp.resolve("marksB");
        var store = new MarksStore(marksDir);
        var dd = store.doc(7L, "w");
        dd.markVisited(StructureType.VILLAGE.id, key(1023, 0), 1); // region (0,0)
        dd.markVisited(StructureType.VILLAGE.id, key(1024, 0), 2); // region (1,0)
        dd.markVisited(StructureType.VILLAGE.id, key(-1, 0), 3); // region (-1,0)
        store.flush(true);

        assertTrue(Files.exists(regionFile(marksDir, 7L, "w", "r.0.0.json")));
        assertTrue(Files.exists(regionFile(marksDir, 7L, "w", "r.1.0.json")));
        assertTrue(Files.exists(regionFile(marksDir, 7L, "w", "r.-1.0.json")));
    }

    @Test
    void regionFileRotationContract() throws IOException {
        var marksDir = tmp.resolve("marksR");
        var store = new MarksStore(marksDir);
        var dd = store.doc(9L, "w");
        dd.markVisited(StructureType.VILLAGE.id, key(0, 0), 1);
        store.flush(true); // 首次: 无正本可轮替

        // 变更 (markVisited 距离不变时不标脏, 用 setGroup 驱动)
        dd.setGroup(StructureType.VILLAGE.id, key(0, 0), StructureGroups.DONE);
        store.flush(true); // 轮替: .old = dist 1 / 默认组

        var old = JsonConfigFile.readJson(
                regionFile(marksDir, 9L, "w", "r.0.0.json.old"), MarksStore.REGION_CODEC);
        assertEquals(1, old.marks().get("minecraft:village").get(0).minDist());
        assertEquals("", old.marks().get("minecraft:village").get(0).group());

        // 会话刷写 (rotate=false): .old 不被覆盖
        dd.setGroup(StructureType.VILLAGE.id, key(0, 0), StructureGroups.SPECIAL);
        store.flush(false);
        byte[] oldBytes = Files.readAllBytes(regionFile(marksDir, 9L, "w", "r.0.0.json.old"));
        dd.setGroup(StructureType.VILLAGE.id, key(0, 0), StructureGroups.HIDDEN);
        store.flush(false);
        assertArrayEquals(oldBytes, Files.readAllBytes(regionFile(marksDir, 9L, "w", "r.0.0.json.old")));
    }

    @Test
    void emptyRegionFileDeleted() throws IOException {
        var marksDir = tmp.resolve("marksE");
        var store = new MarksStore(marksDir);
        var dd = store.doc(5L, "w");
        dd.setGroup(StructureType.VILLAGE.id, key(10, 10), StructureGroups.DONE);
        store.flush(true);
        assertTrue(Files.exists(regionFile(marksDir, 5L, "w", "r.0.0.json")));

        // 记录删除 (默认组) → region 变空 → 文件删除
        dd.setGroup(StructureType.VILLAGE.id, key(10, 10), null);
        store.flush(true);
        assertFalse(Files.exists(regionFile(marksDir, 5L, "w", "r.0.0.json")));
        // .tmp 也不残留
        assertFalse(Files.exists(regionFile(marksDir, 5L, "w", "r.0.0.json.tmp")));
    }

    @Test
    void corruptRegionSkippedOthersLoad() throws IOException {
        var marksDir = tmp.resolve("marksC");
        var store = new MarksStore(marksDir);
        var dd = store.doc(3L, "w");
        dd.markVisited(StructureType.VILLAGE.id, key(0, 0), 1); // r.0.0
        dd.markVisited(StructureType.VILLAGE.id, key(1024, 0), 2); // r.1.0
        store.flush(true);

        Files.writeString(regionFile(marksDir, 3L, "w", "r.0.0.json"), "{corrupt");

        var store2 = new MarksStore(marksDir);
        var dd2 = store2.doc(3L, "w");
        assertNull(dd2.getMark(StructureType.VILLAGE.id, key(0, 0)), "损坏 region 被跳过");
        assertEquals(2, dd2.getMark(StructureType.VILLAGE.id, key(1024, 0)).minDist(),
                "其余 region 不受影响");
    }

    @Test
    void orphanStructureKeysPreserved() throws IOException {
        var marksDir = tmp.resolve("marksO");
        var p = regionFile(marksDir, 11L, "w", "r.0.0.json");
        Files.createDirectories(p.getParent());
        Files.writeString(p, """
                {
                  "version": 1,
                  "marks": {
                    "terralith:volcanic_peak": [[12, 34, 56, "done"]],
                    "futuremod:castle": [[1, 2, -1, ""]]
                  }
                }
                """);

        var store = new MarksStore(marksDir);
        var dd = store.doc(11L, "w");
        // 未知 key 无法归位 (不产生可查标记), 但 flush 后原样保留
        assertNull(dd.getMark(StructureType.VILLAGE.id, key(12, 34)));
        store.flush(true);

        String out = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(out.contains("terralith:volcanic_peak"), out);
        assertTrue(out.contains("futuremod:castle"), out);
    }

    @Test
    void mwIdEmptyMapsToDefaultDir() throws IOException {
        var marksDir = tmp.resolve("marksM");
        var store = new MarksStore(marksDir);
        store.doc(6L, "").markVisited(StructureType.VILLAGE.id, key(1, 1), 9);
        store.flush(true);
        assertTrue(Files.exists(regionFile(marksDir, 6L, "default", "r.0.0.json")),
                "空 mwId (单机默认) → default 目录");
    }

    // ─── 种子级统计与删除 (/sm4x 命令) ──────────────────────

    @Test
    void seedStatsCountsGroupsAndStructures() {
        var store = new MarksStore(tmp.resolve("marksS"));
        var dim = store.doc(1L, "w");
        dim.markVisited(StructureType.VILLAGE.id, 1L, 5); // 仅访问
        dim.setGroup(StructureType.VILLAGE.id, 2L, StructureGroups.DONE); // 仅分组
        dim.markVisited(StructureType.MANSION.id, 3L, 2);
        dim.setGroup(StructureType.MANSION.id, 3L, StructureGroups.DONE); // 访问+分组 = 1 条
        store.doc(1L, "nether").setGroup(StructureType.FORTRESS.id, 4L, StructureGroups.SPECIAL);

        var stats = store.stats(1L);
        assertNotNull(stats);
        assertEquals(2, stats.groups()); // done + special (跨维度去重)
        assertEquals(4, stats.structures()); // 4 条记录
        assertNull(store.stats(2L));
    }

    @Test
    void seedsSnapshotSortedAscending() {
        var marksDir = tmp.resolve("marksN");
        var store = new MarksStore(marksDir);
        store.doc(30L, "w").markVisited(StructureType.VILLAGE.id, key(1, 1), 1);
        store.doc(-10L, "w").markVisited(StructureType.VILLAGE.id, key(1, 1), 1);
        store.doc(20L, "w").markVisited(StructureType.VILLAGE.id, key(1, 1), 1);
        store.flush(true); // 快照基于磁盘目录 (有标记才存在)
        assertArrayEquals(new long[] { -10L, 20L, 30L }, store.seedsSnapshot());
    }

    @Test
    void removeSeedRemovesAllDimsAndCounts() {
        var marksDir = tmp.resolve("marksD");
        var store = new MarksStore(marksDir);
        store.doc(1L, "w").markVisited(StructureType.VILLAGE.id, 1L, 5);
        store.doc(1L, "w").setGroup(StructureType.MANSION.id, 2L, StructureGroups.DONE);
        store.doc(1L, "nether").setGroup(StructureType.FORTRESS.id, 3L, StructureGroups.SPECIAL);
        store.doc(2L, "w").markVisited(StructureType.VILLAGE.id, 9L, 1);
        store.flush(true);

        assertEquals(3, store.removeSeed(1L));
        assertFalse(Files.exists(marksDir.resolve(Long.toHexString(1L))), "种子目录删除");
        assertEquals(0, store.removeSeed(1L)); // 幂等
        // 其他种子不受影响
        var store2 = new MarksStore(marksDir);
        assertNotNull(store2.doc(2L, "w").getMark(StructureType.VILLAGE.id, 9L));
    }

    // ─── 组引用重写 (跨全部分片) ────────────────────────────

    @Test
    void renameGroupRewritesCachedDocs() throws IOException {
        var marksDir = tmp.resolve("marksG");
        var store = new MarksStore(marksDir);
        store.doc(1L, "w").setGroup(StructureType.VILLAGE.id, key(1, 1), "mines");
        store.doc(1L, "w").markVisited(StructureType.MANSION.id, key(2, 2), 6);
        store.doc(1L, "w").setGroup(StructureType.MANSION.id, key(2, 2), "mines");
        store.doc(2L, "nether").setGroup(StructureType.FORTRESS.id, key(3, 3), "mines");

        store.rewriteGroupRefs("mines", "矿组"); // 缓存文档: 内存重写 + 标脏
        store.flush(true);

        var store2 = new MarksStore(marksDir);
        assertEquals("矿组", store2.doc(1L, "w")
                .getMark(StructureType.VILLAGE.id, key(1, 1)).group());
        assertEquals("矿组", store2.doc(1L, "w")
                .getMark(StructureType.MANSION.id, key(2, 2)).group());
        assertEquals("矿组", store2.doc(2L, "nether")
                .getMark(StructureType.FORTRESS.id, key(3, 3)).group());
    }

    @Test
    void renameGroupRewritesUncachedShardsOnDisk() throws IOException {
        var marksDir = tmp.resolve("marksH");
        var store = new MarksStore(marksDir);
        store.doc(4L, "w").setGroup(StructureType.VILLAGE.id, key(1, 1), "mines");
        store.flush(true);

        // 全新 store (无缓存): 直接改写磁盘分片
        var store2 = new MarksStore(marksDir);
        store2.rewriteGroupRefs("mines", "矿组");

        var store3 = new MarksStore(marksDir);
        assertEquals("矿组", store3.doc(4L, "w")
                .getMark(StructureType.VILLAGE.id, key(1, 1)).group());
    }

    @Test
    void removeGroupKeepsVisitClearsGroupAcrossShards() throws IOException {
        var marksDir = tmp.resolve("marksI");
        var store = new MarksStore(marksDir);
        store.doc(1L, "w").setGroup(StructureType.VILLAGE.id, key(1, 1), "mines"); // 纯分组 → 整条删
        store.doc(1L, "w").markVisited(StructureType.MANSION.id, key(2, 2), 6);
        store.doc(1L, "w").setGroup(StructureType.MANSION.id, key(2, 2), "mines"); // 访问+分组 → 保留访问
        store.flush(true);

        store.rewriteGroupRefs("mines", StructureGroups.DEFAULT);
        store.flush(true);

        var store2 = new MarksStore(marksDir);
        assertNull(store2.doc(1L, "w").getMark(StructureType.VILLAGE.id, key(1, 1)),
                "无访问的纯分组记录整条删除");
        var kept = store2.doc(1L, "w").getMark(StructureType.MANSION.id, key(2, 2));
        assertNotNull(kept);
        assertTrue(kept.visited());
        assertEquals(6, kept.minDist());
        assertEquals(StructureGroups.DEFAULT, kept.group());
    }

    // ─── legacy structure_data.sm4x 迁移 ────────────────────

    @Test
    void legacyMigrationSplitsSettingsAndMarks() throws IOException {
        var overworldDim = new StructureDataLegacy.LegacyDim(Map.of(
                StructureType.VILLAGE.id,
                List.of(new StructureDataLegacy.LegacyMark(100L, 4, StructureGroups.DONE))));
        var netherDim = new StructureDataLegacy.LegacyDim(Map.of(
                StructureType.FORTRESS.id,
                List.of(new StructureDataLegacy.LegacyMark(key(1024, 0), 2, StructureGroups.SPECIAL))));
        var seed = new StructureDataLegacy.LegacySeed(Map.of(
                "w", overworldDim,
                "nether", netherDim));
        var snap = new StructureDataLegacy.Snapshot(
                List.of(StructureGroups.HIDDEN),
                List.of(new StructureData.UserGroup("mines", 0xFF00AAFF)),
                new Long2ObjectOpenHashMap<>(Map.of(7L, seed)));

        Path base = tmp.resolve("base");
        var paths = settingsPaths(base, "srv");
        Files.createDirectories(paths.legacy().getParent());
        Sm4xFile.writeFrame(paths.legacy(), snap, StructureDataLegacy.LEGACY_CODEC);

        StructureData settings = StructureDataConfig.load(base, "srv");
        assertTrue(settings.isGroupHidden(StructureGroups.HIDDEN));
        assertEquals(1, settings.userGroups().size());
        assertEquals("mines", settings.userGroups().get(0).name());

        // legacy 改名退出回退链; 设置 json + marks 分片生成
        assertFalse(Files.exists(paths.legacy()));
        assertTrue(Files.exists(paths.target()));
        assertTrue(Files.exists(base.resolve("srv/marks/7/w/r.0.0.json")));

        // 分片可读回 (含跨 region 边界的旧标记)
        var store = new MarksStore(base.resolve("srv/marks"));
        assertEquals(4, store.doc(7L, "w")
                .getMark(StructureType.VILLAGE.id, 100L).minDist());
        assertEquals(StructureGroups.DONE,
                store.doc(7L, "w").getMark(StructureType.VILLAGE.id, 100L).group());
        assertEquals(StructureGroups.SPECIAL,
                store.doc(7L, "nether").getMark(StructureType.FORTRESS.id, key(1024, 0)).group());

        // 二次加载: 直接走 json, 幂等
        StructureData again = StructureDataConfig.load(base, "srv");
        assertTrue(again.isGroupHidden(StructureGroups.HIDDEN));
    }

    @Test
    void readsLegacyV0WithoutUserGroups() throws IOException {
        // 手工构造 v0 帧: MAGIC + [ver=0, hiddenGroups, seeds] + MAGIC (无 userGroups 段)
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

        var snap = Sm4xFile.readFrame(file, StructureDataLegacy.LEGACY_CODEC);
        var mark = snap.seeds().get(7L).dims().get("w").types()
                .get(StructureType.VILLAGE.id).get(0);
        assertEquals(4, mark.minDist());
        assertEquals(StructureGroups.DONE, mark.group());
        assertTrue(snap.userGroups().isEmpty());
        assertEquals(List.of(StructureGroups.HIDDEN), snap.hiddenGroups());
    }
    // ─── golden fixture: 真实 legacy 字节的端到端迁移 ───────────

    @Test
    void goldenLegacyFileMigratesAllFields() throws IOException {
        Path base = tmp.resolve("base");
        var paths = StructureDataConfig.settingsPathsForTest(base, "srv");
        Files.createDirectories(paths.legacy().getParent());
        byte[] legacyBytes;
        try (var in = getClass().getResourceAsStream("/legacy/structure_data.sm4x")) {
            legacyBytes = in.readAllBytes();
            Files.write(paths.legacy(), legacyBytes);
        }

        StructureData settings = StructureDataConfig.load(base, "srv");

        // 设置: 隐藏组 + unicode 用户组 + 内置组颜色覆盖
        assertTrue(settings.isGroupHidden(StructureGroups.HIDDEN));
        assertEquals(3, settings.userGroups().size(), "矿队⚡ + done2 + DONE 覆盖");
        assertEquals(0xFF00AAFF, settings.colorOf("矿队⚡"));
        assertEquals(0x11223344, settings.colorOf("done2"));
        assertEquals(0x80FF5555, settings.colorOf(StructureGroups.DONE), "内置组颜色覆盖");

        // 标记: 跨 (seed, mwId, region) 全维度
        var store = new MarksStore(base.resolve("srv/marks"));
        var village = store.doc(7L, "w").getMark(StructureType.VILLAGE.id, 100L);
        assertNotNull(village);
        assertEquals(4, village.minDist());
        assertEquals(StructureGroups.DONE, village.group());
        var mansion = store.doc(7L, "w").getMark(StructureType.MANSION.id, key(2, 2));
        assertNotNull(mansion);
        assertFalse(mansion.visited(), "纯分组标记");
        assertEquals("矿队⚡", mansion.group());
        // orphan: 注册表外结构 id 250 → id:250 key 保留在 region 文档
        String region0 = Files.readString(base.resolve("srv/marks/7/w/r.0.0.json"));
        assertTrue(region0.contains("id:250"), region0);
        // 空 mwId → default 目录
        var stronghold = store.doc(7L, "").getMark(StructureType.STRONGHOLD.id, key(1024, 0));
        assertNotNull(stronghold);
        assertEquals(1, stronghold.minDist());
        assertEquals("done2", stronghold.group());
        assertTrue(Files.exists(base.resolve("srv/marks/7/default/r.1.0.json")));
        // 负种子 (Long.MIN_VALUE) + 跨 region 边界 (blockX -1024 → region -1)
        var fortress = store.doc(Long.MIN_VALUE, "w").getMark(StructureType.FORTRESS.id, key(-1024, 512));
        assertNotNull(fortress);
        assertEquals(2, fortress.minDist());
        assertTrue(Files.exists(base.resolve("srv/marks/8000000000000000/w/r.-1.0.json")));

        // legacy 改名退出回退链
        assertFalse(Files.exists(paths.legacy()));

        // 幂等: 模拟 crash (settings/marks 产物全删 + legacy 复原) → 重迁移逐字节一致
        String settingsJsonFirst = Files.readString(paths.target());
        byte[] region0First = region0.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.delete(paths.target());
        deleteRecursively(base.resolve("srv/marks"));
        Files.write(paths.legacy(), legacyBytes);
        StructureDataConfig.load(base, "srv");
        assertEquals(settingsJsonFirst, Files.readString(paths.target()), "settings 重迁移一致");
        assertEquals(new String(region0First, java.nio.charset.StandardCharsets.UTF_8),
                Files.readString(base.resolve("srv/marks/7/w/r.0.0.json")), "region 重迁移一致");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
    /**
     * v0.6.1 原版 writer 产出的真实 legacy 字节迁移（数据与合成 golden 同语义）。
     */
    @Test
    void realV061WriterFileMigrates() throws IOException {
        Path base = tmp.resolve("baseR");
        var paths = StructureDataConfig.settingsPathsForTest(base, "srv");
        Files.createDirectories(paths.legacy().getParent());
        try (var in = getClass().getResourceAsStream("/legacy/real/structure_data.sm4x")) {
            Files.copy(in, paths.legacy());
        }

        StructureData settings = StructureDataConfig.load(base, "srv");
        assertTrue(settings.isGroupHidden(StructureGroups.HIDDEN));
        assertEquals(0xFF00AAFF, settings.colorOf("矿队⚡"));
        assertEquals(0x80FF5555, settings.colorOf(StructureGroups.DONE));

        var store = new MarksStore(base.resolve("srv/marks"));
        var village = store.doc(7L, "w").getMark(StructureType.VILLAGE.id, 100L);
        assertEquals(4, village.minDist());
        assertEquals(StructureGroups.DONE, village.group());
        assertEquals("矿队⚡", store.doc(7L, "w").getMark(StructureType.MANSION.id, key(2, 2)).group());
        assertEquals("done2", store.doc(7L, "").getMark(StructureType.STRONGHOLD.id, key(1024, 0)).group());
        assertEquals(2, store.doc(Long.MIN_VALUE, "w").getMark(StructureType.FORTRESS.id, key(-1024, 512)).minDist());
        assertFalse(Files.exists(paths.legacy()));
    }

    /**
     * v0.6.1 E2E 真实运行残留的 legacy 字节：仅一个用户组颜色 + 一个空维度表。
     * 验证稀疏/接近空的真实文件迁移不崩溃、颜色覆盖保留、空维度不产生垃圾分片。
     */
    @Test
    void realV061OrganicSparseFileMigrates() throws IOException {
        Path base = tmp.resolve("baseO");
        var paths = StructureDataConfig.settingsPathsForTest(base, "srv");
        Files.createDirectories(paths.legacy().getParent());
        try (var in = getClass().getResourceAsStream("/legacy/real/structure_data_organic.sm4x")) {
            Files.copy(in, paths.legacy());
        }

        StructureData settings = StructureDataConfig.load(base, "srv");
        // E2E 创建的用户组 "Group1" (色 0x80FF5555) 完整保留
        assertEquals(0x80FF5555, settings.colorOf("Group1"));
        assertFalse(settings.isGroupHidden(StructureGroups.HIDDEN));

        // 种子 123456789 的维度表为空 → 不产生任何 region 分片
        var marksDir = base.resolve("srv/marks/75bcd15");
        assertFalse(Files.exists(marksDir), "空维度迁移不应产生 marks 目录: " + marksDir);
        assertFalse(Files.exists(paths.legacy()));
    }
}
