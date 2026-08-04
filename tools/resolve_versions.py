#!/usr/bin/env python3
"""Resolve the MC × Xaero version matrix into versions.json.

Sources:
  - piston-meta (Mojang): list of release Minecraft versions (>= min_minecraft)
  - Modrinth: for each MC version, the oldest + newest compatible
    Xaero World Map (fabric) releases, and the newest fabric-api release.

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
FABRIC_API_SLUG = "fabric-api"

MIN_MINECRAFT = "26.1"
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
    return [v for v in releases if v >= MIN_MINECRAFT]


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


def resolve() -> "list[dict]":
    rows = []
    for mc in mc_releases():
        xaero = pick_xaero(mc)
        if xaero is None:
            print(f"  WARN {mc}: no release Xaero build found, skipped")
            continue
        fabric_api = pick_fabric_api(mc)
        rows.append({
            "id": mc,
            "java": 25,
            "loomPlugin": None,
            "xaeroArtifactLine": xaero["line"],
            "xaeroOldest": xaero["oldest"],
            "xaeroNewest": xaero["newest"],
            "fabricApi": fabric_api,
        })
    return rows


def load_committed() -> "dict":
    with open(VERSIONS_FILE) as f:
        return json.load(f)


def canonical(rows: "list[dict]") -> "dict":
    return {
        "schema": 1,
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
        for t, ver in (("oldest", r["xaeroOldest"]), ("newest", r["xaeroNewest"])):
            if tag != "all" and t != tag:
                continue
            include.append({
                "mc": r["id"],
                "java": r["java"],
                "xaeroLine": r["xaeroArtifactLine"],
                "xaeroVersion": ver,
                "xaeroTag": t,
                "fabricApi": r["fabricApi"],
            })
    print(json.dumps({"include": include}, indent=2))


def cmd_update(_args) -> int:
    print("Resolving version matrix from upstream ...")
    rows = resolve()
    write_versions(rows)
    print(f"  wrote {VERSIONS_FILE}: {len(rows)} MC rows")
    for r in rows:
        print(f"    {r['id']:6s} line={r['xaeroArtifactLine']:6s} "
              f"xaero={r['xaeroOldest']}..{r['xaeroNewest']} "
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
