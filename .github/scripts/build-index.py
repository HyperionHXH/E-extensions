#!/usr/bin/env python3

import argparse
import hashlib
import json
import shutil
from pathlib import Path


CONTENT_WARNINGS = {
    1: "CONTENT_WARNING_SAFE",
    2: "CONTENT_WARNING_MIXED",
    3: "CONTENT_WARNING_NSFW",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build Mihon and Suwayomi repository indexes")
    parser.add_argument("--source-info", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--signing-key", required=True, help="Lowercase SHA-256 signing certificate digest")
    parser.add_argument("--apk-base-url", required=True)
    parser.add_argument("--jar-base-url", required=True)
    parser.add_argument("--icon-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    args = parse_args()
    info = json.loads(args.source_info.read_text(encoding="utf-8"))
    signing_key = args.signing_key.replace(":", "").lower()
    if len(signing_key) != 64 or any(char not in "0123456789abcdef" for char in signing_key):
        raise ValueError("Signing key must be a SHA-256 certificate digest")

    args.output.mkdir(parents=True, exist_ok=True)
    apk_dir = args.output / "apk"
    jar_dir = args.output / "jar"
    apk_dir.mkdir(exist_ok=True)
    jar_dir.mkdir(exist_ok=True)
    shutil.copy2(args.apk, apk_dir / args.apk.name)
    shutil.copy2(args.jar, jar_dir / args.jar.name)

    sources = [
        {
            "id": str(source["id"]),
            "name": source["name"],
            "language": source["lang"],
            "homeUrl": source["baseUrl"],
            **({"mirrorUrls": source["mirrorUrls"]} if source.get("mirrorUrls") else {}),
        }
        for source in info["sources"]
    ]
    extension = {
        "name": info["name"],
        "packageName": info["packageName"],
        "resources": {
            "apkUrl": f"{args.apk_base_url}/{args.apk.name}",
            "iconUrl": args.icon_url,
            "jarUrl": f"{args.jar_base_url}/{args.jar.name}",
        },
        "extensionLib": info["extensionLib"],
        "versionCode": str(info["versionCode"]),
        "versionName": info["versionName"],
        "contentWarning": CONTENT_WARNINGS[info["contentWarning"]],
        "sources": sources,
    }
    modern_index = {
        "name": "E-extensions",
        "badgeLabel": "E-EXT",
        "signingKey": signing_key,
        "contact": {"website": "https://github.com/HyperionHXH/E-extensions"},
        "extensionList": {"extensions": [extension]},
    }

    legacy_sources = [
        {
            "name": source["name"],
            "lang": source["lang"],
            "id": str(source["id"]),
            "baseUrl": source["baseUrl"],
        }
        for source in info["sources"]
    ]
    legacy_index = [{
        "name": info["name"],
        "pkg": info["packageName"],
        "apk": args.apk.name,
        "lang": info["sources"][0]["lang"],
        "code": info["versionCode"],
        "version": info["versionName"],
        "nsfw": 1 if info["contentWarning"] == 3 else 0,
        "sources": legacy_sources,
    }]

    (args.output / "index.json").write_text(
        json.dumps(modern_index, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.output / "index.min.json").write_text(
        json.dumps(legacy_index, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    checksums = {
        f"apk/{args.apk.name}": sha256(args.apk),
        f"jar/{args.jar.name}": sha256(args.jar),
    }
    (args.output / "checksums.json").write_text(
        json.dumps(checksums, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
