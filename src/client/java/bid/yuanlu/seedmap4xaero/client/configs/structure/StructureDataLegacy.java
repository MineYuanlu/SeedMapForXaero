package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * 旧版 {@code structure_data.sm4x} 二进制格式的冻结编解码器
 * （设置 + 按种子分节的标记，数据体版本 0/1）。
 * <p>
 * <b>只支持自动向上升级</b>：读出为中性快照 {@link Snapshot}，由
 * {@link MarksStore#importSnapshot} 分片落盘；本格式不再被写盘
 * ({@code write} 仅用于测试 fixture)。修改请勿扩展此格式——新数据走 JSON 分片。
 */
public final class StructureDataLegacy {

    private StructureDataLegacy() {
    }

    /** 旧文档的中性快照（与运行时结构解耦）。 */
    public record Snapshot(
            List<String> hiddenGroups,
            List<StructureData.UserGroup> userGroups,
            Long2ObjectOpenHashMap<LegacySeed> seeds) {
    }

    /** 一个种子的全部维度标记（mwId → 维度）。 */
    public record LegacySeed(Map<String, LegacyDim> dims) {
    }

    /** 单维度标记（typeId → 标记列表）。 */
    public record LegacyDim(Map<Integer, List<LegacyMark>> types) {
    }

    /** 一条标记记录。 */
    public record LegacyMark(long key, int minDist, String group) {
    }

    /** legacy 编解码器，配合 {@code Sm4xFile.readFrame} 使用（magic 信封由其包装）。 */
    public static final bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xCodec<Snapshot> LEGACY_CODEC =
            new bid.yuanlu.seedmap4xaero.client.configs.core.Sm4xCodec<>() {
                @Override
                public void write(Snapshot data, DataOutputStream out) throws IOException {
                    StructureDataLegacy.write(data, out);
                }

                @Override
                public Snapshot read(DataInputStream in) throws IOException {
                    return StructureDataLegacy.read(in);
                }
            };

    // ─── 读取 (frozen) ──────────────────────────────────────────

    private static Snapshot read(DataInputStream in) throws IOException {
        final var version = in.readInt();
        if (version != 0 && version != 1)
            throw new IOException("Unsupported legacy StructureData version: " + version);
        final var hiddenGroups = new ArrayList<String>();
        final var userGroups = new ArrayList<StructureData.UserGroup>();

        int hiddenCount = in.readInt();
        for (int i = 0; i < hiddenCount; i++)
            hiddenGroups.add(in.readUTF());

        if (version >= 1) {
            int groupCount = in.readInt();
            for (int i = 0; i < groupCount; i++)
                userGroups.add(new StructureData.UserGroup(in.readUTF(), in.readInt()));
        }

        final var seeds = new Long2ObjectOpenHashMap<LegacySeed>();
        int seedCount = in.readInt();
        for (int i = 0; i < seedCount; i++) {
            long seed = in.readLong();
            seeds.put(seed, readSeed(in));
        }
        return new Snapshot(hiddenGroups, userGroups, seeds);
    }

    private static LegacySeed readSeed(DataInputStream in) throws IOException {
        final int dimCount = in.readInt();
        final var dims = new LinkedHashMap<String, LegacyDim>();
        for (int i = 0; i < dimCount; i++) {
            String mwId = in.readUTF();
            dims.put(mwId, readDim(in));
        }
        return new LegacySeed(dims);
    }

    private static LegacyDim readDim(DataInputStream in) throws IOException {
        final int dimVersion = in.readInt();
        if (dimVersion != 0)
            throw new IOException("Unsupported legacy StructureData.DimData version: " + dimVersion);
        int typeCount = in.readInt();
        final var types = new LinkedHashMap<Integer, List<LegacyMark>>();
        for (int i = 0; i < typeCount; i++) {
            int typeId = in.readUnsignedByte();
            int entryCount = in.readInt();
            final var marks = new ArrayList<LegacyMark>(entryCount);
            for (int j = 0; j < entryCount; j++) {
                long key = in.readLong();
                int minDist = in.readInt();
                String group = in.readUTF();
                marks.add(new LegacyMark(key, minDist, group));
            }
            types.put(typeId, marks);
        }
        return new LegacyDim(types);
    }

    // ─── 写出 (frozen, 仅测试 fixture) ──────────────────────────

    private static void write(Snapshot snap, DataOutputStream out) throws IOException {
        out.writeInt(1);

        out.writeInt(snap.hiddenGroups().size());
        for (String g : snap.hiddenGroups())
            out.writeUTF(g);

        out.writeInt(snap.userGroups().size());
        for (StructureData.UserGroup ug : snap.userGroups()) {
            out.writeUTF(ug.name());
            out.writeInt(ug.color());
        }

        out.writeInt(snap.seeds().size());
        for (var seedEntry : snap.seeds().long2ObjectEntrySet()) {
            out.writeLong(seedEntry.getLongKey());
            var seed = seedEntry.getValue();
            out.writeInt(seed.dims().size());
            for (var dimEntry : seed.dims().entrySet()) {
                out.writeUTF(dimEntry.getKey());
                writeDim(out, dimEntry.getValue());
            }
        }
    }

    private static void writeDim(DataOutputStream out, LegacyDim dim) throws IOException {
        out.writeInt(0);
        out.writeInt(dim.types().size());
        for (var typeEntry : dim.types().entrySet()) {
            out.writeByte(typeEntry.getKey());
            var marks = typeEntry.getValue();
            out.writeInt(marks.size());
            for (LegacyMark mark : marks) {
                out.writeLong(mark.key());
                out.writeInt(mark.minDist());
                out.writeUTF(mark.group());
            }
        }
    }
}
