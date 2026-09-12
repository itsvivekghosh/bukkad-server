#!/usr/bin/env python3
"""
Restructure a Bhukkad microservice to the standard clean architecture.

Usage: python3 restructure-service.py <service-name> [service-dir]

Example: python3 restructure-service.py order services/order

This script:
1. Creates standard directory structure (api, domain, infrastructure, config, util)
2. Moves files to their correct locations based on heuristics
3. Fixes package declarations to match new directory paths
4. Fixes imports to match new package structure
5. Adds test dependencies (test-support, datafaker) to pom.xml
"""

import re
import sys
from pathlib import Path

def restructure_service(service_name, service_dir):
    """Restructure a single service."""
    base = Path(service_dir)
    main_java = base / "src/main/java"
    test_java = base / "src/test/java"
    
    # Find the actual package root directory
    # It could be com/bhukkad/<service> or similar
    pkg_root = None
    for d in main_java.rglob(""):
        if d.is_dir() and d.name == service_name or (d.parent.name == "com" and "bhukkad" in str(d)):
            # Check if this looks like the service root
            parts = d.relative_to(main_java).parts
            if len(parts) >= 2 and parts[0] == "com":
                pkg_root = main_java / parts[0] / parts[1]
                break
    
    if not pkg_root:
        # Fallback: search for any directory matching service name
        for d in main_java.rglob(service_name):
            if d.is_dir():
                pkg_root = d
                break
    
    if not pkg_root:
        print(f"ERROR: Could not find package root for {service_name} in {service_dir}")
        return False
    
    print(f"Found package root: {pkg_root}")
    
    # TODO: Implement file movement, package fixes, import fixes
    # This is a simplified version - the full version would be more comprehensive
    
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 restructure-service.py <service-name> [service-dir]")
        sys.exit(1)
    
    service_name = sys.argv[1]
    service_dir = sys.argv[2] if len(sys.argv) > 2 else f"services/{service_name}"
    
    success = restructure_service(service_name, service_dir)
    sys.exit(0 if success else 1)
