#!/usr/bin/env python3
"""Resolve the CI toolchain versions into .github/toolchain-versions.json.

Sources:
  - repo.msys2.org mingw64 directory index: latest version per recorded
    msys2 package (Windows native toolchain)
  - dl.google.com repository2 XML: latest Android NDK release

Modes:
  update  fetch upstream and rewrite toolchain-versions.json
  check   compare toolchain-versions.json against upstream; exit 1 if stale

语义说明：本文件记录"最后一次验证通过"的工具链版本（固定包，非 pin）。
update 产出的候选版本必须先在 ci/toolchain-bump 分支上通过完整门禁
（refresh-toolchain.yml），全绿后才会提交到 master；测试失败则维持现状。

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
TOOLCHAIN_FILE = os.path.join(ROOT_DIR, ".github", "toolchain-versions.json")

MSYS2_INDEX_URL = "https://repo.msys2.org/mingw/mingw64/"
ANDROID_REPO_URL = "https://dl.google.com/android/repository/repository2-3.xml"

USER_AGENT = "seedmap4xaero/toolchain-resolver (github.com/MineYuanlu/SeedMapForXaero)"


def http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8")


def version_key(v: str) -> "tuple":
    """Natural sort key: digit runs compare numerically, rest lexically."""
    parts = re.findall(r"\d+|\D+", v)
    return tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in parts)


def latest_msys2_packages(index_html: str, names: "list[str]") -> "dict[str, str]":
    """Pick the newest repo version for each package name.

    Filename shape: <name>-<version>-<rel>-any.pkg.tar.zst; the JSON records
    "<version>-<rel>" (e.g. "14.0.0.r495.g63ebe6cdb-1").
    """
    latest = {}
    for name in names:
        versions = re.findall(
            re.escape(name) + r"-([0-9][^\"<>]*?)-any\.pkg\.tar\.zst", index_html)
        if not versions:
            raise SystemExit(f"error: package {name} not found in msys2 index")
        latest[name] = max(set(versions), key=version_key)
    return latest


def latest_android_ndk(repo_xml: str) -> str:
    versions = set(re.findall(r"android-ndk-(r\d+[a-z]*)-", repo_xml))
    if not versions:
        raise SystemExit("error: no NDK versions found in repository2 XML")

    def ndk_key(v: str) -> "tuple":
        m = re.match(r"r(\d+)([a-z]*)", v)
        return (int(m.group(1)), m.group(2))

    return max(versions, key=ndk_key)


def load_committed() -> "dict":
    with open(TOOLCHAIN_FILE) as f:
        return json.load(f)


def resolve(committed: "dict") -> "dict":
    """Return the candidate toolchain dict (same shape, versions = latest)."""
    packages = committed["windows"]["msys2"]["packages"]
    index_html = http_get(MSYS2_INDEX_URL)
    latest_pkgs = latest_msys2_packages(index_html, list(packages))
    repo_xml = http_get(ANDROID_REPO_URL)
    return {
        "schema": committed.get("schema", 1),
        "updated": datetime.now(timezone.utc).replace(microsecond=0)
        .isoformat().replace("+00:00", "Z"),
        "windows": {"msys2": {"packages": latest_pkgs}},
        "android": {"ndk": latest_android_ndk(repo_xml)},
    }


def diff(committed: "dict", candidate: "dict") -> "list[str]":
    changes = []
    for name, live in candidate["windows"]["msys2"]["packages"].items():
        cur = committed["windows"]["msys2"]["packages"].get(name)
        if cur != live:
            changes.append(f"  msys2 {name}: {cur} -> {live}")
    cur_ndk = committed["android"]["ndk"]
    if cur_ndk != candidate["android"]["ndk"]:
        changes.append(f"  android ndk: {cur_ndk} -> {candidate['android']['ndk']}")
    return changes


def write_toolchain(candidate: "dict") -> None:
    with open(TOOLCHAIN_FILE, "w") as f:
        json.dump(candidate, f, indent=2)
        f.write("\n")


def cmd_update(_args) -> int:
    print("Resolving toolchain versions from upstream ...")
    committed = load_committed()
    candidate = resolve(committed)
    changes = diff(committed, candidate)
    if not changes:
        print("toolchain-versions.json is already up to date")
        return 0
    write_toolchain(candidate)
    print(f"  wrote {TOOLCHAIN_FILE}:")
    for c in changes:
        print(c)
    return 0


def cmd_check(_args) -> int:
    committed = load_committed()
    candidate = resolve(committed)
    changes = diff(committed, candidate)
    if not changes:
        print("toolchain-versions.json is up to date")
        return 0
    print("toolchain-versions.json is STALE (upgrade candidates available):")
    for c in changes:
        print(c)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["update", "check"],
                        help="operation to run")
    args = parser.parse_args()
    return {"update": cmd_update, "check": cmd_check}[args.mode](args)


if __name__ == "__main__":
    sys.exit(main())
