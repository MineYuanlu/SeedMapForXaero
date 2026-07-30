#!/usr/bin/env python3
"""Download wiki icons -> structures_plain.png (16x16) + structures.png (20x20 outlined)."""

from PIL import Image
import requests
import sys
import io
import os

BASE = "https://zh.minecraft.wiki/images/{}"

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET_DIR = os.path.join(
    ROOT_DIR, "src", "client", "resources", "assets",
    "seed-map-for-xaero", "textures", "icons",
)
assert os.path.isdir(TARGET_DIR), f"Missing {TARGET_DIR}"

PLAIN_FILE = os.path.join(TARGET_DIR, "structures_plain.png")
OUTLINED_FILE = os.path.join(TARGET_DIR, "structures.png")

PADDING = 2
OUT_SIZE = 16 + 2 * PADDING  # 20

# spriteIndex matches StructureType.java
# Each entry is (primary_filename, fallback_filename, ...)
SPRITES: "list[tuple[str, ...]]" = [
    ("EnvSprite_desert-pyramid.png",),                          # 0  desert_pyramid
    ("BlockSprite_moss-cobblestone.png",),                      # 1  jungle_temple
    ("EntitySprite_witch.png",),                                # 2  swamp_hut
    ("EnvSprite_igloo.png",),                                   # 3  igloo
    ("EntitySprite_leatherworker.png",),                        # 4  village
    ("EntitySprite_drowned.png",),                              # 5  ocean_ruin
    ("ItemSprite_oak-boat.png",),                               # 6  shipwreck
    ("ItemSprite_prismarine-crystals.png",),                    # 7  monument
    ("EnvSprite_mansion.png",),                                 # 8  mansion
    ("EntitySprite_johnny.png",),                               # 9  outpost
    ("EnvSprite_overworld-ruined-portal.png",),                # 10 ruined_portal
    ("EnvSprite_the-nether-ruined-portal.png", "EnvSprite_ruined-portal.png"),  # 11 ruined_portal_nether
    ("EnvSprite_ancient-city.png",),                            # 12 ancient_city
    ("EnvSprite_buried-treasure.png", "EnvSprite_bonus-chest.png"),  # 13 treasure
    ("EnvSprite_abandoned-mineshaft.png", "EnvSprite_mineshaft.png"), # 14 mineshaft
    ("EnvSprite_desert-well.png",),                             # 15 desert_well
    ("BlockSprite_amethyst-cluster.png",),                      # 16 geode
    ("EnvSprite_nether-fortress.png", "EnvSprite_fortress.png"), # 17 fortress
    ("EnvSprite_bastion-remnant.png",),                         # 18 bastion
    ("EnvSprite_end-city.png",),                                # 19 end_city
    ("EnvSprite_end-gateway.png",),                             # 20 end_gateway
    ("EnvSprite_end-island.png",),                              # 21 end_island
    ("ItemSprite_brush.png",),                                  # 22 trail_ruins
    ("BlockSprite_trial-spawner-inactive.png",),                # 23 trial_chambers
    ("ItemSprite_ender-eye.png",),                              # 24 stronghold
]


def download(name):
    url = BASE.format(name)
    r = requests.get(url, timeout=10)
    if r.status_code != 200:
        return None
    return Image.open(io.BytesIO(r.content)).convert("RGBA")


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
    icons: "list[Image.Image | None]" = []
    errors = []
    for i, names in enumerate(SPRITES):
        img = None
        for n in names:
            img = download(n)
            if img is not None:
                break
        if img is None:
            errors.append(f"  spriteIndex {i}: tried {names}, all failed")
            icons.append(None)
        elif img.size != (16, 16):
            errors.append(f"  spriteIndex {i}: unexpected size {img.size}")
            icons.append(None)
        else:
            icons.append(img)
        print(f"  [{i:2d}] {'OK' if img else 'FAIL'} {names[0]}")

    # --- plain 16x16 (no outline) ---
    plain = Image.new("RGBA", (16 * len(SPRITES), 16))
    for i, img in enumerate(icons):
        if img is not None:
            plain.paste(img, (i * 16, 0))
    plain.save(PLAIN_FILE)
    print(f"\nPlain: {plain.size} -> {PLAIN_FILE}")

    # --- outlined 20x20 ---
    outlined = Image.new("RGBA", (OUT_SIZE * len(SPRITES), OUT_SIZE))
    for i, img in enumerate(icons):
        if img is not None:
            expanded = Image.new("RGBA", (OUT_SIZE, OUT_SIZE))
            expanded.paste(img, (PADDING, PADDING))
            outlined.paste(add_outline(expanded), (i * OUT_SIZE, 0))
    outlined.save(OUTLINED_FILE)
    print(f"Outlined: {outlined.size} -> {OUTLINED_FILE}")

    if errors:
        print(f"\n{len(errors)} error(s):")
        for e in errors:
            print(e)
        sys.exit(1)


if __name__ == "__main__":
    main()
