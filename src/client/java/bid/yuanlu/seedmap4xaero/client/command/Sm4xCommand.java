package bid.yuanlu.seedmap4xaero.client.command;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;

import bid.yuanlu.seedmap4xaero.client.configs.basic.ConfigData;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureData;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /sm4x} 客户端命令：种子历史与结构标记数据的查看/清理（二阶段）。
 * <p>
 * 全部输出走聊天栏；种子以绿色 {@code [seed]} 呈现，点击复制到剪贴板；
 * 列表分页，页脚 ◀▶ 可点击翻页（{@code ClickEvent.RunCommand}）。
 */
public final class Sm4xCommand {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Sm4xCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(cmdSm4x()));
    }

    /** {@code /sm4x} 根节点: 无参显示帮助。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdSm4x() {
        return ClientCommands.literal("sm4x")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Component.translatable("xsm.command.help"));
                return 1;
            })
            .then(cmdHistory());
    }

    /** {@code /sm4x history …}: 种子历史 / 结构数据。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdHistory() {
        return ClientCommands.literal("history")
            .then(cmdHistorySeed())
            .then(cmdHistoryStructure());
    }

    /** {@code /sm4x history seed list [page=1]}: 种子历史 (MRU)。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdHistorySeed() {
        return ClientCommands.literal("seed")
            .then(listNode(Sm4xCommand::listSeedHistory));
    }

    /** {@code /sm4x history structure list [page=1] | remove <seed>}。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdHistoryStructure() {
        return ClientCommands.literal("structure")
            .then(listNode(Sm4xCommand::listStructureData))
            .then(ClientCommands.literal("remove")
                .then(ClientCommands.argument("seed", LongArgumentType.longArg())
                    .executes(ctx -> {
                        removeStructureData(ctx.getSource(), LongArgumentType.getLong(ctx, "seed"));
                        return 1;
                    })));
    }

    /** {@code list [page=1]} 子树; handler 首参 = 页码。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> listNode(
            java.util.function.ObjIntConsumer<FabricClientCommandSource> handler) {
        return ClientCommands.literal("list")
            .executes(ctx -> {
                handler.accept(ctx.getSource(), 1);
                return 1;
            })
            .then(ClientCommands.argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    handler.accept(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page"));
                    return 1;
                }));
    }

    // ─── history seed list ───────────────────────────────────

    private static void listSeedHistory(FabricClientCommandSource src, int page) {
        var cfg = ServerConfig.getActiveConfig();
        List<ConfigData.SeedHistoryEntry> all = cfg != null ? cfg.getSeedHistory() : List.of();
        if (all.isEmpty()) {
            src.sendFeedback(Component.translatable("xsm.command.empty"));
            return;
        }
        var paged = Page.slice(all, page);
        var msg = Component.empty();
        msg.append(Component.translatable("xsm.command.history.seed.title"));
        for (var e : paged.items()) {
            msg.append(Component.literal("\n"));
            msg.append(seedComponent(e.seed()));
            msg.append(Component.translatable("xsm.command.seed.last_used", formatDate(e.lastUsed()))
                    .withStyle(ChatFormatting.GRAY));
        }
        msg.append(Component.literal("\n"));
        msg.append(pageFooter("history seed list", paged.page(), paged.total()));
        src.sendFeedback(msg);
    }

    // ─── history structure list ──────────────────────────────

    private static void listStructureData(FabricClientCommandSource src, int page) {
        var data = StructureDataConfig.getActiveData();
        record Row(long seed, StructureData.SeedStats stats) {
        }
        List<Row> rows = new ArrayList<>();
        if (data != null) {
            for (long seed : data.seedsSnapshot()) {
                var st = data.stats(seed);
                if (st != null && st.structures() > 0)
                    rows.add(new Row(seed, st));
            }
        }
        if (rows.isEmpty()) {
            src.sendFeedback(Component.translatable("xsm.command.empty"));
            return;
        }
        var paged = Page.slice(rows, page);
        var msg = Component.empty();
        msg.append(Component.translatable("xsm.command.history.structure.title"));
        for (var row : paged.items()) {
            msg.append(Component.literal("\n"));
            msg.append(seedComponent(row.seed()));
            msg.append(Component.translatable("xsm.command.structure.stats",
                    row.stats().groups(), row.stats().structures()).withStyle(ChatFormatting.GRAY));
        }
        msg.append(Component.literal("\n"));
        msg.append(pageFooter("history structure list", paged.page(), paged.total()));
        src.sendFeedback(msg);
    }

    // ─── history structure remove <seed> ─────────────────────

    private static void removeStructureData(FabricClientCommandSource src, long seed) {
        int n = StructureDataConfig.removeSeedForCommand(seed);
        if (n < 0) {
            src.sendError(Component.translatable("xsm.command.remove.active", seed));
        } else if (n == 0) {
            src.sendError(Component.translatable("xsm.command.remove.not_found", seed));
        } else {
            src.sendFeedback(Component.translatable("xsm.command.remove.success", seedComponent(seed), n));
        }
    }

    // ─── 输出组件助手 ─────────────────────────────────────────

    /** 绿色可点击复制的 {@code [seed]}。 */
    private static MutableComponent seedComponent(long seed) {
        String s = String.valueOf(seed);
        return Component.literal("[" + s + "]").withStyle(style -> style
            .withColor(ChatFormatting.GREEN)
            .withClickEvent(new ClickEvent.CopyToClipboard(s))
            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("xsm.command.seed.copy_hint"))));
    }

    /** 「第 x/y 页」+ 可点击 ◀▶（命令格式: {@code /sm4x <subcommand> <page>}）。 */
    private static MutableComponent pageFooter(String subcommand, int page, int total) {
        var footer = Component.empty();
        if (page > 1)
            footer.append(pageNav("xsm.command.prev_page", "/sm4x " + subcommand + " " + (page - 1)));
        footer.append(Component.translatable("xsm.command.page", page, total).withStyle(ChatFormatting.GRAY));
        if (page < total)
            footer.append(pageNav("xsm.command.next_page", "/sm4x " + subcommand + " " + (page + 1)));
        return footer;
    }

    private static MutableComponent pageNav(String key, String command) {
        return Component.translatable(key).withStyle(style -> style
            .withColor(ChatFormatting.GREEN)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    private static String formatDate(String iso) {
        try {
            return TS_FMT.format(LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return iso;
        }
    }
}
