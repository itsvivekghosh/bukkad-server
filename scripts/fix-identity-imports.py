#!/usr/bin/env python3
"""
Fix imports in the identity service after package restructuring.

Approach:
1. Build a class->new_package mapping from the restructured source files.
2. For each import statement of form `import com.bhukkad.identity.<old_path>.<ClassName>;`,
   look up ClassName in the mapping. If the package differs, replace the import.
3. Also fix inline FQCN references.
"""

import re
from pathlib import Path

BASE = Path("/Users/vivekghosh/Documents/vivekghosh/bhukkad/backend-server/services/identity/src")

# Step 1: Build a class->new_package mapping from all Java files
class_to_pkg = {}

for base in [BASE / "main" / "java", BASE / "test" / "java"]:
    if not base.exists():
        continue
    for java_file in base.rglob("*.java"):
        try:
            content = java_file.read_text(encoding="utf-8")
        except Exception:
            continue
        pkg_match = re.match(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
        if not pkg_match:
            continue
        package = pkg_match.group(1)

        for match in re.finditer(
            r'(?:public\s+|private\s+|protected\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+(\w+)',
            content
        ):
            class_name = match.group(1)
            class_to_pkg[class_name] = package

print(f"Found {len(class_to_pkg)} classes in restructured identity service")

# Step 2: For each class, generate old->new FQCN mappings for all possible old packages
old_packages = [
    "com.bhukkad.identity",
    "com.bhukkad.identity.domain",
    "com.bhukkad.identity.service",
    "com.bhukkad.identity.security",
    "com.bhukkad.identity.api",
    "com.bhukkad.identity.dto",
    "com.bhukkad.identity.dto.request",
    "com.bhukkad.identity.dto.response",
    "com.bhukkad.identity.referral",
    "com.bhukkad.identity.ratelimit",
    "com.bhukkad.identity.idempotency",
    "com.bhukkad.identity.cache",
    "com.bhukkad.identity.persistence",
    "com.bhukkad.identity.config",
    "com.bhukkad.identity.event",
    "com.bhukkad.identity.mapper",
]

old_to_new = {}

for cls, new_pkg in class_to_pkg.items():
    for old_pkg in old_packages:
        if old_pkg != new_pkg:
            old_fqcn = f"{old_pkg}.{cls}"
            new_fqcn = f"{new_pkg}.{cls}"
            old_to_new[old_fqcn] = new_fqcn

# Sort by old FQCN length (longest first to avoid partial matches)
sorted_mappings = sorted(old_to_new.items(), key=lambda x: len(x[0]), reverse=True)

print(f"Total old->new mappings: {len(sorted_mappings)}")

# Step 3: Apply mappings to all Java files
files_changed = 0

for base in [BASE / "main" / "java", BASE / "test" / "java"]:
    if not base.exists():
        continue
    for java_file in base.rglob("*.java"):
        try:
            content = java_file.read_text(encoding="utf-8")
        except Exception:
            continue

        original = content

        for old_fqcn, new_fqcn in sorted_mappings:
            if old_fqcn == new_fqcn:
                continue
            # Replace import statements
            content = content.replace(
                f"import {old_fqcn};",
                f"import {new_fqcn};"
            )
            # Replace inline FQCN references
            content = re.sub(
                rf'\b{re.escape(old_fqcn)}\b',
                new_fqcn,
                content
            )

        if content != original:
            java_file.write_text(content, encoding="utf-8")
            files_changed += 1

print(f"Total files updated: {files_changed}")
