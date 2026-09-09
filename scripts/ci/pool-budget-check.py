#!/usr/bin/env python3
"""PERF-0 connection-pool budget guard (perf-guide §4.2 arithmetic).

Fails when, for any PostgreSQL database,

    SUM(hpa_max_replicas x hikari maximum-pool-size over services on that DB)
        > 0.8 x min(pgbouncer per-DB pool_size, PG max_connections)

Inputs parsed from the tree (no cluster access, CI-safe):
  * services/*/src/main/resources/application*.yml  — Hikari maximum-pool-size
    after base -> profile layering (prod overlay wins; the ${ENV:default}
    placeholder default is the repo-declared production value).
  * k8s/*/hpa.yaml                                  — spec.maxReplicas per
    service; services without an HPA fall back to the Deployment's .spec
    .replicas, then to 1 (never silently ignored).
  * k8s/pgbouncer/configmap.yaml                    — [databases] per-DB
    pool_size entries + default_pool_size.

Exit code 0 = budget holds, 1 = violation (block merge), 2 = inputs unreadable.
"""

import configparser
import io
import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICES_DIR = REPO_ROOT / "services"
K8S_DIR = REPO_ROOT / "k8s"
PGBOUNCER_CONFIGMAP = K8S_DIR / "pgbouncer" / "configmap.yaml"

# §4.2 documents the PG primary budget as max_connections ~= 320.
PG_MAX_CONNECTIONS = 320
BUDGET_NUM, BUDGET_DEN = 4, 5  # demand must satisfy demand*BUDGET_DEN <= BUDGET_NUM*ceiling

PLACEHOLDER_RE = re.compile(r"^\$\{[A-Za-z0-9_]+:(-?\d+)\}$")

# Only base + prod layers define the production budget: the dev/local profiles
# point at throwaway local URLs (e.g. jdbc:postgresql://localhost:5432/_db) and
# tiny pools that must never be attributed to a real database.
PROFILE_ORDER = ["application.yml", "application-prod.yml"]


def load_yaml_docs(path):
    docs = [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d]
    merged = {}
    for doc in docs:
        deep_merge(merged, doc)
    return merged


def deep_merge(dst, src):
    for key, value in src.items():
        if isinstance(value, dict) and isinstance(dst.get(key), dict):
            deep_merge(dst[key], value)
        else:
            dst[key] = value
    return dst


def dig(tree, dotted):
    node = tree
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def as_pool_size(raw, where):
    if raw is None:
        return None
    text = str(raw).strip()
    if text.isdigit():
        return int(text)
    match = PLACEHOLDER_RE.match(text)
    if match:
        return int(match.group(1))
    sys.stderr.write(f"pool-budget-check: cannot interpret pool value {text!r} at {where}\n")
    sys.exit(2)


PLACEHOLDER_DEFAULT_RE = re.compile(r"\$\{[A-Za-z0-9_]+:([^}]*)\}")


def db_name_from_jdbc(url, where):
    """Extract the logical database name from a jdbc:postgresql URL.

    Spring ``${VAR:default}`` placeholders are resolved to their (repo-declared)
    defaults first, e.g. ``.../5432/${POSTGRES_DB:realtime}`` -> ``realtime``.
    """
    text = PLACEHOLDER_DEFAULT_RE.sub(r"\1", str(url or ""))
    match = re.search(r"jdbc:postgresql://[^/]+/([^/?;#]+)", text)
    if match:
        return match.group(1)
    sys.stderr.write(f"pool-budget-check: cannot derive database from url {text!r} at {where}\n")
    sys.exit(2)


def collect_services():
    """{service: {db, pool}} for every service module with a datasource."""
    services = {}
    for svc_dir in sorted(SERVICES_DIR.iterdir()):
        resources = svc_dir / "src" / "main" / "resources"
        base = resources / "application.yml"
        if not svc_dir.is_dir() or not base.exists():
            continue
        merged = {}
        for profile in PROFILE_ORDER:
            path = resources / profile
            if path.exists():
                deep_merge(merged, load_yaml_docs(path))
        pool = as_pool_size(dig(merged, "spring.datasource.hikari.maximum-pool-size"),
                            f"{svc_dir.name} hikari pool")
        url = dig(merged, "spring.datasource.url") or dig(merged, "spring.r2dbc.url")
        if pool is None and url is None:
            continue  # DB-less service (e.g. gateway) — nothing to budget
        if pool is None or url is None:
            sys.stderr.write(f"pool-budget-check: {svc_dir.name} has datasource url but no "
                             "hikari.maximum-pool-size (or vice versa)\n")
            sys.exit(2)
        services[svc_dir.name] = {"db": db_name_from_jdbc(url, svc_dir.name), "pool": pool}
    return services


def collect_max_replicas():
    """{service: (max_replicas, source)} from k8s/<svc>/hpa.yaml (or Deployment)."""
    replicas = {}
    for svc_dir in sorted(K8S_DIR.iterdir()):
        if not svc_dir.is_dir():
            continue
        hpa = svc_dir / "hpa.yaml"
        if hpa.exists():
            spec = load_yaml_docs(hpa).get("spec", {})
            if "maxReplicas" in spec:
                replicas[svc_dir.name] = (int(spec["maxReplicas"]), "hpa")
                continue
        deployment = svc_dir / "deployment.yaml"
        if deployment.exists():
            for doc in yaml.safe_load_all(deployment.read_text(encoding="utf-8")):
                if isinstance(doc, dict) and doc.get("kind") == "Deployment":
                    count = dig(doc, "spec.replicas")
                    if count is not None:
                        replicas.setdefault(svc_dir.name, (int(count), "deployment"))
    return replicas


def load_pgbouncer_pools():
    """(default_pool_size, {db_name: pool_size}) from the pgbouncer configmap."""
    cm = load_yaml_docs(PGBOUNCER_CONFIGMAP)
    # "pgbouncer.ini" contains a dot — read it directly (dig() is dotted-path).
    ini_text = (cm.get("data") or {}).get("pgbouncer.ini")
    if not ini_text:
        sys.stderr.write("pool-budget-check: k8s/pgbouncer/configmap.yaml has no data.pgbouncer.ini\n")
        sys.exit(2)
    parser = configparser.ConfigParser(allow_no_value=True, delimiters=("=",),
                                       interpolation=None, strict=False)
    parser.optionxform = str  # keep pool_size / default_pool_size capitalisation
    parser.read_string(ini_text, source=str(PGBOUNCER_CONFIGMAP))
    default_pool = parser.getint("pgbouncer", "default_pool_size")
    db_pools = {}
    for name, target in parser.items("databases"):
        if not target:
            continue
        options = dict(t.split("=", 1) for t in target.split() if "=" in t)
        if "pool_size" not in options:
            continue
        actual_db = options.get("dbname", name)
        db_pools[actual_db] = int(options["pool_size"])
    return default_pool, db_pools


def main():
    if not PGBOUNCER_CONFIGMAP.exists():
        sys.stderr.write(f"pool-budget-check: missing {PGBOUNCER_CONFIGMAP}\n")
        sys.exit(2)
    services = collect_services()
    replicas = collect_max_replicas()
    default_pool, db_pools = load_pgbouncer_pools()

    rows, per_db_demand = [], {}
    violations = []
    for service in sorted(services):
        db, pool = services[service]["db"], services[service]["pool"]
        max_replicas, source = replicas.get(service, (1, "absent -> assumed 1"))
        demand = max_replicas * pool
        per_db_demand[db] = per_db_demand.get(db, 0) + demand
        rows.append((service, db, source, max_replicas, pool, demand))

    print(f"{'service':<16} {'database':<16} {'replicas-source':<22} {'max-replicas':>12} "
          f"{'pool/pod':>9} {'demand':>7}")
    print("-" * 86)
    for row in rows:
        print(f"{row[0]:<16} {row[1]:<16} {row[2]:<22} {row[3]:>12} {row[4]:>9} {row[5]:>7}")
    print()

    for db in sorted(per_db_demand):
        demand = per_db_demand[db]
        pgb_pool = db_pools.get(db, default_pool)
        ceiling = min(pgb_pool, PG_MAX_CONNECTIONS)
        ok = demand * BUDGET_DEN <= BUDGET_NUM * ceiling
        if not ok:
            violations.append(db)
        print(f"{'OK' if ok else 'VIOLATION':<9} db={db:<16} demand={demand:<5} "
              f"ceiling=min(pgbouncer={pgb_pool}, pg_max_conn={PG_MAX_CONNECTIONS})={ceiling:<5} "
              f"budget(0.8x)={0.8 * ceiling:g}")

    print()
    if violations:
        print("POOL BUDGET CHECK FAILED for: " + ", ".join(violations))
        print("Raise the per-DB pgbouncer pool_size (k8s/pgbouncer/configmap.yaml) or reduce "
              "HPA maxReplicas / DB_POOL_SIZE to satisfy §4.2, per the table's classification.")
        return 1
    print("Pool budget check passed: every database is within the §4.2 headroom rule.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
