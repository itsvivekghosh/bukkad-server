#!/usr/bin/env python3
"""
full-scan-guard.py — G-6 unbounded-scan guard (PRODUCTION-READINESS-AUDIT-GUIDE.md §G-6).

Fails when a repository ``.findAll()`` / ``.findAllByXxx(...)`` call-site under
``services/*/src/main`` is not covered by the platform-lib annotation
``com.bhukkad.common.scan.AllowFullScan(reason = "...")`` (V-04 defect class:
whole-table reads that grow with production traffic).

Rules
-----
* Scanned scope: ``services/*/src/main/java/**/*.java`` (tests are exempt:
  test fixtures are expected to read whole tables).
* ``findAllById(...)`` is NOT a full scan (bounded by the given ids) and is
  ignored, as are paged/sorted variants — any ``.findAll(`` call-site is
  flagged regardless of arguments, because only the annotation carries the
  reason the read is safe.
* A call-site is allowed when the annotation appears on the enclosing method
  (contiguous annotation block directly above the signature) or on the
  enclosing class. Keep ``@AllowFullScan(...)`` on a single line.
* Comments and string literals are stripped before matching, so javadoc
  mentions of ``findAll`` do not create false positives.

Usage
-----
    python3 scripts/ci/full-scan-guard.py            # from the repo root
    python3 scripts/ci/full-scan-guard.py --root services

Exit codes: 0 = clean, 1 = unannotated full-scan call-sites found (or the
scope is missing).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CALL_RE = re.compile(r"\.(findAll|findAllBy[A-Z]\w*)\s*\(")
REASON_RE = re.compile(r'@AllowFullScan\s*\(\s*reason\s*=\s*"([^"]*)"\s*\)')
# First line of a member signature: modifier-led and parenthesised, but not a
# field initialiser (no ``=`` before the first paren).
METHOD_RE = re.compile(r"^\s+(?:public|private|protected|static|final|synchronized|abstract|default)\b[^=(]*\(")
TYPE_RE = re.compile(r"^\s*(?:public|protected|private|static|final|abstract|sealed|non-sealed\s+)*"
                     r"(?:class|interface|enum|record)\s+\w+")


def strip_comments_and_strings(source: str) -> str:
    """Blank out comments and string/char literals, preserving line numbers."""
    out: list[str] = []
    i, n = 0, len(source)
    state = "code"
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                i += 2
                out.append("  ")
            elif c == "/" and nxt == "*":
                state = "block"
                i += 2
                out.append("  ")
            elif c == '"':
                state = "str"
                out.append('"')
                i += 1
            elif c == "'":
                state = "chr"
                out.append("'")
                i += 1
            else:
                out.append(c)
                i += 1
        elif state == "line":
            out.append("\n" if c == "\n" else " ")
            if c == "\n":
                state = "code"
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif state == "str":
            if c == "\\":
                out.append("  ")
                i += 2
            elif c == '"':
                state = "code"
                out.append('"')
                i += 1
            else:
                out.append(" ")
                i += 1
        else:  # chr
            if c == "\\":
                out.append("  ")
                i += 2
            elif c == "'":
                state = "code"
                out.append("'")
                i += 1
            else:
                out.append(" ")
                i += 1
    return "".join(out)


def brace_depth_before(lines: list[str]) -> list[int]:
    """depth_before[i] = brace depth at the START of line i (0 == top level)."""
    depths = [0] * len(lines)
    depth = 0
    for idx, line in enumerate(lines):
        depths[idx] = depth
        depth += line.count("{") - line.count("}")
    return depths


def annotated_above(lines: list[str], raw_lines: list[str], decl_idx: int) -> str | None:
    """Return the @AllowFullScan reason covering ``decl_idx``, if any.

    Checks the signature line itself plus the contiguous annotation block
    directly above it. Presence is matched on the comment-stripped lines;
    the reason string is read from the raw source (string literals are
    blanked during comment stripping).
    """
    for idx in (decl_idx, decl_idx - 1):
        while idx >= 0 and lines[idx].strip().startswith("@"):
            if "AllowFullScan" in lines[idx]:
                match = REASON_RE.search(raw_lines[idx])
                return match.group(1) if match else "(no reason given)"
            idx -= 1
    return None


def enclosing_member(lines: list[str], depths: list[int], site_idx: int):
    """Nearest enclosing (member, member_type) above ``site_idx``."""
    for idx in range(site_idx - 1, -1, -1):
        line = lines[idx]
        if depths[idx] >= 1 and METHOD_RE.match(line) and "=" not in line.split("(", 1)[0]:
            return idx, "method"
        if depths[idx] == 0 and TYPE_RE.match(line):
            return idx, "type"
    return None, None


def is_allowed_call(match: re.Match) -> bool:
    return match.group(1) != "findAllById"


def scan_file(path: Path, root: Path) -> tuple[list[str], list[str]]:
    """Return (violations, allowed) report lines for one Java file."""
    raw_lines = path.read_text(encoding="utf-8").split("\n")
    cleaned = strip_comments_and_strings("\n".join(raw_lines))
    lines = cleaned.split("\n")
    depths = brace_depth_before(lines)
    rel = path.relative_to(root).as_posix()

    violations: list[str] = []
    allowed: list[str] = []
    for match in CALL_RE.finditer(cleaned):
        if not is_allowed_call(match):
            continue
        site_idx = cleaned[: match.start()].count("\n")
        member_idx, member_kind = enclosing_member(lines, depths, site_idx)
        reason = None
        if member_kind == "method":
            reason = annotated_above(lines, raw_lines, member_idx)
            if reason is None:
                # Climb one more level: class-level annotations cover every
                # call-site inside the class body.
                for idx in range(member_idx - 1, -1, -1):
                    if depths[idx] == 0 and TYPE_RE.match(lines[idx]):
                        reason = annotated_above(lines, raw_lines, idx)
                        break
        elif member_kind == "type":
            reason = annotated_above(lines, raw_lines, member_idx)
        snippet = match.group(0).strip()
        if reason is None:
            violations.append(f"  {rel}:{site_idx + 1}: unbounded scan {snippet!r} has no @AllowFullScan")
        else:
            allowed.append(f"  {rel}:{site_idx + 1}: {snippet}  reason: {reason}")
    return violations, allowed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--root", default="services", help="reactor root containing the service modules")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"full-scan-guard: scope root '{args.root}' not found", file=sys.stderr)
        return 1

    main_java_dirs = sorted(root.glob("*/src/main/java"))
    if not main_java_dirs:
        print(f"full-scan-guard: no */src/main/java found under '{args.root}'", file=sys.stderr)
        return 1

    violations: list[str] = []
    allowed: list[str] = []
    files = 0
    for main_dir in main_java_dirs:
        for java_file in sorted(main_dir.rglob("*.java")):
            files += 1
            file_violations, file_allowed = scan_file(java_file, root)
            violations.extend(file_violations)
            allowed.extend(file_allowed)

    print(f"full-scan-guard: scanned {files} main-source files, "
          f"{len(allowed)} annotated full-scan site(s), {len(violations)} violation(s)")
    for line in allowed:
        print(f"allowed:{line}")
    if violations:
        for line in violations:
            print(f"VIOLATION {line}")
        print("full-scan-guard: FAIL — add @AllowFullScan(reason=\"...\") to the enclosing "
              "method (or class), or replace the whole-table read with a paged/bounded query.")
        return 1
    print("full-scan-guard: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
