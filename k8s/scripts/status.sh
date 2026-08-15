#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(dirname "$SCRIPT_DIR")"

echo ""
echo "================================================"
echo "  Bhukkad Kubernetes Status"
echo "================================================"
echo ""

echo "--- Namespace ---"
kubectl get namespace bhukkad 2>/dev/null || echo "Namespace not found"

echo ""
echo "--- Pods ---"
kubectl get pods -n bhukkad -o wide 2>/dev/null || echo "No pods (namespace missing?)"

echo ""
echo "--- Services ---"
kubectl get svc -n bhukkad 2>/dev/null || true

echo ""
echo "--- Deployments ---"
kubectl get deployments -n bhukkad 2>/dev/null || true

echo ""
echo "--- HPA ---"
kubectl get hpa -n bhukkad 2>/dev/null || true

echo ""
echo "--- Ingress ---"
kubectl get ingress -n bhukkad 2>/dev/null || true

echo ""
echo "--- PVCs ---"
kubectl get pvc -n bhukkad 2>/dev/null || true

echo ""
echo "--- Events (last 10) ---"
kubectl get events -n bhukkad --sort-by='.lastTimestamp' 2>/dev/null | tail -10 || true

echo ""
echo "================================================"
echo "Deploy: ${K8S_DIR}/scripts/deploy.sh"
echo "Destroy: ${K8S_DIR}/scripts/destroy.sh"
echo "================================================"
