package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xCodec;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * structure_data 配置文档（{@code structure_data.sm4x}）的数据体：
 * 按种子分节的结构持久标记 + 组可见性。
 * <p>
 * 只负责数据与自身序列化；magic word 帧包装、原子写、损坏回退在
 * {@link bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xFile}。
 *
 * <pre>
 * hiddenGroups : [ String … ]          // 面板中关闭显示的组 (文件级, 与种子无关)
 * seeds : { seed → { mwId → DimData } } // 标记与种子强相关 (同一方块坐标换种子即另一结构)
 * DimData : { typeId → { key → StructureMark } }  // key = (blockX<<32)|blockZ, 结构为 2D 无 Y
 * </pre>
 */
public class StructureData {

    private final Long2ObjectOpenHashMap<SeedData> seeds = new Long2ObjectOpenHashMap<>();
    private final ArrayList<String> hiddenGroups = new ArrayList<>();

    final AtomicBoolean dirty = new AtomicBoolean(false);

    void makeDirty() {
        this.dirty.set(true);
    }

    public @Nullable SeedData getSeed(long seed) {
        synchronized (seeds) {
            return seeds.get(seed);
        }
    }

    @NotNull
    public SeedData getOrCreateSeed(long seed) {
        synchronized (seeds) {
            return seeds.computeIfAbsent(seed, k -> new SeedData(this));
        }
    }

    /** 有标记数据的种子快照（升序）；命令列表用，避免迭代中并发插入。 */
    public long[] seedsSnapshot() {
        synchronized (seeds) {
            long[] out = seeds.keySet().toLongArray();
            java.util.Arrays.sort(out);
            return out;
        }
    }

    /** 删除某种子的全部数据，返回删除的标记记录数（无该种子返回 0）。 */
    public int removeSeed(long seed) {
        synchronized (seeds) {
            var removed = seeds.remove(seed);
            if (removed == null)
                return 0;
            int n = 0;
            for (DimData dd : removed.dims.values())
                n += dd.recordCount();
            makeDirty();
            return n;
        }
    }

    /** 组在面板/地图上是否被隐藏。 */
    public boolean isGroupHidden(String group) {
        synchronized (hiddenGroups) {
            return hiddenGroups.contains(group);
        }
    }

    /** 设置组可见性; 默认组允许隐藏 (隐藏所有未分组结构)。 */
    public void setGroupHidden(String group, boolean hidden) {
        synchronized (hiddenGroups) {
            if (hidden) {
                if (hiddenGroups.contains(group))
                    return;
                hiddenGroups.add(group);
            } else {
                if (!hiddenGroups.remove(group))
                    return;
            }
            makeDirty();
        }
    }

    /** 一个种子的全部维度标记。 */
    public static final class SeedData {
        private final StructureData owner;
        private final ConcurrentHashMap<String, DimData> dims = new ConcurrentHashMap<>();

        SeedData(StructureData owner) {
            this.owner = owner;
        }

        public @Nullable DimData getDim(String mwId) {
            return dims.get(mwId);
        }

        @NotNull
        public DimData getOrCreateDim(String mwId) {
            return dims.computeIfAbsent(mwId, k -> new DimData(this));
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeInt(dims.size());
            for (var e : dims.entrySet()) {
                out.writeUTF(e.getKey());
                e.getValue().write(out);
            }
        }

        private static SeedData read(StructureData owner, DataInputStream in) throws IOException {
            final var sd = new SeedData(owner);
            final int dimCount = in.readInt();
            for (int i = 0; i < dimCount; i++) {
                String mwId = in.readUTF();
                sd.dims.put(mwId, DimData.read(sd, in));
            }
            return sd;
        }
    }

    /** 单维度标记表: typeId → (key → mark)。 */
    public static final class DimData {
        private final StructureData owner;
        private final Long2ObjectOpenHashMap<StructureMark>[] types;

        @SuppressWarnings("unchecked")
        DimData(SeedData ownerSeed) {
            this.owner = ownerSeed.owner;
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
                owner.makeDirty();
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
                        owner.makeDirty();
                    return;
                }
                var cur = m.get(key);
                if (cur != null && cur.group().equals(group))
                    return;
                m.put(key, new StructureMark(
                        cur == null ? StructureMark.NO_VISIT : cur.minDist(), group));
                owner.makeDirty();
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

        /** 标记记录总数（含访问/分组任意一种）。 */
        int recordCount() {
            int n = 0;
            synchronized (this) {
                for (var m : types)
                    if (m != null)
                        n += m.size();
            }
            return n;
        }

        /** 累加统计: 返回本维度记录数, 同时把用到的非默认组名收进 {@code groups}。 */
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

        private void write(DataOutputStream out) throws IOException {
            out.writeInt(0);
            synchronized (this) {
                int typeCount = 0;
                for (var m : types)
                    if (m != null && !m.isEmpty())
                        typeCount++;
                out.writeInt(typeCount);
                for (int id = 0; id < types.length; id++) {
                    var m = types[id];
                    if (m == null || m.isEmpty())
                        continue;
                    out.writeByte(id);
                    out.writeInt(m.size());
                    var eit = m.long2ObjectEntrySet().fastIterator();
                    while (eit.hasNext()) {
                        var e = eit.next();
                        out.writeLong(e.getLongKey());
                        var mark = e.getValue();
                        out.writeInt(mark.minDist());
                        out.writeUTF(mark.group());
                    }
                }
            }
        }

        private static DimData read(SeedData ownerSeed, DataInputStream in) throws IOException {
            final var version = in.readInt();
            if (version != 0)
                throw new IOException("Unsupported StructureData.DimData version: " + version);
            var dd = new DimData(ownerSeed);
            int typeCount = in.readInt();
            for (int i = 0; i < typeCount; i++) {
                int typeId = in.readUnsignedByte();
                if (typeId < 0 || typeId >= dd.types.length)
                    throw new IOException("Invalid structure type id: " + typeId);
                int entryCount = in.readInt();
                var m = dd.types[typeId] = new Long2ObjectOpenHashMap<>(entryCount);
                for (int j = 0; j < entryCount; j++) {
                    long key = in.readLong();
                    int minDist = in.readInt();
                    String group = in.readUTF();
                    m.put(key, new StructureMark(minDist, group));
                }
            }
            return dd;
        }
    }

    /** 某种子的数据量统计: 用到的组数（去重, 非默认组）+ 有记录的结构数。 */
    public record SeedStats(int groups, int structures) {
    }

    /** 某种子的数据量统计；无该种子返回 null。 */
    public @Nullable SeedStats stats(long seed) {
        final SeedData sd;
        synchronized (seeds) {
            sd = seeds.get(seed);
        }
        if (sd == null)
            return null;
        var groups = new java.util.HashSet<String>();
        int structures = 0;
        for (DimData dd : sd.dims.values())
            structures += dd.accumulateStats(groups);
        return new SeedStats(groups.size(), structures);
    }

    /** structure_data 文档的编解码器，配合 {@code Sm4xFile} 使用；版本号是内部细节。 */
    public static final Sm4xCodec<StructureData> CODEC = new Sm4xCodec<>() {
        @Override
        public void write(StructureData data, DataOutputStream out) throws IOException {
            data.write(out);
        }

        @Override
        public StructureData read(DataInputStream in) throws IOException {
            return StructureData.read(in);
        }
    };

    /** 写入数据体（不含 magic 信封）。 */
    private synchronized void write(DataOutputStream out) throws IOException {
        out.writeInt(0);

        synchronized (hiddenGroups) {
            out.writeInt(hiddenGroups.size());
            for (String g : hiddenGroups)
                out.writeUTF(g);
        }

        synchronized (seeds) {
            out.writeInt(seeds.size());
            var it = seeds.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var e = it.next();
                out.writeLong(e.getLongKey());
                e.getValue().write(out);
            }
        }
    }

    private static StructureData read(DataInputStream in) throws IOException {
        final var version = in.readInt();
        if (version != 0)
            throw new IOException("Unsupported StructureData version: " + version);
        final var data = new StructureData();

        int hiddenCount = in.readInt();
        synchronized (data.hiddenGroups) {
            for (int i = 0; i < hiddenCount; i++)
                data.hiddenGroups.add(in.readUTF());
        }

        int seedCount = in.readInt();
        synchronized (data.seeds) {
            for (int i = 0; i < seedCount; i++) {
                long seed = in.readLong();
                data.seeds.put(seed, SeedData.read(data, in));
            }
        }
        return data;
    }
}
