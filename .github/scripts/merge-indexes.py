#!/usr/bin/env python3

import argparse
import json
import shutil
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Merge independently built extension repositories")
    parser.add_argument("--bundles", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repo_dirs = sorted(args.bundles.glob("*/repo-output"))
    if not repo_dirs:
        raise ValueError("No repository outputs were found")

    modern_template = None
    extensions = []
    legacy_extensions = []
    checksums = {}
    args.output.mkdir(parents=True, exist_ok=True)

    for repo_dir in repo_dirs:
        modern = json.loads((repo_dir / "index.json").read_text(encoding="utf-8"))
        legacy = json.loads((repo_dir / "index.min.json").read_text(encoding="utf-8"))
        metadata = {key: value for key, value in modern.items() if key != "extensionList"}
        if modern_template is None:
            modern_template = metadata
        elif modern_template != metadata:
            raise ValueError(f"Repository metadata differs in {repo_dir}")

        extensions.extend(modern["extensionList"]["extensions"])
        legacy_extensions.extend(legacy)
        checksums.update(json.loads((repo_dir / "checksums.json").read_text(encoding="utf-8")))

        for asset_dir in ("apk", "jar"):
            destination = args.output / asset_dir
            destination.mkdir(exist_ok=True)
            for asset in (repo_dir / asset_dir).iterdir():
                target = destination / asset.name
                if target.exists() and target.read_bytes() != asset.read_bytes():
                    raise ValueError(f"Conflicting asset: {asset.name}")
                shutil.copy2(asset, target)

    extensions.sort(key=lambda extension: extension["packageName"])
    legacy_extensions.sort(key=lambda extension: extension["pkg"])
    modern_template["extensionList"] = {"extensions": extensions}

    (args.output / "index.json").write_text(
        json.dumps(modern_template, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.output / "index.min.json").write_text(
        json.dumps(legacy_extensions, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    (args.output / "checksums.json").write_text(
        json.dumps(checksums, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
