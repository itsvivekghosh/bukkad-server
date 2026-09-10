#!/usr/bin/env python3
"""G-13 duplicated-artifact guard: ban hand-built HTTP clients outside the
platform factory (audit P-05/PERF-5).

Fails when any services/*/src/main Java file contains:

  * ``WebClient.builder()``        — must go through
    com.bhukkad.common.web.client.PlatformWebClientBuilderFactory /
    WebClientConfig (platform-lib web/client package is exempt).
  * ``new RestTemplate(`` / ``new SimpleClientHttpRequestFactory(`` — must go
    through a managed bean/factory; hand-rolled request factories hide
    timeouts from the platform defaults.

Lines listed in scripts/ci/webclient-allowlist.txt (``path:lineno: pattern``
or just ``path`` to exempt the whole file) are tolerated so sibling-batch
migrations can merge independently; each allowlist entry is expected to
disappear once its owning batch migrates the file.

Exit code 0 = clean (or only allowlisted hits), 1 = violation (block merge),
2 = allowlist file unreadable/malformed.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICES_MAIN = REPO_ROOT / "services"
ALLOWLIST = REPO_ROOT / "scripts" / "ci" / "webclient-allowlist.txt"

# Files matching these paths are the sanctioned construction points.
EXEMPT_PATH_PARTS = (
    "com/bhukkad/common/web/client/",  # platform-lib factory package (P-05)
)

PATTERNS = {
    "WebClient.builder()": re.compile(r"WebClient\.builder\(\)"),
    "new RestTemplate(...)": re.compile(r"new\s+RestTemplate\s*\("),
    "new SimpleClientHttpRequestFactory(...)": re.compile(
        r"new\s+SimpleClientHttpRequestFactory\s*\("),
}


def load_allowlist():
    """Returns (whole_file_exemptions, line_exemptions) from the allowlist.

    Line entries use ``path:lineno`` (1-based, matching the offending file) or
    ``path:lineno:pattern`` to also pin which detector matched.
    """
    whole, lines = set(), set()
    if not ALLOWLIST.exists():
        return whole, lines
    for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        entry = raw.split("#", 1)[0].strip()
        if not entry:
            continue
        parts = entry.split(":")
        if len(parts) == 1:
            whole.add(parts[0])
        elif len(parts) in (2, 3) and parts[1].isdigit():
            lines.add((parts[0], int(parts[1])))
        else:
            print(f"G-13 allowlist malformed entry: {raw!r}", file=sys.stderr)
            sys.exit(2)
    return whole, lines


def main() -> int:
    whole_exempt, line_exempt = load_allowlist()
    violations = []

    for java_file in sorted(SERVICES_MAIN.glob("*/src/main/java/**/*.java")):
        rel = java_file.relative_to(REPO_ROOT).as_posix()
        if any(part in rel for part in EXEMPT_PATH_PARTS):
            continue
        if rel in whole_exempt:
            continue
        try:
            content = java_file.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            print(f"G-13 unreadable file {rel}: {exc}", file=sys.stderr)
            return 2
        for lineno, line in enumerate(content.splitlines(), start=1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line) and (rel, lineno) not in line_exempt:
                    violations.append(f"{rel}:{lineno}: {label}")

    if violations:
        print("G-13 violations — hand-built HTTP clients outside the "
              "platform factory (migrate to "
              "com.bhukkad.common.web.client.PlatformWebClientBuilderFactory, "
              "or add a temporary scripts/ci/webclient-allowlist.txt entry):")
        for v in violations:
            print(f"  {v}")
        return 1

    print("G-13 OK — no hand-built HTTP clients outside the platform factory "
          "outside the allowlist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
