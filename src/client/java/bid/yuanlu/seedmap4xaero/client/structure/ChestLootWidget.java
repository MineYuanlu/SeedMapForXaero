package bid.yuanlu.seedmap4xaero.client.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm.ChestLoot;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm.LootItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 结构战利品悬浮框: 移植自 SeedMapper ChestLootWidget。
 * <p>
 * 按箱子翻页 (chestIndex), 3×9 物品网格 + 物品 tooltip + 标题 extraInfo
 * (pieceName / chestPos / lootTable / lootSeed)。物品/附魔 id 由 C 端
 * {@link Xsm#itemName}/{@link Xsm#enchantmentName} 映射到 MC 注册表。
 */
public class ChestLootWidget {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/ChestLootWidget");

    private static final Identifier CHEST_CONTAINER = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/gui/chest_container.png");
    private static final int CHEST_CONTAINER_WIDTH = 176;
    private static final int CHEST_CONTAINER_HEIGHT = 78;

    private static final Identifier BUTTON_TEXTURE = Identifier.fromNamespaceAndPath(
            "fabric", "textures/gui/creative_buttons.png");
    private static final int BUTTON_X_OFFSET = 149;
    private static final int BUTTON_Y_OFFSET = 4;
    private static final int BUTTON_WIDTH = 10;
    private static final int BUTTON_HEIGHT = 12;

    private static final int ITEM_SLOT_SIZE = 18;

    /** 平铺模式: 固定 2 列, 容器间距。 */
    public static final int TILE_COLS = 2;
    public static final int TILE_GAP = 4;

    /** 悬浮查看 (未固定) 时的周围缓冲像素, 鼠标滑出此范围则关闭。 */
    public static final int HOVER_BUFFER = 8;

    /** 单个容器的像素宽/高 (平铺定位与命中检测复用)。 */
    public static final int CONTAINER_WIDTH = CHEST_CONTAINER_WIDTH;
    public static final int CONTAINER_HEIGHT = CHEST_CONTAINER_HEIGHT;

    private final int x;
    private final int y;
    private final boolean tiled;

    private int chestIndex = 0;
    private final StructureType structureType;
    private final List<ChestLoot> chestDataList;

    /** 标题 extraInfo tooltip (每个箱子一条)。 */
    private final List<List<ClientTooltipComponent>> extraChestInfo = new ArrayList<>();
    private List<ClientTooltipComponent> pendingItemTooltip = null;
    private int pendingTooltipX = 0;
    private int pendingTooltipY = 0;

    public ChestLootWidget(int x, int y, StructureType structureType, List<ChestLoot> chestDataList, boolean tiled) {
        this.x = x;
        this.y = y;
        this.tiled = tiled;
        this.structureType = structureType;
        this.chestDataList = chestDataList;

        for (ChestLoot chestData : this.chestDataList) {
            List<ClientTooltipComponent> tooltips = new ArrayList<>();
            Component pieceName = Component.literal(chestData.pieceName() != null
                    ? chestData.pieceName() : "unknown_piece");
            tooltips.add(ClientTooltipComponent.create(Component.translatable(
                    "xsm.chestLoot.extraInfo.pieceName", pieceName).getVisualOrderText()));
            Component chestPos = Component.literal(
                    "x: %d, z: %d".formatted(chestData.chestX(), chestData.chestZ()));
            tooltips.add(ClientTooltipComponent.create(Component.translatable(
                    "xsm.chestLoot.extraInfo.chestPos", chestPos).getVisualOrderText()));
            Component lootTable = Component.literal(
                    chestData.lootTable() != null ? chestData.lootTable() : "unknown_loot_table");
            tooltips.add(ClientTooltipComponent.create(Component.translatable(
                    "xsm.chestLoot.extraInfo.lootTable", lootTable).getVisualOrderText()));
            Component lootSeed = Component.literal(Long.toString(chestData.lootSeed()));
            tooltips.add(ClientTooltipComponent.create(Component.translatable(
                    "xsm.chestLoot.extraInfo.lootSeed", lootSeed).getVisualOrderText()));
            this.extraChestInfo.add(tooltips);
        }
    }

    public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, Font font) {
        this.pendingItemTooltip = null;
        if (this.tiled) {
            renderTiled(guiGraphicsExtractor, mouseX, mouseY, font);
        } else {
            renderSingle(guiGraphicsExtractor, mouseX, mouseY, font);
        }
    }

    /** 平铺模式: 所有容器同时显示, 无翻页箭头。 */
    private void renderTiled(GuiGraphicsExtractor g, int mouseX, int mouseY, Font font) {
        boolean tooltipRendered = false;
        for (int i = 0; i < this.chestDataList.size(); i++) {
            int tileX = this.x + (i % TILE_COLS) * (CONTAINER_WIDTH + TILE_GAP);
            int tileY = this.y + (i / TILE_COLS) * (CONTAINER_HEIGHT + TILE_GAP);
            tooltipRendered |= renderContainer(g, mouseX, mouseY, font, this.chestDataList.get(i), i, tileX, tileY);
        }
    }

    /** 单容器模式: 当前箱子的容器 + 翻页箭头。 */
    private void renderSingle(GuiGraphicsExtractor g, int mouseX, int mouseY, Font font) {
        renderContainer(g, mouseX, mouseY, font,
                this.chestDataList.get(this.chestIndex), this.chestIndex, this.x, this.y);

        // 翻页按钮 (仅单容器模式)
        g.blit(RenderPipelines.GUI_TEXTURED, BUTTON_TEXTURE,
                this.x + BUTTON_X_OFFSET, this.y + BUTTON_Y_OFFSET,
                0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, 256, 256);
        g.blit(RenderPipelines.GUI_TEXTURED, BUTTON_TEXTURE,
                this.x + BUTTON_X_OFFSET + BUTTON_WIDTH, this.y + BUTTON_Y_OFFSET,
                BUTTON_WIDTH, 0, BUTTON_WIDTH, BUTTON_HEIGHT, 256, 256);
    }

    /**
     * 渲染一个箱子容器 (背景 + 标题 + 3×9 物品网格 + 物品 tooltip)。
     * 返回是否渲染了物品 tooltip (用于平铺模式跨格只渲染首个)。
     */
    private boolean renderContainer(GuiGraphicsExtractor g, int mouseX, int mouseY, Font font,
            ChestLoot chestData, int index, int boxX, int boxY) {
        // 确保地图/图标不透过箱子贴图的透明像素透出来
        g.fill(boxX, boxY, boxX + CONTAINER_WIDTH, boxY + CONTAINER_HEIGHT, 0xFF000000);
        g.blit(RenderPipelines.GUI_TEXTURED, CHEST_CONTAINER,
                boxX, boxY, 0, 0, CONTAINER_WIDTH, CONTAINER_HEIGHT,
                CONTAINER_WIDTH, CONTAINER_HEIGHT);

        Component title = Component.translatable("xsm.chestLoot.title",
                Component.translatable(this.structureType.translationKey()),
                index + 1, this.chestDataList.size());

        int minX = boxX + 8;
        int minY = boxY + 6;
        g.text(font, title, minX, minY, -1);

        int titleWidth = font.width(title.getVisualOrderText());
        if (mouseX >= minX && mouseX <= minX + titleWidth
                && mouseY >= minY && mouseY <= minY + font.lineHeight) {
            List<ClientTooltipComponent> tooltips = this.extraChestInfo.get(index);
            g.tooltip(font, tooltips,
                    minX - 4 - 12, boxY - tooltips.size() * font.lineHeight - 8 + 12,
                    DefaultTooltipPositioner.INSTANCE, null);
        }

        minY += 12;
        boolean tooltipRendered = false;
        for (int row = 0; row < 3; row++) {
            int y = minY + row * ITEM_SLOT_SIZE;
            for (int column = 0; column < 9; column++) {
                ItemStack item = this.getItem(chestData, row * 9 + column);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                int x = minX + column * ITEM_SLOT_SIZE;
                g.item(item, x, y);
                g.itemDecorations(font, item, x, y);
                if (!tooltipRendered && mouseX >= x && mouseX <= x + ITEM_SLOT_SIZE
                        && mouseY >= y && mouseY <= y + ITEM_SLOT_SIZE) {
                    Minecraft minecraft = Minecraft.getInstance();
                    var tooltipLines = item.getTooltipLines(
                            net.minecraft.world.item.Item.TooltipContext.of(minecraft.level),
                            minecraft.player,
                            minecraft.options.advancedItemTooltips
                                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL);
                    List<ClientTooltipComponent> tooltip = tooltipLines.stream()
                            .map(line -> ClientTooltipComponent.create(line.getVisualOrderText()))
                            .toList();
                    this.pendingItemTooltip = tooltip;
                    this.pendingTooltipX = mouseX;
                    this.pendingTooltipY = mouseY;
                    tooltipRendered = true;
                }
            }
        }
        return tooltipRendered;
    }

    /** 按容器槽位取物品: 战利品按生成顺序放入 27 格容器。 */
    private @Nullable ItemStack getItem(ChestLoot chestData, int slot) {
        List<LootItem> items = chestData.items();
        if (slot >= items.size()) {
            return null;
        }
        return buildItemStack(items.get(slot));
    }

    /** globalItemId → MC Item (缓存)。 */
    private static final Map<Integer, net.minecraft.world.item.Item> ITEM_CACHE = new HashMap<>();
    /** 附魔名称 → Enchantment 注册表 key (缓存)。 */
    private static final Map<String, ResourceKey<Enchantment>> ENCH_CACHE = new HashMap<>();

    private static @Nullable net.minecraft.world.item.Item buildItem(Integer globalItemId) {
        String name = Xsm.itemName(globalItemId);
        if (name == null) {
            return null;
        }
        try {
            var id = Identifier.parse(name);
            var optional = BuiltInRegistries.ITEM.get(id);
            return optional.map(Holder::value).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 C 端战利品条目构建为 MC {@link ItemStack} (含附魔)。
     * 映射失败返回 null (调用方跳过)。
     */
    private static @Nullable ItemStack buildItemStack(LootItem loot) {
        net.minecraft.world.item.Item item = ITEM_CACHE.computeIfAbsent(loot.globalItemId(),
                ChestLootWidget::buildItem);
        if (item == null || item == Items.AIR) {
            return null;
        }
        ItemStack stack = new ItemStack(item, loot.count());
        if (item == Items.SUSPICIOUS_STEW) {
            var lore = Component.translatable("xsm.chestLoot.stewEffect",
                    Component.literal("Unknown"), "?");
            stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        }
        if (!loot.enchantments().isEmpty()) {
            var lookup = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    : null;
            if (lookup == null) {
                return stack;
            }
            for (int[] ench : loot.enchantments()) {
                String name = Xsm.enchantmentName(ench[0]);
                if (name == null) {
                    continue;
                }
                ResourceKey<Enchantment> key = ENCH_CACHE.computeIfAbsent(name, n -> {
                    Identifier id = Identifier.fromNamespaceAndPath("minecraft", n);
                    if (lookup.get(id).isPresent()) {
                        return ResourceKey.create(Registries.ENCHANTMENT, id);
                    }
                    return null;
                });
                if (key == null) {
                    continue;
                }
                Optional<Holder.Reference<Enchantment>> ref = lookup.get(key);
                if (ref.isPresent()) {
                    stack.enchant(ref.get(), ench[1]);
                }
            }
        }
        return stack;
    }

    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
        if (this.tiled) {
            return false; // 平铺无翻页按钮
        }
        int button = mouseButtonEvent.button();
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        double mouseX = mouseButtonEvent.x();
        double mouseY = mouseButtonEvent.y();
        int minX = this.x + BUTTON_X_OFFSET;
        int minY = this.y + BUTTON_Y_OFFSET;
        int maxX = minX + BUTTON_WIDTH;
        int maxY = minY + BUTTON_HEIGHT;
        if (mouseX >= minX && mouseX <= maxX && mouseY >= minY && mouseY <= maxY) {
            this.chestIndex = Math.max(0, this.chestIndex - 1);
            return true;
        }
        minX = minX + BUTTON_WIDTH;
        maxX = maxX + BUTTON_WIDTH;
        if (mouseX >= minX && mouseX <= maxX && mouseY >= minY && mouseY <= maxY) {
            this.chestIndex = Math.min(this.chestDataList.size() - 1, this.chestIndex + 1);
            return true;
        }
        return false;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX <= this.x + getTotalWidth()
                && mouseY >= this.y && mouseY <= this.y + getTotalHeight();
    }

    /** 含缓冲圈的命中检测 (固定态滑出关闭用)。 */
    public boolean isMouseOverWithBuffer(double mouseX, double mouseY, int buffer) {
        return mouseX >= this.x - buffer && mouseX <= this.x + getTotalWidth() + buffer
                && mouseY >= this.y - buffer && mouseY <= this.y + getTotalHeight() + buffer;
    }

    /** 总宽: 单容器 = 176; 平铺 = 2 列 + 间隙。 */
    public int getTotalWidth() {
        return tiled ? totalWidth(this.chestDataList.size()) : CONTAINER_WIDTH;
    }

    /** 总高: 单容器 = 78; 平铺 = 行数 * (78+间隙) - 间隙。 */
    public int getTotalHeight() {
        return tiled ? totalHeight(this.chestDataList.size()) : CONTAINER_HEIGHT;
    }

    /** 平铺模式 (2 列) 下 N 个容器的总宽。 */
    public static int totalWidth(int chestCount) {
        return TILE_COLS * CONTAINER_WIDTH + (TILE_COLS - 1) * TILE_GAP;
    }

    /** 平铺模式 (2 列) 下 N 个容器的总高。 */
    public static int totalHeight(int chestCount) {
        int rows = (chestCount + TILE_COLS - 1) / TILE_COLS;
        return rows * CONTAINER_HEIGHT + (rows - 1) * TILE_GAP;
    }

    public List<ClientTooltipComponent> getPendingItemTooltip() {
        return this.pendingItemTooltip;
    }

    public int getPendingTooltipX() {
        return this.pendingTooltipX;
    }

    public int getPendingTooltipY() {
        return this.pendingTooltipY;
    }

    public int getChestIndex() {
        return this.chestIndex;
    }

    public List<ChestLoot> getChestDataList() {
        return this.chestDataList;
    }
}
