// Stonecutter controller（根项目专用，仅控制多版本参数；版本化构建脚本见 build.gradle）。
// 注意：settings.gradle 里 kotlinController = true → 本文件用 Kotlin DSL；
// 版本化脚本 centralScript = "build.gradle" 仍是 Groovy。

stonecutter active "26.1.2"

stonecutter parameters {
    // ── 机械改名（26.x ↔ ≤1.21.11 API 线差异）─────────────────────────────────
    // 仅对 legacy 线（<26）替换；依据 mc/1.21.11 分支 (cc7c3da) 的改名清单。
    //
    // ⚠ stonecutter replacements 是双向的：条件为真的节点应用正向 from→to，
    //   条件为假的节点应用反向 reverse→reverseValue。两个方向都必须安全：
    //   - string() 的反向自动为 to→from，故 to 串必须在 modern 源码零出现
    //     （已全库 grep 验证，2026-11；改这里前先重查两侧）。
    //   - regex() 的反向必须显式给出：统一用永不匹配的 "\\b\\B" 实现单向替换。
    //   - 词边界（\b）用于杜绝子串误伤（如 GuiGraphics ⊂ GuiGraphicsExtractor、
    //     drawString ⊂ drawStringWithBackground）。
    //   - ChestLootWidget 的方法声明 extractRenderState↔render 无法用替换安全
    //     表达（反向会命中 modern 的 void render(GuiGraphicsExtractor…)），
    //     用源码内联条件注释处理。
    replacements {
        string(current.parsed < "26") {
            // Xaero 挂钩方法名：26.x extractRenderState ↔ 1.40.4 render（mixin 目标串）
            replace("method = \"extractRenderState\"", "method = \"render\"")

            // EditBox 渲染入口（SeedMapPanel 搜索框 ×3）
            replace("extractRenderState(g, mx, my, 0)", "render(g, mx, my, 0)")

            // Xaero loot widget 渲染入口（StructureOverlayMixin）
            replace("widget.extractRenderState(guiGraphics, scaledMouseX, scaledMouseY, mc.font)",
                    "widget.render(guiGraphics, scaledMouseX, scaledMouseY, mc.font)")
        }
        regex(current.parsed < "26") {
            // Fabric API 命令工厂：26.x ClientCommands ↔ ≤1.21.11 ClientCommandManager
            replace("\\bClientCommands\\b", "ClientCommandManager", "\\b\\B", "")

            // 26.x GuiGraphicsExtractor ↔ ≤1.21.11 GuiGraphics
            replace("\\bGuiGraphicsExtractor\\b", "GuiGraphics", "\\b\\B", "")

            // GuiGraphics 文本绘制：26.x text ↔ ≤1.21.11 drawString
            replace("\\bg\\.text\\(", "g.drawString(", "\\b\\B", "")
            replace("\\bgraphics\\.text\\(", "graphics.drawString(", "\\b\\B", "")

            // GuiGraphics 物品绘制：26.x item/itemDecorations ↔ ≤1.21.11 renderItem/renderItemDecorations
            replace("\\bg\\.itemDecorations\\(", "g.renderItemDecorations(", "\\b\\B", "")
            replace("\\bg\\.item\\(", "g.renderItem(", "\\b\\B", "")
        }
    }
}
