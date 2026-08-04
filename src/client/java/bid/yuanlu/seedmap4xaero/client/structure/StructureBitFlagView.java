package bid.yuanlu.seedmap4xaero.client.structure;

import java.io.DataOutput;
import java.io.IOException;

public interface StructureBitFlagView {
    /** 检测一个structure是否被整体禁用 (bit0) */
    boolean isStructureSet(int structureId);

    /**
     * 检测一个variant是否被禁用 (只判变种位, 不含整体位)。
     * 可见性 = !isStructureSet(id) && !isVariantSet(id, variant), 由调用方组合。
     */
    boolean isVariantSet(int structureId, int variant);

    /** 写入到 DataOutput。 */
    void write(DataOutput out) throws IOException;
}
