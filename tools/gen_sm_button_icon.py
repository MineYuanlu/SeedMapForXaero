#!/usr/bin/env python3
"""生成 XsmIconButton 的 16x16 贴图（assets …/textures/gui/sm_button.png）"""

from PIL import Image
from PIL.Image import Resampling
import os, sys

# 脚本位于 <repo>/tools/，产物直接写到 client 资源目录
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    REPO, "src", "client", "resources", "assets", "seed-map-for-xaero",
    "textures", "gui")


COLORS = {
    ".": (0, 0, 0, 0),  # 无色
    "G": (0, 0, 0, 135), # 灰色阴影
    "W": (255, 255, 255, 255),  # 白色
    "A": (106, 190, 48, 255),  # 嫩绿
    "B": (60, 130, 30, 255),  # 叶暗/茎深绿
    "C": (140, 100, 60, 255),  # 土堆棕
}

SEED = """
................
................
.....A..........
....AAA.........
....AAAAA.......
.....AAAAA......
......AAAA......
.......B........
.......B........
.......B........
......CC........
.....CCCC.......
....CCCCCC......
.....CCCC.......
................
................
"""

FONT_M7 = {
    "S": [".WWW.", "W...W", "W....", ".WWW.", "....W", "W...W", ".WWW."],
    "M": ["W.....W", "WW...WW", "W.W.W.W", "W..W..W", "W.....W", "W.....W", "W.....W"],
}


def color_map(ch: str):
    assert ch in COLORS, f"Unknown character: {ch}"
    return COLORS[ch]


def text_to_px(s, x0, y0, gap=1):
    px: "dict[tuple[int,int],tuple[int, int, int, int]]" = {}
    cx = x0
    for ch in s:
        g = FONT_M7[ch]
        for j, row in enumerate(g):
            for i, c in enumerate(row):
                px[(cx + i, y0 + j)] = color_map(c)
                # if c == "#":
                #     px[(cx + i, y0 + j)] = color
        cx += len(g[0]) + gap
    return px


def ascii_to_px(s: str): 
    px: "dict[tuple[int,int],tuple[int, int, int, int]]"={}
    for x,line in enumerate(s.strip("\n").splitlines()):
        for y, ch in enumerate(line):
            px[(y, x)] = color_map(ch)
    return px

def blend_pixel(bottom, top):
    """将 top 覆盖到 bottom 上（source-over），返回合并后的 RGBA tuple。
    
    两个参数均为 (r, g, b, a)，取值 0..255。
    """
    if not top:
        return bottom
    if not bottom:
        return top
    br, bg, bb, ba = bottom
    tr, tg, tb, ta = top

    # 快路径：整像素不透明 / 完全透明
    if ta == 255:
        return top
    if ta == 0:
        return bottom

    a_b = ba / 255.0
    a_t = ta / 255.0

    a_out = a_t + a_b * (1.0 - a_t)
    if a_out == 0:
        return (0, 0, 0, 0)

    inv = 1.0 - a_t
    r_out = (tr * a_t + br * a_b * inv) / a_out
    g_out = (tg * a_t + bg * a_b * inv) / a_out
    b_out = (tb * a_t + bb * a_b * inv) / a_out

    return (
        max(0, min(255, round(r_out))),
        max(0, min(255, round(g_out))),
        max(0, min(255, round(b_out))),
        max(0, min(255, round(a_out * 255))),
    )

def shadow_px(px, dx=1, dy=1, color="G"):
    """只有可见像素（a>0）投影；返回投影 dict（不与本体混合）。"""
    out = {}
    for (x, y), c in px.items():
        if c[3] > 0:
            nx, ny = x + dx, y + dy
            if 0 <= nx < 16 and 0 <= ny < 16:
                out[(nx, ny)] = color_map(color)
    return out


def visible(px):
    """剔除 ascii/字模里产生的透明占位项（避免干扰合并）。"""
    return {k: c for k, c in px.items() if c[3] > 0}


def compose_shadowed(seed, sm):
    """图层栈（底→顶）:
       种子阴影 → 种子本体 → SM阴影 → SM本体。
       单级阴影: SM 阴影跳过"已是阴影色"的格子，避免双重叠黑。
    """
    sh_seed = shadow_px(seed)
    sh_sm = shadow_px(sm)
    merged = {}

    for key, c in sh_seed.items():
        merged[key] = c
    for key, c in seed.items():          # 本体盖过自己同层的阴影
        merged[key] = blend_pixel(merged.get(key), c)
    for key, c in sh_sm.items():
        if merged.get(key) == color_map("G"):
            continue                     # 单级阴影去重
        merged[key] = blend_pixel(merged.get(key), c)
    for key, c in sm.items():
        merged[key] = blend_pixel(merged.get(key), c)
    return merged

def dump_img(
    px: "dict[tuple[int,int],tuple[int, int, int, int]]", path: str, w=16, h=16
):
    print(f"saving image {w}x{h} to {path}")
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for (x, y), color in px.items():
        img.putpixel((x, y), color)
    img.save(path, "PNG")
    print(f"Saved to {path}")


if __name__ == "__main__":

    def main():
        sm = visible(text_to_px("SM", 1, 8, gap=1))
        seed = visible(ascii_to_px(SEED))
        merge = compose_shadowed(seed, sm)
        dump_img(merge, os.path.join(OUT, "sm_button.png"))
        # ascii 预览
        mark = {v: k for k, v in COLORS.items()}
        g = [["." for _ in range(16)] for _ in range(16)]
        for (x, y), c in merge.items():
            g[y][x] = mark.get(c, "?")
        for r in g:
            print("".join(r))
    main()
