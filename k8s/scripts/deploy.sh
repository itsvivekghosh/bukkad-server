#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')] $1${NC}"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] $1${NC}"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] $1${NC}"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
K8S_DIR="${PROJECT_DIR}/k8s"
MINIKUBE_OVERLAY="${K8S_DIR}/overlays/minikube"
IMAGE_TAG="${IMAGE_TAG:-bhukkad-server:latest}"
CLEAN_DEPLOY="${CLEAN_DEPLOY:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
USE_MINIKUBE_PROFILE=false
KUSTOMIZE_FLAGS=()

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --clean          Delete existing resources before apply
  --skip-build     Skip Maven/Docker build (reuse local image)
  --tag <image>    Image tag (default: bhukkad-server:latest)
  -h, --help       Show help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN_DEPLOY=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --tag) IMAGE_TAG="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) error "Unknown option: $1" ;;
  esac
done

command -v kubectl >/dev/null 2>&1 || error "kubectl not found"
command -v docker >/dev/null 2>&1 || error "docker not found"

echo ""
echo "================================================"
echo "  Bhukkad - Kubernetes Deployment"
echo "================================================"

if ! kubectl cluster-info >/dev/null 2>&1; then
  if command -v minikube >/dev/null 2>&1; then
    warn "Cluster unreachable; starting minikube..."
    minikube start --cpus=2 --memory=4096 --driver=docker
    minikube addons enable ingress
    minikube addons enable metrics-server
  else
    error "No reachable cluster. Start minikube or point kubeconfig to a cluster."
  fi
fi

if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
  USE_MINIKUBE_PROFILE=true
  KUSTOMIZE_DIR="${MINIKUBE_OVERLAY}"
  KUSTOMIZE_FLAGS=(--load-restrictor LoadRestrictionsNone)
  log "Minikube detected — using low-memory overlay (1 app replica, no RabbitMQ)"
  eval "$(minikube docker-env)"
  minikube addons enable metrics-server 2>/dev/null || true
else
  KUSTOMIZE_DIR="${K8S_DIR}"
fi

if [[ "$SKIP_BUILD" != "true" ]]; then
  export DOCKER_BUILDKIT=1
  log "Building application image (${IMAGE_TAG})..."
  docker build \
    --file "${PROJECT_DIR}/docker/Dockerfile" \
    --tag "${IMAGE_TAG}" \
    --build-arg BUILDKIT_INLINE_CACHE=1 \
    "${PROJECT_DIR}"
else
  log "Skipping image build"
fi

apply_manifests() {
  kubectl kustomize "${KUSTOMIZE_FLAGS[@]}" "${KUSTOMIZE_DIR}" | kubectl apply -f -
}

delete_manifests() {
  kubectl kustomize "${KUSTOMIZE_FLAGS[@]}" "${KUSTOMIZE_DIR}" | kubectl delete -f - --ignore-not-found=true || true
}

if [[ "$CLEAN_DEPLOY" == "true" ]]; then
  warn "Cleaning previous deployment..."
  # Remove legacy RabbitMQ if switching from full profile to minikube overlay.
  kubectl delete deployment/bhukkad-rabbitmq -n bhukkad --ignore-not-found=true || true
  delete_manifests
  kubectl wait --for=delete namespace/bhukkad --timeout=120s 2>/dev/null || true
fi

log "Applying manifests from ${KUSTOMIZE_DIR}..."
apply_manifests

log "Waiting for data stores..."
kubectl rollout status deployment/bhukkad-mysql -n bhukkad --timeout=600s
kubectl rollout status deployment/bhukkad-redis -n bhukkad --timeout=300s
if [[ "$USE_MINIKUBE_PROFILE" != "true" ]]; then
  kubectl rollout status deployment/bhukkad-rabbitmq -n bhukkad --timeout=300s || true
fi

log "Rolling out API..."
kubectl rollout status deployment/bhukkad-app -n bhukkad --timeout=900s
kubectl rollout status deployment/bhukkad-nginx -n bhukkad --timeout=180s || true

log "Deployment status"
kubectl get pods,svc,hpa -n bhukkad

log "Health check via port-forward..."
kubectl port-forward -n bhukkad svc/bhukkad-app 19090:8080 >/tmp/bhukkad-pf.log 2>&1 &
PF_PID=$!
trap 'kill ${PF_PID} 2>/dev/null || true' EXIT
sleep 3
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:19090/api/v1/health/ping || echo "000")
kill ${PF_PID} 2>/dev/null || true
trap - EXIT

if [[ "$HTTP_CODE" == "200" ]]; then
  log "Health check passed"
else
  warn "Health check returned ${HTTP_CODE}. Inspect logs: kubectl logs -n bhukkad -l component=api --tail=50"
fi

cat <<EOF

Access:
  kubectl port-forward -n bhukkad svc/bhukkad-app 8080:8080
  curl http://localhost:8080/api/v1/health/ping

Status:  ${K8S_DIR}/scripts/status.sh
Destroy: ${K8S_DIR}/scripts/destroy.sh
EOF
