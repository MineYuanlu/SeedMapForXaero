#!/usr/bin/env python3
"""Resolve the MC × Xaero version matrix into versions.json.

Sources:
  - piston-meta (Mojang): list of release Minecraft versions (>= min_minecraft)
  - Modrinth: for each MC version, the oldest + newest compatible
    Xaero World Map (fabric) releases, the newest Xaero Minimap release
    (dev-runtime-only, see build.gradle), and the newest fabric-api release.

Modes:
  --update   fetch upstream and rewrite versions.json
  --check    compare versions.json against upstream; exit 1 if stale
  --matrix   read versions.json and print the GitHub Actions strategy matrix
             JSON to stdout

No third-party deps (stdlib urllib only), so CI needs no pip install.
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSIONS_FILE = os.path.join(ROOT_DIR, "versions.json")

PISTON_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MODRINTH_API = "https://api.modrinth.com/v2"
XAERO_SLUG = "xaeros-world-map"
XAERO_MINIMAP_SLUG = "xaeros-minimap"
FABRIC_API_SLUG = "fabric-api"

MIN_MINECRAFT = "1.21.11"

# e.g. "26.1" -> (26, 1);  "1.21.11" -> (1, 21, 11)
def version_key(v: str) -> tuple:
    return tuple(int(x) for x in v.split("."))

# 支持的 MC 版本 = stonecutter 节点目录（versions/<MC 版本>/）∩ piston release。
# versions.json 只为已建节点的版本生成行：周刷新加新 MC 行必须先建节点，
# 否则 CI 矩阵引用不存在的 gradle 项目。artifact 家族由版本号推断（≥26 = modern）。
def node_dirs() -> "set[str]":
    base = os.path.join(ROOT_DIR, "versions")
    if not os.path.isdir(base):
        return set()
    return {d for d in os.listdir(base)
            if os.path.isdir(os.path.join(base, d))}
USER_AGENT = "seedmap4xaero/matrix-resolver (github.com/MineYuanlu/SeedMapForXaero)"

# e.g. "fabric-26.1.2-1.44.2" -> ("26.1.2", "1.44.2")
VERSION_RE = re.compile(r"^fabric-([^-]+)-(\d+\.\d+\.\d+)$")


def http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def modrinth_versions(slug: str, game_version: str) -> "list[dict]":
    url = (
        f"{MODRINTH_API}/project/{slug}/version?"
        f"loaders=%5B%22fabric%22%5D&game_versions=%5B%22{game_version}%22%5D"
    )
    return json.loads(http_get(url))


def mc_releases() -> "list[str]":
    manifest = json.loads(http_get(PISTON_URL))
    releases = [v["id"] for v in manifest["versions"] if v["type"] == "release"]
    return [v for v in releases if version_key(v) >= version_key(MIN_MINECRAFT)]


def pick_xaero(game_version: str) -> "dict | None":
    """Return {line, oldest, newest} for one MC version, or None if unsupported."""
    versions = [v for v in modrinth_versions(XAERO_SLUG, game_version)
                if v["version_type"] == "release"]
    if not versions:
        return None
    versions.sort(key=lambda v: v["date_published"])
    parsed = [VERSION_RE.match(v["version_number"]) for v in versions]
    parsed = [m for m in parsed if m]
    if not parsed:
        return None
    first, last = parsed[0], parsed[-1]
    if first.group(1) != last.group(1):
        raise SystemExit(
            f"error: xaero artifact line changed within {game_version}: "
            f"{first.group(1)} -> {last.group(1)}")
    return {
        "line": first.group(1),
        "oldest": first.group(2),
        "newest": last.group(2),
    }


def pick_fabric_api(game_version: str) -> "str | None":
    versions = [v for v in modrinth_versions(FABRIC_API_SLUG, game_version)
                if v["version_type"] == "release"]
    if not versions:
        return None
    versions.sort(key=lambda v: v["date_published"])
    return versions[-1]["version_number"]


def pick_xaero_minimap(game_version: str) -> "str | None":
    """Newest release Xaero Minimap mod version for one MC version.

    version_number looks like "fabric-<mc>-<modver>"; the maven artifact is
    xaerominimap-fabric-<line>:<modver> (modver only, no fabric prefix).
    """
    versions = [v for v in modrinth_versions(XAERO_MINIMAP_SLUG, game_version)
                if v["version_type"] == "release"]
    versions.sort(key=lambda v: v["date_published"])
    parsed = [VERSION_RE.match(v["version_number"]) for v in versions]
    parsed = [m for m in parsed if m]
    return parsed[-1].group(2) if parsed else None


def resolve() -> "list[dict]":
    nodes = node_dirs()
    rows = []
    for mc in mc_releases():
        if mc not in nodes:
            continue
        xaero = pick_xaero(mc)
        if xaero is None:
            print(f"  WARN {mc}: no release Xaero build found, skipped")
            continue
        fabric_api = pick_fabric_api(mc)
        minimap = pick_xaero_minimap(mc)
        if minimap is None:
            # 矩阵行不带 xaeroMinimapVersion key → 回落 gradle.properties 默认值；
            # 世界图支持而 minimap 不支持的 MC 目前不存在，防御性告警即可
            print(f"  WARN {mc}: no release Xaero Minimap build found")
        is_legacy = version_key(mc) < (26,)
        # 发布 jar 的版本后缀（+mc<artifactVersion>，build.gradle / release.yml 消费）：
        # legacy 只有一个版本 → 用自身 id；modern 是跨版本 universal jar → 家族标签。
        # MC 27 出现时需重审标签策略（届时家族拆分是自然的重审点）。
        rows.append({
            "id": mc,
            "artifact": "legacy" if is_legacy else "modern",
            "artifactVersion": mc if is_legacy else "26.x",
            "java": 21 if is_legacy else 25,
            "xaeroArtifactLine": xaero["line"],
            "xaeroOldest": xaero["oldest"],
            "xaeroNewest": xaero["newest"],
            "xaeroMinimap": minimap,
            "fabricApi": fabric_api,
        })
    return rows


def load_committed() -> "dict":
    with open(VERSIONS_FILE) as f:
        return json.load(f)


def canonical(rows: "list[dict]") -> "dict":
    return {
        "schema": 2,
        "generated": datetime.now(timezone.utc).replace(microsecond=0)
        .isoformat().replace("+00:00", "Z"),
        "minMinecraft": MIN_MINECRAFT,
        "versions": rows,
    }


def write_versions(rows: "list[dict]") -> None:
    with open(VERSIONS_FILE, "w") as f:
        json.dump(canonical(rows), f, indent=2)
        f.write("\n")


def print_matrix(rows: "list[dict]", tag: str = "all") -> None:
    """Emit the GitHub Actions strategy matrix JSON.

    tag=all: every MC version × oldest+newest Xaero (compile/JUnit matrix).
    tag=newest/oldest: only that Xaero endpoint (E2E matrix uses newest).
    """
    include = []
    for r in rows:
        pairs = [("oldest", r["xaeroOldest"]), ("newest", r["xaeroNewest"])]
        if tag == "all":
            # 同线仅一个 release 时 oldest==newest，去重避免空跑同组合两遍
            if r["xaeroOldest"] == r["xaeroNewest"]:
                pairs = [("oldest", r["xaeroOldest"])]
        else:
            pairs = [p for p in pairs if p[0] == tag]
        for t, ver in pairs:
            entry = {
                "mc": r["id"],
                "node": r["id"],
                "artifact": r["artifact"],
                "java": r["java"],
                "xaeroLine": r["xaeroArtifactLine"],
                "xaeroVersion": ver,
                "xaeroTag": t,
                "fabricApi": r["fabricApi"],
            }
            if r.get("xaeroMinimap"):
                # key 名与 -PxaeroMinimapVersion 一致（build.gradle 依赖坐标用）
                entry["xaeroMinimapVersion"] = r["xaeroMinimap"]
            include.append(entry)
    print(json.dumps({"include": include}, indent=2))


def cmd_update(_args) -> int:
    print("Resolving version matrix from upstream ...")
    rows = resolve()
    write_versions(rows)
    print(f"  wrote {VERSIONS_FILE}: {len(rows)} MC rows")
    for r in rows:
        print(f"    {r['id']:6s} line={r['xaeroArtifactLine']:6s} "
              f"xaero={r['xaeroOldest']}..{r['xaeroNewest']} "
              f"minimap={r['xaeroMinimap']} "
              f"fabricApi={r['fabricApi']}")
    return 0


def cmd_check(_args) -> int:
    live = resolve()
    committed = load_committed()
    live_canonical = canonical(live)["versions"]
    committed_versions = committed.get("versions", [])
    if live_canonical == committed_versions:
        print("versions.json is up to date")
        return 0
    print("versions.json is STALE:")
    for lv in live_canonical:
        cv = next((c for c in committed_versions if c["id"] == lv["id"]), None)
        if cv != lv:
            print(f"  {lv['id']}: committed={cv}  live={lv}")
    for cv in committed_versions:
        if not any(l["id"] == cv["id"] for l in live_canonical):
            print(f"  {cv['id']}: committed but no longer released (obsolete)")
    return 1


def cmd_matrix(args) -> int:
    committed = load_committed()
    print_matrix(committed["versions"], tag=args.tag)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["update", "check", "matrix"],
                        help="operation to run")
    parser.add_argument("--tag", choices=["all", "oldest", "newest"], default="all",
                        help="matrix mode: only emit Xaero endpoint(s) for this tag (default: all)")
    args = parser.parse_args()
    return {"update": cmd_update, "check": cmd_check, "matrix": cmd_matrix}[args.mode](args)


if __name__ == "__main__":
    sys.exit(main())
