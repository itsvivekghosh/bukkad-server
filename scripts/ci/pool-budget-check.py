#!/usr/bin/env python3
"""
Pool budget CI guard (Phase 9 / Phase 5).

Reads the actual k8s HPA + pgbouncer config + per-service application-prod.yml
and validates:
  per-DB: hpa_max × hikari_pool ≤ 0.8 × pgbouncer_pool
  global:  max_client_conn ≥ Σ(pgbouncer_pool)
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
K8S_DIR = REPO_ROOT / "k8s"
SERVICES_DIR = REPO_ROOT / "services"

BUDGET_FACTOR = 0.8


def parse_hpa_max(hpa_path: Path) -> int:
    content = hpa_path.read_text()
    m = re.search(r"maxReplicas:\s*(\d+)", content)
    if not m:
        raise ValueError(f"maxReplicas not found in {hpa_path}")
    return int(m.group(1))


def parse_pgbouncer_pool(pgbouncer_path: Path, db_name: str) -> int:
    content = pgbouncer_path.read_text()
    # Try exact match first, then common pluralization (order→orders, etc.)
    for candidate in [db_name, db_name.rstrip("e") + "ies" if db_name.endswith("e") else db_name + "s"]:
        pattern = rf"^\s*{candidate}\b.*pool_size=(\d+)"
        m = re.search(pattern, content, re.MULTILINE | re.IGNORECASE)
        if m:
            return int(m.group(1))
    raise ValueError(f"pool_size for {db_name} not found in {pgbouncer_path}")


def parse_max_client_conn(pgbouncer_path: Path) -> int:
    content = pgbouncer_path.read_text()
    m = re.search(r"max_client_conn\s*=\s*(\d+)", content)
    if not m:
        raise ValueError(f"max_client_conn not found in {pgbouncer_path}")
    return int(m.group(1))


def parse_hikari_pool(service_dir: Path) -> int:
    # Look for application-prod.yml in the service
    prod_yml = service_dir / "src" / "main" / "resources" / "application-prod.yml"
    if not prod_yml.exists():
        # Fallback: no prod yml, use default
        return 20
    content = prod_yml.read_text()
    # Match spring.datasource.hikari.maximum-pool-size or similar
    m = re.search(
        r"spring\.datasource\.hikari\.maximum-pool-size:\s*(\d+)", content
    )
    if not m:
        return 20  # default
    return int(m.group(1))


def discover_services() -> list[tuple[str, Path, Path]]:
    """Return list of (service_name, service_dir, hpa_path)."""
    results = []
    for hpa_path in sorted(K8S_DIR.glob("*/hpa.yaml")):
        service_name = hpa_path.parent.name
        service_dir = SERVICES_DIR / service_name
        if service_dir.exists():
            results.append((service_name, service_dir, hpa_path))
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate pgbouncer connection budget")
    parser.add_argument(
        "--budget-factor", type=float, default=BUDGET_FACTOR,
        help="Max fraction of pgbouncer pool per DB (default: 0.8)"
    )
    args = parser.parse_args()

    pgbouncer_path = K8S_DIR / "pgbouncer" / "configmap.yaml"
    if not pgbouncer_path.exists():
        print(f"ERROR: pgbouncer config not found at {pgbouncer_path}", file=sys.stderr)
        return 2

    max_client_conn = parse_max_client_conn(pgbouncer_path)
    budget_factor = args.budget_factor

    total_pgbouncer_pool = 0
    failures = []

    for service_name, service_dir, hpa_path in discover_services():
        try:
            hpa_max = parse_hpa_max(hpa_path)
            try:
                pgbouncer_pool = parse_pgbouncer_pool(pgbouncer_path, service_name)
            except ValueError:
                # Services without a pgbouncer pool (e.g. gateway) are skipped
                # — they have no datasource, so no DB connection budget.
                continue
            hikari_pool = parse_hikari_pool(service_dir)
        except ValueError as exc:
            failures.append(f"FAIL: {service_name}: {exc}")
            continue

        demand = hpa_max * hikari_pool
        budget = int(budget_factor * pgbouncer_pool)
        total_pgbouncer_pool += pgbouncer_pool

        if demand > budget:
            failures.append(
                f"FAIL: {service_name}: demand={demand} > budget={budget} "
                f"(hpa_max={hpa_max} × hikari_pool={hikari_pool} > "
                f"{budget_factor} × pgbouncer_pool={pgbouncer_pool})"
            )
        else:
            print(
                f"OK: {service_name}: demand={demand} ≤ budget={budget} "
                f"(hpa_max={hpa_max} × hikari_pool={hikari_pool}, "
                f"pgbouncer_pool={pgbouncer_pool})"
            )

    if total_pgbouncer_pool > max_client_conn:
        failures.append(
            f"FAIL: total_pgbouncer_pool={total_pgbouncer_pool} > "
            f"max_client_conn={max_client_conn}"
        )
    else:
        print(
            f"OK: total_pgbouncer_pool={total_pgbouncer_pool} ≤ "
            f"max_client_conn={max_client_conn}"
        )

    if failures:
        print("\nFAILURES:")
        for f in failures:
            print(f"  {f}")
        return 1

    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
