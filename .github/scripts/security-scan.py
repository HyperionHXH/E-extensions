#!/usr/bin/env python3
"""Fail when tracked text files contain common credential formats."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


PATTERNS = {
    "Google API key": rb"AIza[0-9A-Za-z_-]{35}",
    "Google OAuth client secret": rb"GOCSPX-[0-9A-Za-z_-]{20,}",
    "GitHub token": rb"gh[pousr]_[0-9A-Za-z]{36,255}",
    "GitHub fine-grained token": rb"github_pat_[0-9A-Za-z_]{20,255}",
    "AWS access key": rb"(?:AKIA|ASIA)[0-9A-Z]{16}",
    "Slack token": rb"xox[baprs]-[0-9A-Za-z-]{10,255}",
    "Stripe live secret": rb"sk_live_[0-9A-Za-z]{16,}",
    "private key": rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
    "assigned credential": rb"(?i)(?:api[_-]?key|client[_-]?secret|access[_-]?token|auth[_-]?token|password)\s*[:=]\s*[\"'][0-9A-Za-z_./+=-]{16,}[\"']",
}

MAX_FILE_SIZE = 10 * 1024 * 1024


def tracked_files() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [Path(item.decode("utf-8")) for item in output.split(b"\0") if item]


def main() -> int:
    self_path = Path(__file__).resolve()
    findings: list[tuple[Path, int, str]] = []

    for path in tracked_files():
        if path.resolve() == self_path or not path.is_file() or path.stat().st_size > MAX_FILE_SIZE:
            continue

        data = path.read_bytes()
        if b"\0" in data[:8192]:
            continue

        for detector, pattern in PATTERNS.items():
            for match in re.finditer(pattern, data):
                line = data.count(b"\n", 0, match.start()) + 1
                findings.append((path, line, detector))

    if findings:
        for path, line, detector in findings:
            print(f"{path}:{line}: potential {detector}")
        print(f"credential scan failed with {len(findings)} finding(s)", file=sys.stderr)
        return 1

    print("credential scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
