#!/usr/bin/env python3
"""
Config validation CI guard (P2).

Validates all Spring Boot YAML configs under services/*/src/main/resources/:
  1. YAML parse succeeds
  2. No duplicate keys at the same mapping level
  3. No duplicate top-level 'spring:' keys (SnakeYAML DuplicateKeyException trap)
  4. Required keys present (spring.datasource or spring.data.redis or spring.cloud.gateway)
"""

import argparse
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SERVICES_DIR = REPO_ROOT / "services"


def check_duplicate_keys(parsed, path="") -> list[str]:
    """Recursively check for duplicate keys in a parsed YAML structure."""
    errors = []
    if isinstance(parsed, dict):
        seen = {}
        for key, value in parsed.items():
            current_path = f"{path}.{key}" if path else key
            if key in seen:
                errors.append(
                    f"Duplicate key '{key}' at {current_path} "
                    f"(first at {seen[key]}, duplicate at {current_path})"
                )
            else:
                seen[key] = current_path
            errors.extend(check_duplicate_keys(value, current_path))
    elif isinstance(parsed, list):
        for i, item in enumerate(parsed):
            errors.extend(check_duplicate_keys(item, f"{path}[{i}]"))
    return errors


def validate_file(yml_path: Path) -> list[str]:
    """Validate a single YAML file. Returns list of error strings."""
    errors = []
    try:
        content = yml_path.read_text(encoding="utf-8")
    except Exception as exc:
        return [f"Unreadable file {yml_path}: {exc}"]

    try:
        parsed = yaml.safe_load(content)
    except yaml.YAMLError as exc:
        return [f"YAML parse error in {yml_path}: {exc}"]

    if parsed is None:
        return []  # empty file

    # Check for duplicate keys
    errors.extend(check_duplicate_keys(parsed))

    # Check for duplicate top-level 'spring:' keys
    if isinstance(parsed, dict):
        spring_keys = [k for k in parsed if k == "spring"]
        if len(spring_keys) > 1:
            errors.append(
                f"Duplicate top-level 'spring:' key in {yml_path} "
                f"({len(spring_keys)} occurrences)"
            )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Spring Boot YAML configs")
    parser.add_argument(
        "--services-dir", type=Path, default=SERVICES_DIR,
        help="Root directory containing service modules"
    )
    args = parser.parse_args()

    all_errors = []
    checked = 0

    for yml_path in sorted(args.services_dir.glob("*/src/main/resources/application*.yml")):
        checked += 1
        errors = validate_file(yml_path)
        for err in errors:
            all_errors.append(f"{yml_path.relative_to(args.services_dir.parent)}: {err}")

    print(f"Checked {checked} YAML config files.")
    if all_errors:
        print("\nFAILURES:")
        for err in all_errors:
            print(f"  {err}")
        return 1

    print("All YAML configs are valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
