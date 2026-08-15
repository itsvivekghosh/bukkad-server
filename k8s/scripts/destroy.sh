#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(dirname "$SCRIPT_DIR")"

echo "Destroying Bhukkad Kubernetes deployment..."
kubectl delete -k "${K8S_DIR}" --ignore-not-found=true
kubectl delete namespace bhukkad --ignore-not-found=true --wait=true --timeout=120s 2>/dev/null || true
echo "Done!"
