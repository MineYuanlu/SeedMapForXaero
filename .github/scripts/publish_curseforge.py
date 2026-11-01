#!/usr/bin/env python3
"""Publish the universal JAR to CurseForge (project 1774452).

Usage: publish_curseforge.py [--version V] [--dry-run] <jar>

Env:
  CURSEFORGE_TOKEN  author API token (required, secret in CI)
  CHANGELOG         markdown changelog text (required)
  CF_FIXTURE_DIR    offline fixtures {version-types.json, game-versions.json}
                    instead of querying the live API (for offline tests)

Game version IDs are resolved live from the CurseForge Upload API
(https://minecraft.curseforge.com/api/game/version-types and
/api/game/versions), with MC targets derived from versions.json —
so new MC lines need no maintenance here.

Only stdlib is used (no pip install), mirroring tools/resolve_versions.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API_BASE = "https://minecraft.curseforge.com/api"
DEFAULT_PROJECT_ID = "1774452"
REPO_ROOT = Path(__file__).resolve().parents[2]
UA = "seedmap4xaero/curseforge-publisher (+github.com/MineYuanlu/SeedMapForXaero)"

# Type names that share version names but are not the actual MC game version.
DENY_RE = re.compile(
    r"^(Bukkit|Addons|Java|Modloader|Environment|Datapack|Snapshot|Beta|Alpha|Resource|Biome)",
    re.I,
)

LOADERS = ("Fabric", "Forge", "NeoForge", "Quilt")


def _request(req: urllib.request.Request) -> bytes:
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        body = e.read(400).decode("utf-8", "replace")
        if "<html" in body.lower():
            body = "(Cloudflare challenge page)"
        raise SystemExit(f"ERROR: HTTP {e.code} from {req.full_url}: {body}") from e
    except urllib.error.URLError as e:
        raise SystemExit(f"ERROR: network failure for {req.full_url}: {e.reason}") from e


def api_get(path: str, token: str) -> bytes:
    req = urllib.request.Request(
        f"{API_BASE}/{path}",
        headers={"X-Api-Token": token, "User-Agent": UA},
    )
    return _request(req)


def api_post(path: str, token: str, fields: dict[str, tuple[str, bytes]]) -> bytes:
    boundary = uuid.uuid4().hex
    parts = []
    for name, (content_type, payload) in fields.items():
        header = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        )
        parts.append(header.encode() + payload + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(
        f"{API_BASE}/{path}",
        data=b"".join(parts),
        method="POST",
        headers={
            "X-Api-Token": token,
            "User-Agent": UA,
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    return _request(req)


def gradle_version() -> str:
    props = (REPO_ROOT / "gradle.properties").read_text()
    m = re.search(r"^mod_version=(.+)$", props, re.M)
    if not m:
        raise SystemExit("ERROR: no mod_version in gradle.properties and no --version given")
    return m.group(1).strip()


def targets_from_versions_json() -> list[str]:
    """Java lines (from the per-line java field), MC ids, Fabric loader."""
    data = json.loads((REPO_ROOT / "versions.json").read_text())
    java = sorted({v.get("java") for v in data["versions"] if v.get("java")})
    names = [f"Java {j}" for j in java]
    names += [v["id"] for v in data["versions"]]
    names.append("Fabric")
    return names


def load_api_data(token: str) -> "tuple[dict[int, str], list[dict]]":
    """(typeId -> typeName map, game version entries). Fixture override for offline tests."""
    fixture = os.environ.get("CF_FIXTURE_DIR")
    if fixture:
        fdir = Path(fixture)
        types = json.loads((fdir / "version-types.json").read_text())
        vers = json.loads((fdir / "game-versions.json").read_text())
    else:
        types = json.loads(api_get("game/version-types", token))
        vers = json.loads(api_get("game/versions", token))
    type_names = {int(t["id"]): str(t["name"]) for t in types}
    return type_names, vers


def resolve_gameversions(
    targets: list[str], type_names: dict[int, str], vers: list[dict]
) -> list[dict]:
    """Resolve CF numeric gameVersion IDs.

    CF type names are inconsistent across lines ("Minecraft 26.1" vs "26.2" vs
    Bukkit entries sharing the same version name), so per target:
      1) drop entries whose type name looks non-version (DENY_RE)
      2) among the rest, prefer type "Minecraft <v>" or "<v>"
      3) Java targets require type "Java"; loader targets require "Modloader"
    """
    def resolve(name: str) -> dict:
        if name.startswith("Java "):
            pool = [v for v in vers if v["name"] == name
                    and type_names.get(int(v["gameVersionTypeID"])) == "Java"]
        elif name in LOADERS:
            pool = [v for v in vers if v["name"] == name
                    and type_names.get(int(v["gameVersionTypeID"])) == "Modloader"]
        else:
            pool = [v for v in vers if v["name"] == name
                    and not DENY_RE.match(type_names.get(int(v["gameVersionTypeID"]), ""))]
            pref = [v for v in pool
                    if type_names.get(int(v["gameVersionTypeID"])) in (f"Minecraft {name}", name)]
            if pref:
                pool = pref
        if len(pool) == 1:
            vid = pool[0]["id"]
            return {"name": name, "id": vid, "type": type_names.get(int(pool[0]["gameVersionTypeID"]), "?")}
        if not pool:
            raise SystemExit(f"ERROR: game version not found: {name!r}")
        detail = ", ".join(
            f"{v['id']}(type={type_names.get(int(v.get('gameVersionTypeID', 0)))})" for v in pool
        )
        raise SystemExit(f"ERROR: ambiguous game version {name!r}: {detail}")

    return [resolve(t) for t in targets]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("jar", help="path of the JAR to upload")
    ap.add_argument("--version", help="mod version (default: from gradle.properties)")
    ap.add_argument("--dry-run", action="store_true", help="print metadata, do not upload")
    ap.add_argument("--project-id", default=DEFAULT_PROJECT_ID)
    args = ap.parse_args()

    token = os.environ.get("CURSEFORGE_TOKEN")
    changelog = os.environ.get("CHANGELOG")
    if not token:
        raise SystemExit("ERROR: CURSEFORGE_TOKEN not set")
    if not changelog:
        raise SystemExit("ERROR: CHANGELOG not set")

    jar = Path(args.jar)
    if not jar.is_file():
        raise SystemExit(f"ERROR: jar not found: {jar}")
    version = args.version or gradle_version()

    targets = targets_from_versions_json()
    if len(targets) < 5:
        raise SystemExit(f"ERROR: only {len(targets)} targets derived from versions.json (expected >=5)")
    print(f"targets: {' '.join(targets)}")

    type_names, vers = load_api_data(token)
    info = resolve_gameversions(targets, type_names, vers)
    print("resolved:", json.dumps(info, separators=(",", ":")))

    metadata = {
        "displayName": f"v{version}",
        "changelog": changelog,
        "changelogType": "markdown",
        "releaseType": "release",
        "gameVersions": [v["id"] for v in info],
    }

    if args.dry_run:
        print(f"DRY RUN — would upload {jar} as:")
        print(json.dumps(metadata, indent=2))
        return

    fields = {
        "metadata": ("application/json", json.dumps(metadata).encode()),
        "file": ("application/java-archive", jar.read_bytes()),
    }
    resp = api_post(f"projects/{args.project_id}/upload-file", token, fields)
    try:
        result = json.loads(resp)
    except ValueError:
        raise SystemExit(f"ERROR: upload returned non-JSON: {resp[:400]!r}")
    print("curseforge upload OK:", json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
