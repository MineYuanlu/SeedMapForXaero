#!/usr/bin/env python3
"""Download wiki icons -> structures_plain.png (16x16) + structures.png (20x20 outlined).
Also generates structures.ini (structure id -> variant:spriteIndex).

SPRITES 第一层键为 structID, 值:
  str                        -> variant 0 图标, 单一来源
  tuple[str, ...]            -> variant 0 图标, 带 fallback 列表
  dict[int, str|tuple[...]]  -> variant 码 -> 图标来源 (variant 0 必须存在)
"PLACEHOLDER" 生成棋盘格占位图标 (颜色按 id+variant 区分), 待手工挑选 wiki 图标替换。
spriteIndex 按像素去重后自动分配, 与 structures.ini 保持一致。
下载有本地缓存 (.cache/structures_icons/, 键 = wiki 文件名)。"""

from PIL import Image, ImageDraw, ImageFont
import hashlib
import io
import os
import requests

BASE = "https://zh.minecraft.wiki/images/{}"

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET_DIR = os.path.join(
    ROOT_DIR,
    "src",
    "client",
    "resources",
    "assets",
    "seed-map-for-xaero",
    "textures",
    "icons",
)
assert os.path.isdir(TARGET_DIR), f"Missing {TARGET_DIR}"

CACHE_DIR = os.path.join(ROOT_DIR, ".cache", "structures_icons")
os.makedirs(CACHE_DIR, exist_ok=True)

PLAIN_FILE = os.path.join(TARGET_DIR, "structures_plain.png")
OUTLINED_FILE = os.path.join(TARGET_DIR, "structures.png")
INI_FILE = os.path.join(TARGET_DIR, "structures.ini")

PADDING = 2
OUT_SIZE = 16 + 2 * PADDING  # 20

LOCAL_ASSETS_PREFIX = "__local_assets__:"


def local_assets(file) -> str:
    path = os.path.join(ROOT_DIR, "assets", "structures", file)
    return LOCAL_ASSETS_PREFIX + path


SPRITES: "dict[int, str | tuple[str, ...] | dict[int, str | tuple[str, ...]]]" = {
    0: "BlockSprite_grass-block.png",  # feature
    1: "EnvSprite_desert-pyramid.png",  # desert_pyramid
    2: "BlockSprite_moss-cobblestone.png",  # jungle_temple
    3: "EntitySprite_witch.png",  # swamp_hut
    4: {  # igloo / basement
        0: "EnvSprite_igloo.png",
        1: local_assets("igloo-basement.png"),
    },
    5: {  # village: 0 plain, 1 desert, 2 savanna, 3 taiga, 4 snowy, 8-12 zombie x biome
        0: "EntitySprite_leatherworker.png",
        1: "EntitySprite_villager-desert.png",
        2: "EntitySprite_villager-savanna.png",
        3: "EntitySprite_villager-taiga.png",
        4: "EntitySprite_villager-snowy.png",
        8: "EntitySprite_zombie-villager.png",
        9: "EntitySprite_zombie-villager-desert.png",
        10: "EntitySprite_zombie-villager-savanna.png",
        11: "EntitySprite_zombie-villager-taiga.png",
        12: "EntitySprite_zombie-villager-snowy.png",
    },
    6: "EntitySprite_drowned.png",  # ocean_ruin
    7: {  # shipwreck / beached
        0: "ItemSprite_oak-boat.png",
        1: "ItemSprite_oak-boat.png",  # "EnvSprite_shipwreck.png",
    },
    8: "ItemSprite_prismarine-crystals.png",  # monument
    9: "EnvSprite_mansion.png",  # mansion
    10: "EntitySprite_johnny.png",  # outpost
    11: {  # ruined_portal: 0 normal, 1 giant, 2 underground, 4 airpocket
        0: "EnvSprite_overworld-ruined-portal.png",
        1: "EnvSprite_overworld-ruined-portal.png",
        2: "EnvSprite_overworld-ruined-portal.png",
        4: "EnvSprite_overworld-ruined-portal.png",
    },
    12: {  # ruined_portal_nether: 同上
        0: ("EnvSprite_ruined-portal.png", "EnvSprite_the-nether-ruined-portal.png"),
        1: ("EnvSprite_ruined-portal.png", "EnvSprite_the-nether-ruined-portal.png"),
        2: ("EnvSprite_ruined-portal.png", "EnvSprite_the-nether-ruined-portal.png"),
        4: ("EnvSprite_ruined-portal.png", "EnvSprite_the-nether-ruined-portal.png"),
    },
    13: "EnvSprite_ancient-city.png",  # ancient_city
    14: ("EnvSprite_bonus-chest.png", "EnvSprite_buried-treasure.png"),  # treasure
    15: ("EnvSprite_abandoned-mineshaft.png", "EnvSprite_mineshaft.png"),  # mineshaft
    16: "EnvSprite_desert-well.png",  # desert_well
    17: {  # geode / cracked
        0: "BlockSprite_amethyst-cluster.png",
        1: "BlockSprite_amethyst-cluster.png",
    },
    18: ("EnvSprite_fortress.png", "EnvSprite_nether-fortress.png"),  # fortress
    19: {  # bastion: 0 housing, 1 hoglin_stable, 2 treasure, 3 bridge
        0: "EnvSprite_bastion-remnant.png",
        1: "EnvSprite_bastion-remnant.png",
        2: "EnvSprite_bastion-remnant.png",
        3: "EnvSprite_bastion-remnant.png",
    },
    20: {  # end_city / ship
        0: "EnvSprite_end-city.png",
        1: "EnvSprite_end-ship.png",
    },
    21: "EnvSprite_end-gateway.png",  # end_gateway
    22: local_assets("end-island.png"),  # end_island
    23: "ItemSprite_brush.png",  # trail_ruins
    24: {  # trial_chambers: 0 entrance, 1 end, 2-3 corridor
        0: "BlockSprite_trial-spawner-inactive.png",
        1: "BlockSprite_trial-spawner-inactive.png",
        2: "BlockSprite_trial-spawner-inactive.png",
        3: "BlockSprite_trial-spawner-inactive.png",
    },
    25: "ItemSprite_ender-eye.png",  # stronghold
}


def placeholder(sid: int, variant: int) -> Image.Image:
    """占位图标: 棋盘格 + 变种号, 颜色按 (sid, variant) 确定, 待手工替换。"""
    h = hashlib.md5(f"{sid}:{variant}".encode()).hexdigest()
    c1 = tuple(int(h[i : i + 2], 16) for i in (0, 2, 4))
    c2 = tuple(int(h[i : i + 2], 16) for i in (6, 8, 10))
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = (*c1, 255) if (x // 4 + y // 4) % 2 == 0 else (*c2, 255)
    lum = (c1[0] * 299 + c1[1] * 587 + c1[2] * 114) // 1000
    d = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=8)
    text = f"{variant}"
    tb = d.textbbox((0, 0), text, font=font)
    d.text(
        ((16 - tb[2]) // 2, (16 - tb[3]) // 2),
        text,
        font=font,
        fill=(0, 0, 0, 255) if lum > 128 else (255, 255, 255, 255),
    )
    return img


def download(name: str):
    """下载图标; 本地缓存优先, 成功下载后写入缓存 (键 = wiki 文件名, 已含 .png)"""
    if name.startswith(LOCAL_ASSETS_PREFIX):
        path = name[len(LOCAL_ASSETS_PREFIX) :]
        assert os.path.isfile(path), f"Not Found File: {path}"
        return Image.open(path).convert("RGBA")
    cache_file = os.path.join(CACHE_DIR, name)
    if os.path.exists(cache_file):
        return Image.open(cache_file).convert("RGBA")
    url = BASE.format(name)
    r = requests.get(url, timeout=10)
    if r.status_code != 200:
        return None
    img = Image.open(io.BytesIO(r.content)).convert("RGBA")
    img.save(cache_file)
    return img


def resolve_icon(sid: int, variant: int, src) -> "tuple[Image.Image, str, list[str]]":
    """解析图标来源 -> (图标, 实际使用的文件名, 警告列表); 失败用占位符兜底。"""
    warnings = []
    names = (src,) if isinstance(src, str) else src
    for n in names:
        if n == "PLACEHOLDER":
            continue
        img = download(n)
        if img is not None and img.size == (16, 16):
            return img, n, warnings
        if img is not None:
            warnings.append(f"{n}: unexpected size {img.size}")
        else:
            warnings.append(f"{n}: download failed")
    return placeholder(sid, variant), "PLACEHOLDER", warnings


NEIGHBORS_8 = [(dx, dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1) if dx != 0 or dy != 0]


def add_outline(img: Image.Image) -> Image.Image:
    pixels = img.load()
    w, h = img.size
    opaque = [[False] * h for _ in range(w)]
    ring1 = [[False] * h for _ in range(w)]
    ring2 = [[False] * h for _ in range(w)]

    # Pass 0: build opaque mask
    for x in range(w):
        for y in range(h):
            opaque[x][y] = pixels[x, y][3] == 255

    # Pass 1: ring1 — transparent pixel with any opaque 8-neighbor
    for x in range(w):
        for y in range(h):
            if opaque[x][y]:
                continue
            for dx, dy in NEIGHBORS_8:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and opaque[nx][ny]:
                    ring1[x][y] = True
                    break

    # Pass 2: ring2 — transparent (not opaque, not ring1) with any ring1 neighbor
    for x in range(w):
        for y in range(h):
            if opaque[x][y] or ring1[x][y]:
                continue
            for dx, dy in NEIGHBORS_8:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and ring1[nx][ny]:
                    ring2[x][y] = True
                    break

    # Pass 3: remove ring1 pixels that have no ring2 neighbor (unless on image edge)
    for x in range(w):
        for y in range(h):
            if not ring1[x][y]:
                continue
            if x == 0 or x == w - 1 or y == 0 or y == h - 1:
                # edge pixel — keep
                continue
            has_ring2 = False
            for dx, dy in NEIGHBORS_8:
                nx, ny = x + dx, y + dy
                if ring2[nx][ny]:
                    has_ring2 = True
                    break
            if not has_ring2:
                ring1[x][y] = False

    # Apply colors
    out = img.copy()
    out_pixels = out.load()
    for x in range(w):
        for y in range(h):
            if ring2[x][y]:
                out_pixels[x, y] = (255, 255, 255, 255)
            elif ring1[x][y]:
                out_pixels[x, y] = (0, 0, 0, 255)
    return out


def main():
    resolved: "dict[tuple[int, int], tuple[Image.Image, str, list[str]]]" = {}
    for sid in sorted(SPRITES):
        spec = SPRITES[sid]
        if isinstance(spec, dict):
            if 0 not in spec:
                raise ValueError(
                    f"id {sid}: variant 0 缺失 (dict 形式必须包含 variant 0)"
                )
            variants = dict(sorted(spec.items()))
        else:
            variants = {0: spec}
        for v, src in variants.items():
            img, used, warnings = resolve_icon(sid, v, src)
            resolved[(sid, v)] = (img, used, warnings)
            if used == "PLACEHOLDER":
                print(
                    f"  id {sid:2d} v{v:2d}: 占位符"
                    + (f" ({'; '.join(warnings)})" if warnings else "")
                )
            else:
                print(
                    f"  id {sid:2d} v{v:2d}: {used}"
                    + (f" (fallback, {'; '.join(warnings)})" if warnings else "")
                )

    # 按像素去重 -> spriteIndex (ini 与贴图共用同一索引)
    index_of: "dict[bytes, int]" = {}
    unique: "list[Image.Image]" = []
    sprite_index: "dict[tuple[int, int], int]" = {}
    for key in sorted(resolved):
        img, _, _ = resolved[key]
        pixels = img.tobytes()
        idx = index_of.get(pixels)
        if idx is None:
            idx = len(unique)
            index_of[pixels] = idx
            unique.append(img)
        sprite_index[key] = idx

    # --- plain 16x16 (no outline) ---
    plain = Image.new("RGBA", (16 * len(unique), 16))
    for i, img in enumerate(unique):
        plain.paste(img, (i * 16, 0))
    plain.save(PLAIN_FILE)
    print(f"\nPlain: {plain.size} -> {PLAIN_FILE}")

    # --- outlined 20x20 ---
    outlined = Image.new("RGBA", (OUT_SIZE * len(unique), OUT_SIZE))
    for i, img in enumerate(unique):
        expanded = Image.new("RGBA", (OUT_SIZE, OUT_SIZE))
        expanded.paste(img, (PADDING, PADDING))
        outlined.paste(add_outline(expanded), (i * OUT_SIZE, 0))
    outlined.save(OUTLINED_FILE)
    print(f"Outlined: {outlined.size} -> {OUTLINED_FILE}")

    # --- structures.ini: id=variant:index;... (variant 0 恒最前) ---
    with open(INI_FILE, "w", encoding="utf-8") as f:
        for sid in sorted(SPRITES):
            pairs = [((s, v), i) for (s, v), i in sprite_index.items() if s == sid]
            if not pairs or pairs[0][0][1] != 0:
                raise ValueError(f"id {sid}: variant 0 解析失败, 无法生成 ini")
            entries = ";".join(f"{v}:{i}" for (_, v), i in pairs)
            f.write(f"{sid}={entries}\n")
    print(f"Ini: {INI_FILE}")
    for sid in sorted(SPRITES):
        pairs = [((s, v), i) for (s, v), i in sprite_index.items() if s == sid]
        print(f"  {sid}: " + ";".join(f"{v}:{i}" for (_, v), i in pairs))


if __name__ == "__main__":
    main()
