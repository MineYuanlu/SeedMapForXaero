package bid.yuanlu.seedmap4xaero.client.structure;

import java.io.InputStream;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * 数据包自定义结构的运行时图标 sprite sheet (M3)。
 * <p>
 * 图标来源 = {@code c:worldgen/structure_icons.json} 的物品 id → 客户端资源
 * {@code assets/<ns>/textures/{item,block}/<path>.png} (16×16); 找不到回退
 * 通用地标图标 (slot 0)。产出两张动态纹理 (地图 20px/格 + 面板 16px/格),
 * 经 {@link DynamicTexture} 注册到 TextureManager, 与原版 structures.png
 * 同款采样方式。
 * <p>
 * 仅渲染线程调用 {@link #ensureLoaded()}; 注入表变化 (引用比较) 时惰性重建。
 * 物品纹理读取失败不致命 — 回退 slot 0。
 */
public final class CustomStructureIcons {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/CustomStructureIcons");

    /** 地图叠加用 (20px/格, 与 structures.png 同格宽)。 */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/icons/custom_structures");
    /** 设置面板用 (16px/格, 无描边, 与 structures_plain.png 同格宽)。 */
    public static final Identifier PLAIN_TEXTURE = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/icons/custom_structures_plain");

    public static final int CELL = 20;
    public static final int PLAIN_CELL = 16;

    /** 上次构建对应的注入表 (引用比较检测变化)。 */
    private static volatile List<CustomStructureType> builtFor;
    /** 地图 sheet 总宽 (px); -1 = 未构建。 */
    private static volatile int sheetWidth = -1;
    /** 面板 sheet 总宽 (px); -1 = 未构建。 */
    private static volatile int plainSheetWidth = -1;

    private CustomStructureIcons() {
    }

    /** 地图 sheet 总宽; 未构建 (无自定义结构) 返回 -1, 调用方应回退原版路径。 */
    public static int sheetWidth() {
        return sheetWidth;
    }

    public static int plainSheetWidth() {
        return plainSheetWidth;
    }

    /**
     * 渲染线程惰性构建/重建。无自定义结构时为空操作 (宽度保持 -1)。
     */
    public static void ensureLoaded() {
        final List<CustomStructureType> current = StructureTypes.customTypes();
        if (current.isEmpty()) {
            if (builtFor != null)
                reset();
            return;
        }
        if (current == builtFor)
            return; // 未变化
        rebuild(current);
    }

    private static void reset() {
        builtFor = null;
        sheetWidth = -1;
        plainSheetWidth = -1;
    }

    private static void rebuild(List<CustomStructureType> types) {
        // slot 0 = 通用回退图标; 之后每个可加载图标的类型占一格
        final int slots = 1 + countIconTypes(types);
        final NativeImage sheet = new NativeImage(NativeImage.Format.RGBA, CELL * slots, CELL, false);
        final NativeImage plain = new NativeImage(NativeImage.Format.RGBA, PLAIN_CELL * slots, PLAIN_CELL, false);
        drawFallbackIcon(sheet, 0, CELL);
        drawFallbackIcon(plain, 0, PLAIN_CELL);

        int slot = 1;
        for (CustomStructureType type : types) {
            if (type.isHidden())
                continue;
            final var item = type.iconItem();
            if (item == null)
                continue;
            final NativeImage img = loadItemTexture(item);
            if (img == null) {
                LOGGER.debug("no item texture for {} ({}), using fallback icon", type.key(), item);
                continue;
            }
            try {
                blitInto(sheet, slot, CELL, 2, img);
                blitInto(plain, slot, PLAIN_CELL, 0, img);
            } finally {
                img.close();
            }
            type.setIconSlot(slot);
            slot++;
        }

        register(TEXTURE, sheet);
        register(PLAIN_TEXTURE, plain);
        builtFor = types;
        sheetWidth = CELL * slots;
        plainSheetWidth = PLAIN_CELL * slots;
        LOGGER.debug("custom structure icons rebuilt: {} slots", slots);
    }

    private static int countIconTypes(List<CustomStructureType> types) {
        int n = 0;
        for (CustomStructureType t : types)
            if (!t.isHidden() && t.iconItem() != null)
                n++;
        return n;
    }

    private static void register(Identifier id, NativeImage image) {
        try {
            final DynamicTexture texture = new DynamicTexture(() -> "xsm/" + id.getPath(), image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        } catch (Throwable t) {
            LOGGER.warn("failed to register custom structure texture {}", id, t);
            image.close();
        }
    }

    /** 物品/方块贴图: assets/<ns>/textures/item|block/<path>.png (缺 mcmeta 动画取首帧)。 */
    private static @Nullable NativeImage loadItemTexture(Identifier item) {
        final String[] candidates = {
                "textures/item/" + item.getPath() + ".png",
                "textures/block/" + item.getPath() + ".png",
        };
        for (String path : candidates) {
            final Identifier res = Identifier.fromNamespaceAndPath(item.getNamespace(), path);
            try {
                final var rm = Minecraft.getInstance().getResourceManager();
                final var resource = rm.getResource(res);
                if (resource.isEmpty())
                    continue;
                try (InputStream in = resource.get().open()) {
                    return NativeImage.read(in);
                }
            } catch (Exception e) {
                LOGGER.debug("failed to load texture {}: {}", res, e.toString());
            }
        }
        return null;
    }

    /** 把 16×16 (或更大, 就近缩小) 的物品贴图拷进 sheet 的 slot 格内。 */
    private static void blitInto(NativeImage sheet, int slot, int cell, int inset,
            NativeImage src) {
        final int dstX = slot * cell + inset;
        final int dstY = inset;
        final int dstW = cell - inset * 2;
        for (int y = 0; y < dstW; y++) {
            for (int x = 0; x < dstW; x++) {
                final int sx = src.getWidth() > dstW ? x * src.getWidth() / dstW : x;
                final int sy = src.getHeight() > dstW ? y * src.getHeight() / dstW : y;
                if (sx >= src.getWidth() || sy >= src.getHeight())
                    continue;
                sheet.setPixel(dstX + x, dstY + y, src.getPixel(sx, sy));
            }
        }
    }

    /** 通用回退图标: 琥珀色菱形 + 深色描边 (区分于任何原版结构图标)。ARGB 像素。 */
    private static void drawFallbackIcon(NativeImage img, int slot, int cell) {
        final int cx = slot * cell + cell / 2;
        final int cy = cell / 2;
        final int r = cell / 2 - 2;
        final int fill = 0xFFFFB83F;   // 琥珀
        final int border = 0xFF3F2000; // 深棕
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                final int d = Math.abs(x) + Math.abs(y);
                if (d > r)
                    continue;
                img.setPixel(cx + x, cy + y, d >= r - 1 ? border : fill);
            }
        }
    }

    /** 当前注册的地图 sheet 纹理 (渲染 blit 用); 未构建返回 null。 */
    public static @Nullable AbstractTexture texture() {
        if (sheetWidth < 0)
            return null;
        return Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
    }

    /** 当前注册的面板 sheet 纹理; 未构建返回 null。 */
    public static @Nullable AbstractTexture plainTexture() {
        if (plainSheetWidth < 0)
            return null;
        return Minecraft.getInstance().getTextureManager().getTexture(PLAIN_TEXTURE);
    }
}
