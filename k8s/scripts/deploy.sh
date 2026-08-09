#!/bin/bash

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')] $1${NC}"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] $1${NC}"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] $1${NC}"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"

echo ""
echo "================================================"
echo "  Bhukkad - Kubernetes Deployment"
echo "================================================"
echo ""

# Check tools
command -v kubectl >/dev/null 2>&1 || error "kubectl not found. Install: brew install kubectl"
command -v docker >/dev/null 2>&1 || error "docker not found"
log "Tools found"

# Check cluster
log "Checking cluster..."
if ! kubectl cluster-info >/dev/null 2>&1; then
    warn "Cluster not reachable. Trying to start minikube..."
    if command -v minikube &>/dev/null; then
        minikube start --cpus=4 --memory=8192 --driver=docker
        minikube addons enable ingress
        minikube addons enable metrics-server
    else
        error "No cluster available. Install minikube: brew install minikube"
    fi
fi
log "Cluster is reachable"

# Set docker env for minikube
if command -v minikube &>/dev/null; then
    log "Setting minikube docker env..."
    eval $(minikube docker-env)
fi

# Build JAR
log "Building JAR..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q
log "JAR built"

# Build Docker image
log "Building Docker image..."
docker build -t bhukkad-server:latest -f docker/Dockerfile .
log "Docker image built"

# Load image (for minikube)
if command -v minikube &>/dev/null; then
    log "Loading image into minikube..."
    minikube image load bhukkad-server:latest 2>/dev/null || true
fi

# Delete existing deployment (clean start)
log "Cleaning old deployment..."
kubectl delete -k k8s/ --ignore-not-found=true 2>/dev/null || true
sleep 5

# Apply manifests
log "Applying Kubernetes manifests..."
kubectl apply -k k8s/

# Wait for MySQL
log "Waiting for MySQL (up to 3 min)..."
for i in $(seq 1 60); do
    STATUS=$(kubectl get pods -n bhukkad -l component=mysql -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "Pending")
    READY=$(kubectl get pods -n bhukkad -l component=mysql -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || echo "false")
    if [ "$STATUS" = "Running" ] && [ "$READY" = "true" ]; then
        log "MySQL is ready!"
        break
    fi
    if [ $i -eq 60 ]; then
        warn "MySQL taking long. Checking logs..."
        kubectl logs -n bhukkad -l component=mysql --tail=20 2>/dev/null
    fi
    echo -n "."
    sleep 3
done
echo ""

# Wait for Redis
log "Waiting for Redis..."
for i in $(seq 1 30); do
    READY=$(kubectl get pods -n bhukkad -l component=redis -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || echo "false")
    if [ "$READY" = "true" ]; then
        log "Redis is ready!"
        break
    fi
    echo -n "."
    sleep 2
done
echo ""

# Wait for App
log "Waiting for Application (up to 5 min)..."
for i in $(seq 1 100); do
    STATUS=$(kubectl get pods -n bhukkad -l component=api -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "Pending")
    READY=$(kubectl get pods -n bhukkad -l component=api -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || echo "false")

    if [ "$STATUS" = "Running" ] && [ "$READY" = "true" ]; then
        log "Application is ready!"
        break
    fi

    # Check for errors
    if [ "$STATUS" = "CrashLoopBackOff" ] || [ "$STATUS" = "Error" ]; then
        warn "App pod has errors. Checking logs..."
        kubectl logs -n bhukkad -l component=api --tail=30 2>/dev/null
        echo ""
        warn "Checking init container logs..."
        POD_NAME=$(kubectl get pod -n bhukkad -l component=api -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
        kubectl logs -n bhukkad "$POD_NAME" -c wait-for-mysql --tail=10 2>/dev/null
        break
    fi

    echo -n "."
    sleep 3
done
echo ""

# Final status
echo ""
log "=== Deployment Status ==="
echo ""
kubectl get all -n bhukkad
echo ""

# Check if app is responding
log "Testing health endpoint..."
kubectl port-forward -n bhukkad svc/bhukkad-app 9090:8080 &
PF_PID=$!
sleep 5

HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/api/health/ping 2>/dev/null || echo "000")
kill $PF_PID 2>/dev/null || true

if [ "$HEALTH" = "200" ]; then
    log "Health check PASSED!"
else
    warn "Health check returned: $HEALTH"
    warn "App might still be starting. Check logs:"
    warn "kubectl logs -n bhukkad -l component=api -f"
fi

echo ""
echo "================================================"
echo "  Access Commands:"
echo ""
echo "  # Port forward app"
echo "  kubectl port-forward -n bhukkad svc/bhukkad-app 8080:8080"
echo ""
echo "  # Port forward nginx"
echo "  kubectl port-forward -n bhukkad svc/bhukkad-nginx 80:80"
echo ""
echo "  # View app logs"
echo "  kubectl logs -n bhukkad -l component=api -f"
echo ""
echo "  # Test"
echo "  curl http://localhost:8080/api/health/ping"
echo ""
echo "  # Status"
echo "  ./k8s/scripts/status.sh"
echo ""
echo "  # Destroy"
echo "  ./k8s/scripts/destroy.sh"
echo "================================================"