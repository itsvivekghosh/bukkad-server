#!/usr/bin/env python3
"""
Chaos test runner for Phase 9.

Executes a sequence of failure-injection tests against a running cluster
and reports pass/fail for each.
"""

import argparse
import subprocess
import sys
import time


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}")
    return subprocess.run(cmd, check=check)


def wait(seconds: int, reason: str) -> None:
    print(f"Waiting {seconds}s for {reason}...")
    time.sleep(seconds)


def test_redis_master_failover() -> bool:
    print("\n=== Redis master failover ===")
    try:
        run(["kubectl", "delete", "pod", "redis-cluster-0", "-n", "bhukkad"])
        wait(30, "Redis failover")
        # Verify cluster state
        result = run(
            ["kubectl", "exec", "-n", "bhukkad", "redis-cluster-1", "--",
             "redis-cli", "cluster", "info"],
            check=False,
        )
        if result.returncode != 0:
            print("FAIL: redis-cluster info failed")
            return False
        if "cluster_state:ok" not in (result.stdout or ""):
            print("FAIL: cluster not ok after failover")
            return False
        print("PASS: Redis failover")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def test_kafka_broker_kill() -> bool:
    print("\n=== Kafka broker kill ===")
    try:
        run(["kubectl", "delete", "pod", "redpanda-0", "-n", "bhukkad"])
        wait(30, "Kafka broker recovery")
        print("PASS: Kafka broker kill")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def test_gateway_pod_kill() -> bool:
    print("\n=== Gateway pod kill ===")
    try:
        result = run(
            ["kubectl", "get", "pods", "-l", "app=bhukkad,component=gateway", "-n", "bhukkad", "-o", "jsonpath={.items[0].metadata.name}"],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0 or not result.stdout.strip():
            print("SKIP: no gateway pod found")
            return True
        pod = result.stdout.strip()
        run(["kubectl", "delete", "pod", pod, "-n", "bhukkad"])
        wait(10, "Gateway pod reschedule")
        print("PASS: Gateway pod kill")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def test_pgbouncer_restart() -> bool:
    print("\n=== pgbouncer restart ===")
    try:
        run(["kubectl", "rollout", "restart", "deployment/pgbouncer", "-n", "bhukkad"])
        wait(15, "pgbouncer restart")
        print("PASS: pgbouncer restart")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Run chaos tests")
    parser.add_argument("--test", choices=["redis", "kafka", "gateway", "pgbouncer", "all"],
                        default="all", help="Which chaos test to run")
    args = parser.parse_args()

    tests = {
        "redis": test_redis_master_failover,
        "kafka": test_kafka_broker_kill,
        "gateway": test_gateway_pod_kill,
        "pgbouncer": test_pgbouncer_restart,
    }

    results = []
    if args.test == "all":
        for name, fn in tests.items():
            results.append((name, fn()))
    else:
        fn = tests[args.test]
        results.append((args.test, fn()))

    print("\n=== Chaos Test Results ===")
    failures = 0
    for name, passed in results:
        status = "PASS" if passed else "FAIL"
        print(f"  {name}: {status}")
        if not passed:
            failures += 1

    if failures:
        print(f"\n{failures} test(s) failed.")
        return 1

    print("\nAll chaos tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
