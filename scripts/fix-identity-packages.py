#!/usr/bin/env python3
"""
Fix package declarations and imports in the identity service.

For each Java file:
1. Compute the expected package from its directory path
2. Update the package declaration if it doesn't match
3. Build a class->package mapping from all files
4. Fix all imports that reference identity classes
"""

import re
from pathlib import Path

BASE = Path("/Users/vivekghosh/Documents/vivekghosh/bhukkad/backend-server/services/identity/src")

# Step 1: Fix package declarations based on directory paths
files_with_pkg_changes = 0

for java_file in BASE.rglob("*.java"):
    try:
        content = java_file.read_text(encoding="utf-8")
    except Exception:
        continue

    # Compute expected package from directory path
    rel_path = java_file.relative_to(BASE)
    # Strip 'main/java/' or 'test/java/' prefix and filename
    parts = list(rel_path.parts)
    
    # Remove 'src/main/java' or 'src/test/java' - parts are [src, main/java, ...] or similar
    # Actually relative_to(BASE) gives us paths like:
    # main/java/com/bhukkad/identity/config/SecurityConfig.java
    # test/java/com/bhukkad/identity/unit/service/MembershipServiceTest.java
    # So parts[0] is 'main' or 'test', parts[1] is 'java'
    
    if len(parts) >= 3 and parts[0] in ("main", "test") and parts[1] == "java":
        pkg_parts = parts[2:-1]  # remove 'main'/'test', 'java', and filename
        expected_pkg = ".".join(pkg_parts)
    else:
        continue
    
    # Find current package declaration
    pkg_match = re.search(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
    if not pkg_match:
        continue
    
    current_pkg = pkg_match.group(1)
    
    if current_pkg != expected_pkg:
        content = content.replace(
            f"package {current_pkg};",
            f"package {expected_pkg};",
            1  # only replace first occurrence
        )
        java_file.write_text(content, encoding="utf-8")
        files_with_pkg_changes += 1
        print(f"Fixed: {java_file.relative_to(BASE.parent)}")
        print(f"  {current_pkg} -> {expected_pkg}")

print(f"\nTotal files with package fixes: {files_with_pkg_changes}")
