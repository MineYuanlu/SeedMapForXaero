package bid.yuanlu.seedmap4xaero.client.structure;

import bid.yuanlu.seedmap4xaero.client.cache.StrongholdCache.StrongholdPos;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache.StructurePos;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;

/**
 * 结构图标的共享枚举 + 屏幕坐标几何。
 * <p>
 * 渲染 (StructureOverlayMixin) 与右键命中 (StructureClickMixin) 必须使用
 * 完全一致的过滤 (二级 disabled flags + 要塞 gate) 与坐标变换, 否则会出现
 * "能看到但点不到 / 能点到但没图标" 的漂移。本类统一两者。
 */
public final class StructureIcons {

    /** 地图屏幕坐标变换: 由 GuiMap 的 shadow 字段 (cameraX/cameraZ/scale/screenScale) 派生。 */
    public record Transform(double cameraX, double cameraZ, double scale, double invScale,
            double guiW, double guiH) {
        /** 结构锚点 -> 合成屏幕 x (与原始 blit 语义一致)。 */
        public double guiX(double blockX) {
            return (blockX - cameraX) * scale * invScale + guiW / 2.0;
        }

        /** 结构锚点 -> 合成屏幕 y。 */
        public double guiZ(double blockZ) {
            return (blockZ - cameraZ) * scale * invScale + guiH / 2.0;
        }
    }

    /**
     * 图标消费端 (原始类型参数, 每图标零分配)。
     * <p>
     * 千万不用对象封装 (如 record Icon): 热循环 (渲染每帧/几千图标) 里
     * 每图标一次分配会增加 young gen 压力; 原始类型经寄存器传递无任何开销。
     */
    @FunctionalInterface
    public interface VisibleIconSink {
        void accept(StructureType type, int variant, int blockX, int blockZ,
                double guiX, double guiZ);
    }

    private StructureIcons() {
    }

    /**
     * 当前是否可绘制结构图标 (功能开关 + 活跃世界配置 + 至少一种结构启用)。
     * 不检查 mapProcessor, 由调用方 (mixin 内 shadow) 自行守卫。
     */
    public static boolean enabled() {
        if (!ServerConfig.isStructureEnabled())
            return false;
        final var wc = ServerConfig.getActiveWorldConfig();
        if (wc == null)
            return false;
        return !wc.getStructureTypeSet().isEmpty();
    }

    /**
     * 枚举当前可见的结构图标 (REGIONS + 要塞, 应用 disabled flags)。
     *
     * @return false 表示当前不可绘制 (未回调任何图标)
     */
    public static boolean forEachVisible(VisibleIconSink out, Transform t) {
        if (!enabled())
            return false;
        final var wc = ServerConfig.getActiveWorldConfig();
        final StructureBitFlagView flags = wc.getDisabledStructures();

        for (var entry : StructureCache.REGIONS.entrySet()) {
            StructureType type = entry.getKey();
            final int typeId = type.id;
            for (StructurePos rp : entry.getValue()) {
                if (!rp.loaded())
                    continue;
                if (flags.isStructureSet(typeId) || flags.isVariantSet(typeId, rp.getVariant()))
                    continue;
                out.accept(type, rp.getVariant(), rp.blockX(), rp.blockZ(),
                        t.guiX(rp.blockX()), t.guiZ(rp.blockZ()));
            }
        }

        if (!flags.isStructureSet(StructureType.STRONGHOLD.id)) {
            final var strongholds = StructureCache.strongholds();
            if (strongholds != null) {
                for (StrongholdPos sh : strongholds) {
                    if (sh == null)
                        continue;
                    out.accept(StructureType.STRONGHOLD, sh.getVariant(), sh.blockX(),
                            sh.blockZ(), t.guiX(sh.blockX()), t.guiZ(sh.blockZ()));
                }
            }
        }
        return true;
    }
}
