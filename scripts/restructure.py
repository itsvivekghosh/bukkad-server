#!/usr/bin/env python3
"""
Restructure Bhukkad services:
1. Rename entity → domain (search, survey, referral, supportticket, growth)
2. Merge serviceImpl → service (search, survey, referral, supportticket, realtime, personalization, growth)
3. Remove wallet-old.bak (supportticket)
4. Fix test serviceImpl → service (referral, supportticket, realtime, personalization, growth)
5. Update all package declarations and imports
"""

import os
import re
import subprocess
import shutil
from pathlib import Path

BASE = Path("/Users/vivekghosh/Documents/vivekghosh/bhukkad/backend-server/services")

# Services that need entity → domain rename
ENTITY_TO_DOMAIN = ["search", "survey", "referral", "supportticket", "growth"]

# Services that need serviceImpl → service merge (main)
SERVICEIMPL_TO_SERVICE_MAIN = [
    "search", "survey", "referral", "supportticket",
    "realtime", "personalization", "growth"
]

# Services that need test serviceImpl → service merge
SERVICEIMPL_TO_SERVICE_TEST = [
    "referral", "supportticket", "realtime", "personalization", "growth"
]

def run(cmd, cwd=None):
    """Run a command and return output."""
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=cwd)
    if result.returncode != 0 and "rm" not in cmd.split()[0]:
        print(f"  WARN: {cmd}")
        print(f"    stderr: {result.stderr[:200]}")
    return result

def update_package_declarations(base_dir, old_pkg, new_pkg):
    """Update package declarations and imports in all Java files."""
    old_pkg_dot = old_pkg.replace("/", ".")
    new_pkg_dot = new_pkg.replace("/", ".")

    for java_file in base_dir.rglob("*.java"):
        try:
            content = java_file.read_text(encoding="utf-8")
        except Exception:
            continue

        original = content

        # Update package declaration (must be first non-comment, non-blank line)
        content = re.sub(
            r'^package\s+' + re.escape(old_pkg_dot) + r'\s*;',
            f'package {new_pkg_dot};',
            content,
            flags=re.MULTILINE
        )

        # Update imports
        content = re.sub(
            r'^import\s+' + re.escape(old_pkg_dot) + r'\.',
            f'import {new_pkg_dot}.',
            content,
            flags=re.MULTILINE
        )

        # Update fully qualified references in annotations, @Autowired etc.
        # (e.g., @Autowired com.bhukkad.search.entity.Foo -> com.bhukkad.search.domain.Foo)
        content = content.replace(old_pkg_dot + ".", new_pkg_dot + ".")

        if content != original:
            java_file.write_text(content, encoding="utf-8")
            return True
    return False

def git_mv(src, dst):
    """Git move a file or directory."""
    if not src.exists():
        return False
    src.parent.mkdir(parents=True, exist_ok=True)
    dst.parent.mkdir(parents=True, exist_ok=True)
    run(f"git mv -f '{src}' '{dst}'")
    return True

def main():
    print("=" * 60)
    print("BHUKKAD SERVICE RESTRUCTURING")
    print("=" * 60)

    # 1. Remove wallet-old.bak
    print("\n[1/5] Removing wallet-old.bak...")
    bak_dir = BASE / "supportticket" / "src" / "main" / "java" / "com" / "bhukkad" / "support" / "wallet-old.bak"
    if bak_dir.exists():
        # Use git rm for tracked files
        for f in bak_dir.rglob("*"):
            if f.is_file():
                run(f"git rm -f '{f}'")
        run(f"git rm -rf '{bak_dir}'")
        print(f"  Removed {bak_dir}")

    # 2. Rename entity → domain
    print("\n[2/5] Renaming entity → domain...")
    for svc in ENTITY_TO_DOMAIN:
        entity_dir = BASE / svc / "src" / "main" / "java" / "com" / "bhukkad" / svc / "entity"
        domain_dir = BASE / svc / "src" / "main" / "java" / "com" / "bhukkad" / svc / "domain"

        if not entity_dir.exists():
            print(f"  {svc}: entity dir not found, skipping")
            continue

        if domain_dir.exists():
            print(f"  {svc}: domain dir already exists, merging entity into domain")
            # Move all files from entity to domain
            for f in entity_dir.rglob("*"):
                if f.is_file():
                    rel = f.relative_to(entity_dir)
                    dst = domain_dir / rel
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    git_mv(f, dst)
            # Move subdirectories too
            for d in entity_dir.rglob("*"):
                if d.is_dir() and d != entity_dir:
                    rel = d.relative_to(entity_dir)
                    dst = domain_dir / rel
                    dst.mkdir(parents=True, exist_ok=True)
                    for f in d.rglob("*"):
                        if f.is_file():
                            dst_f = dst / f.name
                            git_mv(f, dst_f)
            run(f"rmdir '{entity_dir}' 2>/dev/null || true")
        else:
            git_mv(entity_dir, domain_dir)

        # Update package declarations and imports
        svc_base = BASE / svc / "src" / "main" / "java"
        changed = update_package_declarations(svc_base, f"com/bhukkad/{svc}/entity", f"com/bhukkad/{svc}/domain")
        if changed:
            print(f"  {svc}: updated package declarations (entity→domain)")
        else:
            print(f"  {svc}: no package updates needed")

    # 3. Merge serviceImpl → service (main source)
    print("\n[3/5] Merging serviceImpl → service (main)...")
    for svc in SERVICEIMPL_TO_SERVICE_MAIN:
        impl_dir = BASE / svc / "src" / "main" / "java" / "com" / "bhukkad" / svc / "serviceImpl"
        service_dir = BASE / svc / "src" / "main" / "java" / "com" / "bhukkad" / svc / "service"

        if not impl_dir.exists():
            print(f"  {svc}: serviceImpl dir not found, skipping")
            continue

        if not service_dir.exists():
            git_mv(impl_dir, service_dir)
            print(f"  {svc}: moved serviceImpl → service")
        else:
            # Merge: move all files from serviceImpl into service
            for f in impl_dir.rglob("*"):
                if f.is_file():
                    rel = f.relative_to(impl_dir)
                    dst = service_dir / rel
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    git_mv(f, dst)
            run(f"rmdir '{impl_dir}' 2>/dev/null || true")
            print(f"  {svc}: merged serviceImpl into service")

        # Update package declarations
        svc_base = BASE / svc / "src" / "main" / "java"
        changed = update_package_declarations(svc_base, f"com/bhukkad/{svc}/serviceImpl", f"com/bhukkad/{svc}/service")
        if changed:
            print(f"  {svc}: updated package declarations (serviceImpl→service)")

    # 4. Fix test serviceImpl → service
    print("\n[4/5] Fixing test serviceImpl → service...")
    for svc in SERVICEIMPL_TO_SERVICE_TEST:
        impl_dir = BASE / svc / "src" / "test" / "java" / "com" / "bhukkad" / svc / "serviceImpl"
        service_dir = BASE / svc / "src" / "test" / "java" / "com" / "bhukkad" / svc / "service"

        if not impl_dir.exists():
            print(f"  {svc}: test serviceImpl dir not found, skipping")
            continue

        if not service_dir.exists():
            git_mv(impl_dir, service_dir)
            print(f"  {svc}: moved test serviceImpl → service")
        else:
            for f in impl_dir.rglob("*"):
                if f.is_file():
                    rel = f.relative_to(impl_dir)
                    dst = service_dir / rel
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    git_mv(f, dst)
            run(f"rmdir '{impl_dir}' 2>/dev/null || true")
            print(f"  {svc}: merged test serviceImpl into service")

        # Update package declarations in test files
        svc_base = BASE / svc / "src" / "test" / "java"
        changed = update_package_declarations(svc_base, f"com/bhukkad/{svc}/serviceImpl", f"com/bhukkad/{svc}/service")
        if changed:
            print(f"  {svc}: updated test package declarations (serviceImpl→service)")

    # 5. Also update any remaining references in ALL services
    # (e.g., if a file in service A imports from service B's entity package)
    print("\n[5/5] Updating cross-service references...")
    for svc in ENTITY_TO_DOMAIN:
        old_import = f"import com.bhukkad.{svc}.entity"
        new_import = f"import com.bhukkad.{svc}.domain"
        for java_file in BASE.rglob("*.java"):
            try:
                content = java_file.read_text(encoding="utf-8")
            except Exception:
                continue
            if old_import in content:
                content = content.replace(old_import, new_import)
                # Also update FQCN references
                content = content.replace(f"com.bhukkad.{svc}.entity.", f"com.bhukkad.{svc}.domain.")
                java_file.write_text(content, encoding="utf-8")
                print(f"  Updated {java_file.relative_to(BASE)}")

    print("\n" + "=" * 60)
    print("RESTRUCTURING COMPLETE")
    print("=" * 60)
    print("\nNext steps:")
    print("  1. Review git diff")
    print("  2. Run compilation: ./mvnw -f services/pom.xml compile")
    print("  3. Run tests: ./mvnw -f services/pom.xml test")

if __name__ == "__main__":
    main()
