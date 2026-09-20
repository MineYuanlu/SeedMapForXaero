package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;
import bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile;
import bid.yuanlu.seedmap4xaero.client.structure.StructureKeys;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 结构持久标记的分片存储：{@code marks/<seedHex>/<mwId>/r.<x>.<z>.json}。
 * <p>
 * 分片粒度 = 64×64 区块（{@link #REGION_BLOCKS} 方块）一个 region 文件。
 * 收益：写放大从"全量文档"降到"单 region"（分组点击/周期刷盘只写脏 region）、
 * 损坏半径隔离（单 region 损坏不影响其他，损坏文件跳过加载）、
 * 种子清理 = 删目录。
 * <p>
 * 加载策略：按 (seed, mwId) 惰性加载整维度（该维度的全部 region 文件），
 * 之后驻留内存；region 文件很小（结构密度天然限界），无淘汰压力。
 * 未注册结构 key 的标记以 orphan 形式原样保留（数据包结构移除后数据不丢，
 * 恢复后自动重挂）。
 * <p>
 * 轮替契约同 {@link JsonConfigFile#save}：生命周期检查点 {@code flush(true)} /
 * 会话刷写与周期刷盘 {@code flush(false)}，由门面在调用点保证。
 */
public final class MarksStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/MarksStore");

    /** region 边长（方块）：64×64 区块 = 1024×1024。 */
    public static final int REGION_BLOCKS = 1024;

    /** 某种子的数据量统计（/sm4x 命令）。 */
    public record SeedStats(int groups, int structures) {
    }

    /** 一个种子维度内的标记文档（typeId → posKey → mark），原 StructureData.DimData。 */
    public static final class DimData {
        private final Long2ObjectOpenHashMap<StructureMark>[] types;
        /** 自上次落盘后有变更的 region（打包坐标）。 */
        private final LongOpenHashSet dirtyRegions = new LongOpenHashSet();
        /** 未注册结构 key 的标记（region 文件读入时无法归位者），写出时按 region 并回。 */
        private @Nullable List<OrphanMark> orphans;

        record OrphanMark(String structureKey, long key, int minDist, String group) {
        }

        @SuppressWarnings("unchecked")
        DimData() {
            types = new Long2ObjectOpenHashMap[StructureType.FEATURE_NUM];
        }

        private Long2ObjectOpenHashMap<StructureMark> map(int typeId) {
            var m = types[typeId];
            if (m == null) {
                m = new Long2ObjectOpenHashMap<>();
                types[typeId] = m;
            }
            return m;
        }

        public @Nullable StructureMark getMark(int typeId, long key) {
            if (typeId < 0 || typeId >= types.length)
                return null;
            var m = types[typeId];
            return m == null ? null : m.get(key);
        }

        /** 记录一次访问, 取历史最小距离; 无变化不标脏。 */
        public void markVisited(int typeId, long key, int dist) {
            var m = map(typeId);
            synchronized (this) {
                var cur = m.get(key);
                var next = cur == null ? new StructureMark(dist, StructureGroups.DEFAULT)
                        : cur.withVisit(dist);
                if (next == cur)
                    return;
                m.put(key, next);
                dirtyRegions.add(MarksStore.regionIdxOf(key));
            }
        }

        /**
         * 设置分组; {@code null} 或 {@link StructureGroups#DEFAULT} 表示删除标记记录
         * (默认组 = 删除组)。记录不存在时若设置非默认组则新建 (未访问仅分组)。
         */
        public void setGroup(int typeId, long key, @Nullable String group) {
            var m = map(typeId);
            synchronized (this) {
                if (group == null || group.equals(StructureGroups.DEFAULT)) {
                    if (m.remove(key) != null)
                        dirtyRegions.add(MarksStore.regionIdxOf(key));
                    return;
                }
                var cur = m.get(key);
                if (cur != null && cur.group().equals(group))
                    return;
                m.put(key, new StructureMark(
                        cur == null ? StructureMark.NO_VISIT : cur.minDist(), group));
                dirtyRegions.add(MarksStore.regionIdxOf(key));
            }
        }

        /** 统计某组标记数, 仅计入 {@code enabledTypes} 启用的结构类型。 */
        public int countGroup(String group, BitSetView enabledTypes) {
            int count = 0;
            synchronized (this) {
                for (int id = 0; id < types.length; id++) {
                    if (!enabledTypes.get(id))
                        continue;
                    var m = types[id];
                    if (m == null)
                        continue;
                    for (var mark : m.values()) {
                        if (mark.group().equals(group))
                            count++;
                    }
                }
            }
            return count;
        }

        /** 标记记录总数（含访问/分组任意一种，不含 orphan）。 */
        int recordCount() {
            int n = 0;
            synchronized (this) {
                for (var m : types)
                    if (m != null)
                        n += m.size();
            }
            return n;
        }

        /** 累加统计: 把非默认组名收进 {@code groups}，返回本维度记录数。 */
        int accumulateStats(java.util.Set<String> groups) {
            int n = 0;
            synchronized (this) {
                for (var m : types) {
                    if (m == null)
                        continue;
                    n += m.size();
                    for (var mark : m.values()) {
                        if (!mark.group().isEmpty())
                            groups.add(mark.group());
                    }
                }
            }
            return n;
        }

        /**
         * 组引用重写 (组改名/删组): from→to（含 orphan）。to 为默认组且记录未访问
         * → 整条删除; 否则仅改组名、保留访问距离。变更的 region 全部标脏。
         */
        boolean reassignGroup(String from, String to) {
            boolean changed = false;
            synchronized (this) {
                for (var m : types) {
                    if (m == null)
                        continue;
                    var it = m.long2ObjectEntrySet().iterator();
                    while (it.hasNext()) {
                        var e = it.next();
                        var mark = e.getValue();
                        if (!mark.group().equals(from))
                            continue;
                        if (to.equals(StructureGroups.DEFAULT) && !mark.visited())
                            it.remove();
                        else
                            e.setValue(new StructureMark(mark.minDist(), to));
                        changed = true;
                    }
                }
                if (orphans != null) {
                    var it = orphans.listIterator();
                    while (it.hasNext()) {
                        var o = it.next();
                        if (!o.group().equals(from))
                            continue;
                        if (to.equals(StructureGroups.DEFAULT) && o.minDist() < 0)
                            it.remove();
                        else
                            it.set(new OrphanMark(o.structureKey(), o.key(), o.minDist(), to));
                        changed = true;
                    }
                }
                if (changed)
                    markAllRegionsDirty();
            }
            return changed;
        }

        private void markAllRegionsDirty() {
            for (var m : types) {
                if (m == null)
                    continue;
                for (var e : m.long2ObjectEntrySet())
                    dirtyRegions.add(MarksStore.regionIdxOf(e.getLongKey()));
            }
            if (orphans != null)
                for (var o : orphans)
                    dirtyRegions.add(MarksStore.regionIdxOf(o.key()));
        }

        private void addOrphan(OrphanMark o) {
            if (orphans == null)
                orphans = new ArrayList<>();
            orphans.add(o);
        }

        /** 单测辅助: 脏 region 快照。 */
        long[] dirtyRegionsForTest() {
            synchronized (this) {
                return dirtyRegions.toLongArray();
            }
        }
    }

    // ─── region 文件模型 ────────────────────────────────────────

    /** region 文件内一条标记：[x, z, minDist, group]。 */
    public record MarkEntry(int x, int z, int minDist, String group) {
    }

    /** region 文件文档：结构 key → 标记列表（LinkedHashMap 保持写出顺序稳定）。 */
    record RegionDoc(LinkedHashMap<String, List<MarkEntry>> marks) {

        int totalMarks() {
            int n = 0;
            for (var l : marks.values())
                n += l.size();
            return n;
        }
    }

    static final JsonCodec<RegionDoc> REGION_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(RegionDoc data) {
            var json = new JsonObject();
            json.addProperty("version", 1);
            var marks = new JsonObject();
            for (var e : data.marks().entrySet()) {
                var arr = new JsonArray();
                for (MarkEntry m : e.getValue()) {
                    var o = new JsonArray();
                    o.add(m.x());
                    o.add(m.z());
                    o.add(m.minDist());
                    o.add(m.group());
                    arr.add(o);
                }
                marks.add(e.getKey(), arr);
            }
            json.add("marks", marks);
            return json;
        }

        @Override
        public RegionDoc read(JsonObject json) throws IOException {
            try {
                var marks = new LinkedHashMap<String, List<MarkEntry>>();
                if (json.has("marks")) {
                    var marksJson = json.getAsJsonObject("marks");
                    for (var e : marksJson.entrySet()) {
                        var list = new ArrayList<MarkEntry>();
                        for (JsonElement el : e.getValue().getAsJsonArray()) {
                            var o = el.getAsJsonArray();
                            if (o.size() < 3)
                                throw new IOException("Mark entry needs [x,z,minDist,group?]: " + o);
                            list.add(new MarkEntry(o.get(0).getAsInt(), o.get(1).getAsInt(),
                                    o.get(2).getAsInt(),
                                    o.size() >= 4 ? o.get(3).getAsString() : StructureGroups.DEFAULT));
                        }
                        marks.put(e.getKey(), list);
                    }
                }
                return new RegionDoc(marks);
            } catch (RuntimeException ex) {
                throw new IOException("Malformed marks region document", ex);
            }
        }
    };

    // ─── 存储主体 ───────────────────────────────────────────────

    private final Path marksDir;
    /** seed → mwId → 文档（惰性加载后驻留）。 */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, DimData>> docs = new ConcurrentHashMap<>();

    public MarksStore(Path marksDir) {
        this.marksDir = marksDir;
    }

    /** 结构持久 key: (blockX<<32)|blockZ（与门面 {@code keyOf} 一致）。 */
    public static long keyOf(int blockX, int blockZ) {
        return (long) blockX << 32 | (blockZ & 0xFFFFFFFFL);
    }

    /** 标记 key → region 打包坐标 ((rx<<32)|rz)。 */
    static long regionIdxOf(long key) {
        int x = (int) (key >> 32), z = (int) key;
        return ((long) (x >> 10) << 32) | ((z >> 10) & 0xFFFFFFFFL);
    }

    /** 取 (seed, mwId) 的维度标记文档；首次访问从分片惰性加载。 */
    public @NotNull DimData doc(long seed, @NotNull String mwId) {
        return docs.computeIfAbsent(seed, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mwId, k -> loadDoc(seed, mwId));
    }

    /** 已缓存的文档（不触发加载；统计/组重写路径用）。 */
    private @Nullable DimData cachedDoc(long seed, String mwId) {
        var perSeed = docs.get(seed);
        return perSeed != null ? perSeed.get(mwId) : null;
    }

    /**
     * 目录名 → 缓存键（原始 mwId）的反查。缓存键是原始 mwId，而磁盘目录名经过
     * {@link #dimDirName} 消毒（空串 → default），反查时以消毒结果匹配。
     */
    private @Nullable String rawMwIdFor(long seed, String dirName) {
        var perSeed = docs.get(seed);
        if (perSeed != null) {
            for (String key : perSeed.keySet()) {
                if (dimDirName(key).equals(dirName))
                    return key;
            }
        }
        return null;
    }

    private DimData loadDoc(long seed, String mwId) {
        var dd = new DimData();
        var dir = dimDir(seed, mwId);
        if (!Files.isDirectory(dir))
            return dd;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to list marks dir {}", dir, e);
            return dd;
        }
        for (Path f : files) {
            try {
                mergeRegion(dd, JsonConfigFile.readJson(f, REGION_CODEC));
            } catch (IOException e) {
                // 损坏 region 跳过: 其余 region 不受影响 (分片损坏隔离)
                LOGGER.error("Skipping corrupt marks region {}", f, e);
            }
        }
        return dd;
    }

    private static void mergeRegion(DimData dd, RegionDoc doc) {
        synchronized (dd) {
            for (var e : doc.marks().entrySet()) {
                int typeId = StructureKeys.resolveId(e.getKey());
                for (MarkEntry m : e.getValue()) {
                    long key = keyOf(m.x(), m.z());
                    if (typeId >= 0 && typeId < StructureType.FEATURE_NUM) {
                        dd.map(typeId).put(key, new StructureMark(m.minDist(), m.group()));
                    } else {
                        dd.addOrphan(new DimData.OrphanMark(e.getKey(), key, m.minDist(), m.group()));
                    }
                }
            }
        }
    }

    /** 收集某 region 的全部标记（含 orphan）为 region 文档。 */
    private static RegionDoc collectRegion(DimData dd, long regionIdx) {
        var marks = new LinkedHashMap<String, List<MarkEntry>>();
        synchronized (dd) {
            for (int typeId = 0; typeId < dd.types.length; typeId++) {
                var m = dd.types[typeId];
                if (m == null || m.isEmpty())
                    continue;
                String structureKey = StructureKeys.persistedKey(typeId);
                for (var e : m.long2ObjectEntrySet()) {
                    long key = e.getLongKey();
                    if (regionIdxOf(key) != regionIdx)
                        continue;
                    marks.computeIfAbsent(structureKey, k -> new ArrayList<>())
                            .add(new MarkEntry((int) (key >> 32), (int) key,
                                    e.getValue().minDist(), e.getValue().group()));
                }
            }
            if (dd.orphans != null) {
                for (var o : dd.orphans) {
                    if (regionIdxOf(o.key()) != regionIdx)
                        continue;
                    marks.computeIfAbsent(o.structureKey(), k -> new ArrayList<>())
                            .add(new MarkEntry((int) (o.key() >> 32), (int) o.key(),
                                    o.minDist(), o.group()));
                }
            }
        }
        return new RegionDoc(marks);
    }

    /**
     * 写出全部已缓存文档的脏 region。
     * {@code rotate=true} = 生命周期检查点 / {@code false} = 会话刷写（轮替契约见类 javadoc）。
     */
    public synchronized void flush(boolean rotate) {
        for (var seedEntry : docs.entrySet()) {
            for (var dimEntry : seedEntry.getValue().entrySet()) {
                flushDoc(seedEntry.getKey(), dimEntry.getKey(), dimEntry.getValue(), rotate);
            }
        }
    }

    private void flushDoc(long seed, String mwId, DimData dd, boolean rotate) {
        final long[] dirty;
        synchronized (dd) {
            if (dd.dirtyRegions.isEmpty())
                return;
            dirty = dd.dirtyRegions.toLongArray();
        }
        for (long regionIdx : dirty) {
            if (writeRegion(seed, mwId, dd, regionIdx, rotate)) {
                synchronized (dd) {
                    dd.dirtyRegions.remove(regionIdx);
                }
            }
        }
    }

    /** 写单个 region；区域已空则删除文件。返回是否成功（失败保持脏，下轮重写）。 */
    private boolean writeRegion(long seed, String mwId, DimData dd, long regionIdx, boolean rotate) {
        RegionDoc doc = collectRegion(dd, regionIdx);
        var paths = regionPaths(seed, mwId, regionIdx);
        try {
            if (doc.marks().isEmpty()) {
                Files.deleteIfExists(paths.target());
                Files.deleteIfExists(paths.tmp());
                return true;
            }
            JsonConfigFile.save(paths, doc, REGION_CODEC, rotate);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to write marks region {} (seed {})", paths.target(), seed, e);
            return false;
        }
    }

    // ─── 组引用重写 (全量: 缓存文档 + 未缓存分片) ────────────────

    /** 组改名/删组时重写全部标记引用（跨全部分片）；变更 region 标脏由 {@link #flush} 落盘。 */
    public synchronized void rewriteGroupRefs(String from, String to) {
        for (var perSeed : docs.values())
            for (var dd : perSeed.values())
                dd.reassignGroup(from, to);

        // 未缓存的 (seed, mwId) 分片: 直接读文件改写回写
        for (long seed : seedsSnapshot()) {
            var seedDir = marksDir.resolve(Long.toHexString(seed));
            if (!Files.isDirectory(seedDir))
                continue;
            for (String dirName : listDimDirs(seedDir)) {
                if (rawMwIdFor(seed, dirName) != null)
                    continue; // 缓存文档已覆盖 (含全部 region), 由 flush 落盘
                rewriteGroupRefsOnDisk(seedDir.resolve(dirName), from, to);
            }
        }
    }

    private void rewriteGroupRefsOnDisk(Path dimDir, String from, String to) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dimDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.json"))
                    .sorted().toList();
        } catch (IOException e) {
            LOGGER.error("Failed to list marks dir {}", dimDir, e);
            return;
        }
        for (Path f : files) {
            try {
                var doc = JsonConfigFile.readJson(f, REGION_CODEC);
                boolean changed = false;
                for (var e : doc.marks().entrySet()) {
                    var list = e.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        var m = list.get(i);
                        if (!m.group().equals(from))
                            continue;
                        if (to.equals(StructureGroups.DEFAULT) && m.minDist() < 0)
                            list.remove(i--);
                        else
                            list.set(i, new MarkEntry(m.x(), m.z(), m.minDist(), to));
                        changed = true;
                    }
                }
                if (changed) {
                    if (doc.marks().isEmpty())
                        Files.deleteIfExists(f);
                    else
                        JsonConfigFile.save(regionPathsFor(f), doc, REGION_CODEC, false);
                }
            } catch (IOException ex) {
                LOGGER.error("Failed to rewrite groups in {}", f, ex);
            }
        }
    }

    /** 由 region 文件路径反推 JsonConfigFile.Paths（legacy 字段占位，region 无 legacy）。 */
    private static JsonConfigFile.Paths regionPathsFor(Path regionFile) {
        var dir = regionFile.getParent();
        String name = regionFile.getFileName().toString();
        return new JsonConfigFile.Paths(dir.resolve(name), dir.resolve(name + ".tmp"),
                dir.resolve(name + ".old"), dir.resolve("unused"), dir.resolve("unused"));
    }

    // ─── 种子级操作 (/sm4x 命令) ────────────────────────────────

    /** 有标记数据的种子快照（升序）；扫描 marks 目录。 */
    public synchronized long[] seedsSnapshot() {
        if (!Files.isDirectory(marksDir))
            return new long[0];
        try (Stream<Path> stream = Files.list(marksDir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> {
                        try {
                            // toHexString 的无符号表示 (负种子 > 2^63-1) 必须 parseUnsignedLong 回读
                            return (Long) Long.parseUnsignedLong(p.getFileName().toString(), 16);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sorted()
                    .toArray();
        } catch (IOException e) {
            LOGGER.error("Failed to list marks seeds", e);
            return new long[0];
        }
    }

    /** 某种子的统计；无数据返回 null。已缓存文档按内存统计（含未落盘变更）。 */
    public synchronized @Nullable SeedStats stats(long seed) {
        var seedDir = marksDir.resolve(Long.toHexString(seed));
        var cached = docs.get(seed);
        var mwIds = new TreeSet<String>();
        if (Files.isDirectory(seedDir))
            mwIds.addAll(listDimDirs(seedDir));
        if (cached != null)
            mwIds.addAll(cached.keySet());
        if (mwIds.isEmpty())
            return null;

        int structures = 0;
        var groups = new HashSet<String>();
        for (String dirName : mwIds) {
            String rawMwId = rawMwIdFor(seed, dirName);
            var dd = rawMwId != null ? cached.get(rawMwId) : null;
            if (dd != null) {
                structures += dd.recordCount();
                dd.accumulateStats(groups);
            } else {
                structures += countFromDisk(seedDir.resolve(dirName), groups);
            }
        }
        return new SeedStats(groups.size(), structures);
    }

    private int countFromDisk(Path dimDir, Set<String> groups) {
        int n = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dimDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.json"))
                    .sorted().toList();
        } catch (IOException e) {
            return 0;
        }
        for (Path f : files) {
            try {
                var doc = JsonConfigFile.readJson(f, REGION_CODEC);
                n += doc.totalMarks();
                for (var e : doc.marks().entrySet())
                    for (MarkEntry m : e.getValue())
                        if (!m.group().isEmpty())
                            groups.add(m.group());
            } catch (IOException e) {
                LOGGER.error("Skipping corrupt marks region {}", f, e);
            }
        }
        return n;
    }

    /**
     * 删除某种子全部标记：先刷脏（保证磁盘完整），再统计并删除目录。
     * 返回删除的记录数；无数据返回 0。
     */
    public synchronized int removeSeed(long seed) {
        flush(false);
        int n = 0;
        var seedDir = marksDir.resolve(Long.toHexString(seed));
        if (Files.isDirectory(seedDir)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(seedDir)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.json"))
                        .toList();
            } catch (IOException e) {
                LOGGER.error("Failed to walk marks dir {}", seedDir, e);
                files = List.of();
            }
            for (Path f : files) {
                try {
                    n += JsonConfigFile.readJson(f, REGION_CODEC).totalMarks();
                } catch (IOException e) {
                    LOGGER.error("Skipping corrupt marks region {}", f, e);
                }
            }
            deleteRecursively(seedDir);
        }
        docs.remove(seed);
        return n;
    }

    // ─── legacy 迁移 ────────────────────────────────────────────

    /**
     * 旧 structure_data.sm4x 快照一次性分片落盘（迁移专用；全新文件，
     * {@code rotate=false}）。调用时机 = activate 加载链发现 legacy 文件。
     */
    public synchronized void importSnapshot(StructureDataLegacy.Snapshot snap) {
        for (var seedEntry : snap.seeds().long2ObjectEntrySet()) {
            long seed = seedEntry.getLongKey();
            for (var dimEntry : seedEntry.getValue().dims().entrySet()) {
                String mwId = dimEntry.getKey();
                var regions = new LinkedHashMap<Long, LinkedHashMap<String, List<MarkEntry>>>();
                for (var typeEntry : dimEntry.getValue().types().entrySet()) {
                    String structureKey = StructureKeys.persistedKey(typeEntry.getKey());
                    for (StructureDataLegacy.LegacyMark m : typeEntry.getValue()) {
                        regions.computeIfAbsent(regionIdxOf(m.key()), k -> new LinkedHashMap<>())
                                .computeIfAbsent(structureKey, k -> new ArrayList<>())
                                .add(new MarkEntry((int) (m.key() >> 32), (int) m.key(),
                                        m.minDist(), m.group()));
                    }
                }
                for (var regionEntry : regions.entrySet()) {
                    try {
                        JsonConfigFile.save(regionPaths(seed, mwId, regionEntry.getKey()),
                                new RegionDoc(regionEntry.getValue()), REGION_CODEC, false);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write migrated region for seed {}", seed, e);
                    }
                }
            }
        }
        LOGGER.info("Imported legacy structure marks: {} seeds", snap.seeds().size());
    }

    // ─── 路径辅助 ───────────────────────────────────────────────

    private Path dimDir(long seed, String mwId) {
        return marksDir.resolve(Long.toHexString(seed)).resolve(dimDirName(mwId));
    }

    /** mwId → 目录名（域已验证 FS 安全；空串映射 default，防御性再消毒）。 */
    private static String dimDirName(String mwId) {
        String name = mwId.isEmpty() ? "default" : mwId;
        return name.replaceAll("[^A-Za-z0-9$.,_+-]", "_");
    }

    private List<String> listDimDirs(Path seedDir) {
        try (Stream<Path> stream = Files.list(seedDir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private JsonConfigFile.Paths regionPaths(long seed, String mwId, long regionIdx) {
        var dir = dimDir(seed, mwId);
        String name = "r." + (int) (regionIdx >> 32) + "." + (int) regionIdx + ".json";
        return new JsonConfigFile.Paths(dir.resolve(name), dir.resolve(name + ".tmp"),
                dir.resolve(name + ".old"), dir.resolve("unused"), dir.resolve("unused"));
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete {}", p, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to walk {}", dir, e);
        }
    }
}
