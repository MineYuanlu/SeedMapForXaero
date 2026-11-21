#!/usr/bin/env python3
"""Guardrail: 版本化条件注释与替换词必须处于 26.1.2（vcsVersion）规范化状态。

活跃节点（26.1.2）直接编译共享源码树，规范化状态 = 两层不变量：
  1. 结构：`//? if` 块中，条件对 26.1.2 为真的分支保持可编译代码，
     为假的分支包在 /* */ 注释里。
  2. 替换词：stonecutter.gradle.kts 的 replacements 按 legacy（<26）方向定义，
     静止态（26.1.2）的共享树活跃代码必须是 modern 侧写法 —— 活跃代码中
     不得出现 legacy 模式（被包裹分支注释里的 legacy 写法是合法的）。

为什么不用 stonecutter 的 switch 任务做这个检查/恢复：switch 任务只改
controller 的 `active` 行 + 把 `versions/<节点>/build/generated` 拷回 src/
（merge），而 vcs 节点的 generate 是原样拷贝 —— 对 26.1.2 来说"切回来"
永远不动树；切到 legacy 再切回只会留下替换词污染（实测 2026-11）。
本脚本对源码做确定性静态检查，不依赖 Gradle。

条件语法（当前全库词汇表）：`>=X` / `<X`（可扩展 `<=` `>` `==`），
按 stonecutter 的前缀截断语义与 26.1.2 比较（26.1.2 与 26.1 只比到 (26,1)）。
用法: python3 tools/check_switch_normalized.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ACTIVE = (26, 1, 2)  # vcsVersion，与 settings.gradle 的 stonecutter 配置保持一致
SCAN_DIRS = ["src", "src-modern", "src-legacy"]  # 结构检查范围
TOKEN_SCOPE = ["src"]  # 替换词检查范围：仅共享树（src-legacy 按设计直调 legacy API）

COND_RE = re.compile(r"^\s*//\? if\s+(>=|<=|>|<|==|=)\s*([\d.]+)\s*\{\s*$")
# 结束标记可能紧跟被包裹分支的注释闭合：`*///?}` / `*///?} else {`
CLOSE_RE = re.compile(r"^\s*(?:\*/\s*)?//\?\}\s*(?:else\s*\{)?\s*$")
ELSE_RE = re.compile(r"^\s*(?:\*/\s*)?//\?\}\s*else\s*\{\s*$")

# 与 stonecutter.gradle.kts 的 replacements 保持同步（legacy 侧模式，反向核对清单）。
# 注意：这里的模式必须与控制器里的正则同源；改控制器后同步改这里。
TOKEN_RULES = [
    (r'method = "render"', 'mixin 目标应为 method = "extractRenderState"'),
    (r'render\(g, mx, my, 0\)', 'EditBox 渲染入口应为 extractRenderState(g, mx, my, 0)'),
    (r'widget\.render\(guiGraphics', 'loot widget 入口应为 widget.extractRenderState(...)'),
    (r'\bClientCommandManager\b', 'Fabric API 命令工厂应为 ClientCommands'),
    (r'\bGuiGraphics\b', 'MC 26.x 图形类应为 GuiGraphicsExtractor'),
    (r'\bg\.drawString\(', '文本绘制应为 g.text('),
    (r'\bgraphics\.drawString\(', '文本绘制应为 graphics.text('),
    (r'\bg\.renderItemDecorations\(', '物品 decorations 应为 g.itemDecorations('),
    (r'\bg\.renderItem\(', '物品绘制应为 g.item('),
]


def version_key(v: str) -> tuple:
    return tuple(int(x) for x in v.split("."))


def eval_cond(op: str, v: str) -> bool:
    # stonecutter 的版本比较是前缀截断语义：26.1.2 与 26.1 比较时只比到 (26,1)，
    # 即 `>=26.1` 匹配整个 26.1.x 线（与 6 节点构建的实际行为一致）
    a, b = version_key(v), ACTIVE
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b,
            "==": a == b, "=": a == b}[op]


def scan_line(line: str, depth: int) -> "tuple[int, str]":
    """逐字符扫描一行 → (行尾块注释深度, 该行活跃代码文本)。

    块注释内部与行注释（含 //? 标记）不计入活跃文本。
    """
    i, n = 0, len(line)
    out = []
    while i < n:
        if depth > 0:
            if line.startswith("*/", i):
                depth -= 1
                i += 2
            else:
                i += 1
        elif line.startswith("/*", i):
            depth += 1
            i += 2
        elif line.startswith("//", i):
            break
        else:
            out.append(line[i])
            i += 1
    return depth, "".join(out)


def check_structure(path: Path, lines: "list[str]") -> "list[str]":
    issues = []
    # depth_at_start[i] = 第 i 行扫描前的块注释深度
    depth_at_start = []
    d = 0
    for line in lines:
        depth_at_start.append(d)
        d, _ = scan_line(line, d)

    open_block = None  # (line_no, cond_op, cond_v, expect_if_live)
    for i, line in enumerate(lines):
        if open_block is None:
            if depth_at_start[i] == 0:
                m = COND_RE.match(line)
                if m:
                    expect_if_live = eval_cond(m.group(1), m.group(2))
                    open_block = (i, m.group(1), m.group(2), expect_if_live)
            continue
        start, op, ver, expect_if_live = open_block
        if depth_at_start[i] != 0 and not line.lstrip().startswith("*/"):
            continue  # 被包裹分支的内部行
        if not CLOSE_RE.match(line):
            continue
        close_i = i
        is_else = bool(ELSE_RE.match(line))
        # if 分支活性 = 到达结束标记行时的注释深度（包裹的分支 /* 尚未闭合 → 深度 1）
        if_live = depth_at_start[close_i] == 0
        if if_live != expect_if_live:
            issues.append(
                f"{path}:{start + 1}: if 分支应为"
                f"{'可编译代码' if expect_if_live else '/* */ 注释'}"
                f"（//? if {op}{ver}），实际{'未注释' if if_live else '被注释'}")
        if is_else:
            # else 分支从结束标记行的下一行开始，其活性 = 到达真正闭合行时的深度
            j = close_i + 1
            while j < len(lines) and not CLOSE_RE.match(lines[j]):
                j += 1
            if j >= len(lines):
                issues.append(f"{path}:{start + 1}: else 分支缺少 //?}} 闭合")
                open_block = None
                continue
            else_live = depth_at_start[j] == 0
            if else_live == if_live:
                issues.append(
                    f"{path}:{start + 1}: if/else 两分支活性相同"
                    f"（必须恰有一个分支为可编译代码）")
        open_block = None
    if open_block is not None:
        issues.append(f"{path}:{open_block[0] + 1}: //? if 块缺少 //?}} 闭合")
    return issues


def live_code(path: Path) -> str:
    """提取文件的活跃代码文本（剔除块注释内部与行注释，含 //? 标记）。"""
    parts = []
    d = 0
    with path.open(encoding="utf-8") as f:
        for line in f:
            d, code = scan_line(line.rstrip("\n"), d)
            parts.append(code)
    return "\n".join(parts)


def check_tokens(path: Path) -> "list[str]":
    text = live_code(path)
    issues = []
    for pattern, hint in TOKEN_RULES:
        if re.search(pattern, text):
            issues.append(f"{path}: 活跃代码出现 legacy 替换词 /{pattern}/ —— {hint}")
    return issues


def main() -> int:
    issues = []
    for d in SCAN_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            content = path.read_text(encoding="utf-8")
            if "//? if" in content:
                issues.extend(check_structure(path, content.splitlines()))
    for d in TOKEN_SCOPE:
        base = ROOT / d
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            issues.extend(check_tokens(path))
    if issues:
        print("::error::共享源码树未处于 26.1.2（vcsVersion）规范化状态:")
        for msg in issues:
            print(f"  {msg}")
        print("恢复方式: git checkout -- . （不要用 stonecutter switch 任务切回，"
              "vcs 节点的 generate 是原样拷贝，不会修复树）")
        return 1
    print("guardrail ok: 条件注释与替换词处于 26.1.2（vcsVersion）规范化状态")
    return 0


if __name__ == "__main__":
    sys.exit(main())
