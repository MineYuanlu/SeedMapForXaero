#!/usr/bin/env python3
"""Download wiki icons -> biomes.png (16x16)"""

import io
import os
import subprocess
import sys
import tempfile

from PIL import Image
import requests

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
C_DIR = os.path.join(ROOT_DIR, "src", "main", "c")
C_BUILD_DIR = os.path.join(C_DIR, "build")

CACHE_DIR = os.path.join(ROOT_DIR, ".cache", "biomes_icons")
os.makedirs(CACHE_DIR, exist_ok=True)

# https://zh.minecraft.wiki/images/BiomeSprite_frozen-ocean.png
BASE = "https://zh.minecraft.wiki/images/BiomeSprite_{}.png"

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
OUT_IMG_FILE = os.path.join(TARGET_DIR, "biomes.png")
OUT_INDEX_FILE = os.path.join(TARGET_DIR, "biomes.ini")


def build_xsmpretools():
    os.makedirs(C_BUILD_DIR, exist_ok=True)
    subprocess.run(
        ["cmake", "-S", C_DIR, "-B", C_BUILD_DIR, "-DCMAKE_BUILD_TYPE=Release"],
        check=True,
    )
    subprocess.run(
        ["cmake", "--build", C_BUILD_DIR, "--target", "xsmpretools"],
        check=True,
    )


# Special mapping table
SPECIAL_NAMES: "dict[str,list[str]]" = {
    "rainforest": ["jungle"],  # 52 雨林只存在于 Beta 1.8-
    "taiga_mountains": ["taiga-hills"],
    "modified_jungle": ["jungle-hills"],
    "snowy_taiga_mountains": ["snowy-taiga-hills"],
    "giant_spruce_taiga_hills": ["old-growth-spruce-taiga"],
}


def wiki_guess_list(name: str) -> "set[str]":
    """猜测 wiki 上的命名"""
    parts = name.lower().split("_")
    results = [
        *SPECIAL_NAMES.get(name, []),
        name,  # 下划线
        name.replace("_", "-"),  # 中划线
        parts[0] + "".join(p.capitalize() for p in parts[1:]),  # 小驼峰
        "".join(p.capitalize() for p in parts),  # 大驼峰
    ]
    seen = set()
    deduped = []
    for s in results:
        if s not in seen:
            seen.add(s)
            deduped.append(s)
    return deduped


def get_biomes():
    build_xsmpretools()
    with tempfile.TemporaryDirectory() as tmpdir:
        out_file = os.path.join(tmpdir, "biomes.txt")
        subprocess.run(
            [
                os.path.join(C_BUILD_DIR, "xsmpretools"),
                "get_biomes_id_to_name",
                out_file,
            ],
            check=True,
        )
        biomes: "list[tuple[int, str]]" = []
        with open(out_file) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                bid, name = line.split("\t", 1)
                biomes.append((int(bid), name))
        return biomes


def download(name:str):
    """下载图标"""
    variants = wiki_guess_list(name)
    for variant in variants:
        cache_file = os.path.join(CACHE_DIR, f"{variant}.png")
        if os.path.exists(cache_file):
            return Image.open(cache_file)

    for variant in variants:
        url = BASE.format(variant)
        r = requests.get(url, timeout=10)
        if r.status_code != 200:
            continue
        cache_file = os.path.join(CACHE_DIR, f"{variant}.png")
        img = Image.open(io.BytesIO(r.content)).convert("RGBA")
        img.save(cache_file)
        return img
    return None


def main():
    os.makedirs(TARGET_DIR, exist_ok=True)
    biomes = get_biomes()
    icons: "list[Image.Image | None]" = []
    errors:"list[str]" = []
    bid2idx:"list[tuple[int,int]]"=[]
    for i,(bid, name) in enumerate(biomes):
        img = download(name)
        if img is None:
            errors.append(f"  biome {bid} ({name}): all variants failed")
            icons.append(None)
        elif img.size != (16, 16):
            errors.append(f"  biome {bid} ({name}): unexpected size {img.size}")
            icons.append(None)
        else:
            icons.append(img)
        bid2idx.append((bid, i))
        print(f"  [{bid:3d}] {'OK' if img else 'FAIL'} {name}")
    assert len(icons) == len(biomes), f"len(biomes) != len(icons): {len(biomes)} != {len(icons)}"
    assert len(bid2idx) == len(biomes), f"len(biomes) != len(bid2idx): {len(biomes)} != {len(bid2idx)}"

    plain = Image.new("RGBA", (16 * len(icons), 16))
    for i, img in enumerate(icons):
        if img is not None:
            plain.paste(img, (i * 16, 0))
    plain.save(OUT_IMG_FILE)
    with open(OUT_INDEX_FILE, "w") as f:
        f.write("\n".join(f"{bid}={idx}" for bid, idx in bid2idx))
    print(f"\nSaved: {plain.size} -> {OUT_IMG_FILE} + {OUT_INDEX_FILE}")

    if errors:
        print(f"\n{len(errors)} error(s):")
        for e in errors:
            print(e)
        sys.exit(1)


if __name__ == "__main__":
    main()
