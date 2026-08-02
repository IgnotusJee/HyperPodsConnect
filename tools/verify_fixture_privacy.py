#!/usr/bin/env python3
"""Fail when committed protocol fixtures contain sensitive or raw capture material."""

from __future__ import annotations

import re
import sys
from pathlib import Path


FIXTURE_ROOT = Path(__file__).resolve().parents[1] / "testdata" / "fixtures"
ALLOWED_FAKE_MACS = {"02:00:00:00:00:01"}
FORBIDDEN_BINARY_SUFFIXES = {
    ".bin",
    ".img",
    ".fw",
    ".pcap",
    ".pcapng",
    ".btsnoop",
}
MAC_PATTERN = re.compile(r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
SECRET_ASSIGNMENT_PATTERNS = {
    "ADB serial": re.compile(r"(?im)\badb[ _-]?serial\s*[:=]\s*(?!redacted\b|none\b|n/?a\b|<)[^\s]+"),
    "LinkKey": re.compile(r"(?im)\blink[ _-]?key\s*[:=]\s*(?!redacted\b|none\b|n/?a\b|<)[^\s]+"),
    "LTK": re.compile(r"(?im)\bltk\s*[:=]\s*(?!redacted\b|none\b|n/?a\b|<)[^\s]+"),
    "IRK": re.compile(r"(?im)\birk\s*[:=]\s*(?!redacted\b|none\b|n/?a\b|<)[^\s]+"),
    "account token": re.compile(
        r"(?im)\b(?:account[ _-]?)?token\s*[:=]\s*(?!redacted\b|none\b|n/?a\b|<)[^\s]+",
    ),
}


def verify() -> list[str]:
    problems: list[str] = []
    if not FIXTURE_ROOT.is_dir():
        return [f"fixture root is missing: {FIXTURE_ROOT}"]

    for path in sorted(item for item in FIXTURE_ROOT.rglob("*") if item.is_file()):
        relative = path.relative_to(FIXTURE_ROOT)
        if path.suffix.lower() in FORBIDDEN_BINARY_SUFFIXES:
            problems.append(f"{relative}: forbidden raw capture/firmware binary extension")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            problems.append(f"{relative}: fixture is not UTF-8 text")
            continue

        for match in MAC_PATTERN.finditer(text):
            value = match.group(0).upper()
            if value not in ALLOWED_FAKE_MACS:
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{relative}:{line}: real-looking Bluetooth MAC {value}")
        for label, pattern in SECRET_ASSIGNMENT_PATTERNS.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{relative}:{line}: populated {label} field")

    return problems


def main() -> int:
    problems = verify()
    if problems:
        print("Fixture privacy verification failed:", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    print("Fixture privacy verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
