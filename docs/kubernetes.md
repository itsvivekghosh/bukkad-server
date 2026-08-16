# Kubernetes Guide

Deploy Bhukkad to a Kubernetes cluster using Kustomize manifests under `k8s/`.

## Prerequisites

- `kubectl` configured for your cluster
- `docker` for building images
- **Minikube** (local) or any CNCF-compliant cluster (EKS, GKE, AKS, etc.)
- For HPA: metrics-server installed
- For ingress: nginx ingress controller

## Stack components

| Component | Manifest | Notes |
|-----------|----------|-------|
| Namespace | `namespace.yaml` | `bhukkad` |
| ConfigMap | `configmap.yaml` | Non-secret app config |
| Secrets | `secrets.yaml` | DB, Redis, JWT (replace in real prod) |
| MySQL | `mysql/*` | PVC + performance config |
| Redis | `redis/*` | PVC + tuned config |
| RabbitMQ | `rabbitmq/deployment.yaml` | Optional STOMP relay |
| App | `app/deployment.yaml` | 2 replicas, probes, PDB |
| HPA | `app/hpa.yaml` | CPU/memory autoscaling |
| Nginx | `nginx/*` | Reverse proxy |
| Ingress | `ingress.yaml` | External access |

## Quick deploy (Minikube)

```bash
# One-command deploy from repo root
./k8s/scripts/deploy.sh
```

Options:

```bash
./k8s/scripts/deploy.sh --skip-build    # Reuse local image
./k8s/scripts/deploy.sh --clean         # Delete namespace first
./k8s/scripts/deploy.sh --tag myreg/bhukkad:v1
```

The script will:

1. Start minikube if no cluster is reachable
2. Build `bhukkad-server:latest` with BuildKit
3. `kubectl apply -k k8s/`
4. Wait for MySQL → Redis → App rollouts
5. Port-forward health check

## Manual deploy

```bash
export DOCKER_BUILDKIT=1

# Build image (use minikube docker env if local)
eval $(minikube docker-env)   # minikube only
docker build -f docker/Dockerfile -t bhukkad-server:latest .

kubectl apply -k k8s/

kubectl rollout status deployment/bhukkad-mysql -n bhukkad --timeout=300s
kubectl rollout status deployment/bhukkad-redis -n bhukkad --timeout=180s
kubectl rollout status deployment/bhukkad-app -n bhukkad --timeout=600s
```

## Access the API

### Port forward (local)

```bash
kubectl port-forward -n bhukkad svc/bhukkad-app 8080:8080
curl http://localhost:8080/api/v1/health/ping
```

### Via nginx service

```bash
kubectl port-forward -n bhukkad svc/bhukkad-nginx 8080:80
```

### Ingress (cluster with ingress controller)

Hosts configured in `k8s/ingress.yaml`:

- `api.bhukkad.com`
- `localhost`

```bash
# Minikube ingress
minikube addons enable ingress
echo "$(minikube ip) api.bhukkad.com" | sudo tee -a /etc/hosts
curl http://api.bhukkad.com/api/v1/health/ping
```

## Secrets (production)

**Do not use committed `k8s/secrets.yaml` values in production.**

Options:

1. Edit secrets before apply (dev only)
2. Use `k8s/external-secret.yaml` with External Secrets Operator
3. `kubectl create secret generic bhukkad-secrets --from-env-file=.env -n bhukkad`

Required keys:

- `DB_USERNAME`, `DB_PASSWORD`
- `MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET`, `JWT_EXPIRATION`, `JWT_REFRESH_EXPIRATION`

## App deployment details

- **Replicas:** 2 (HPA scales 2–10)
- **Probes:**
  - Startup: `/api/v1/health/ping`
  - Liveness: `/actuator/health/liveness`
  - Readiness: `/actuator/health/readiness`
- **Graceful shutdown:** `preStop` sleep 15s + 45s termination grace
- **Pod anti-affinity:** spreads replicas across nodes
- **PDB:** `minAvailable: 1` during disruptions

## Horizontal Pod Autoscaler

Default HPA (`k8s/app/hpa.yaml`):

- Min: 2, Max: 10
- Targets: CPU 65%, Memory 75%
- Scale-up: aggressive; scale-down: 5 min stabilization

Optional custom-metrics HPA (requires Prometheus adapter):

```bash
kubectl apply -k k8s/overlays/custom-metrics-hpa/
```

> Only one HPA should target `bhukkad-app` at a time.

## Observability

```bash
# Pod status
./k8s/scripts/status.sh

# App logs
kubectl logs -n bhukkad -l component=api -f --tail=100

# Prometheus (requires ADMIN role or bearer token in prod)
kubectl port-forward -n bhukkad svc/bhukkad-app 8080:8080
curl -H "Authorization: Bearer $ADMIN_JWT" http://localhost:8080/actuator/prometheus
```

## Scaling manually

```bash
kubectl scale deployment/bhukkad-app -n bhukkad --replicas=4
```

## Update image (rolling deploy)

```bash
docker build -t bhukkad-server:v2 -f docker/Dockerfile .
# minikube: eval $(minikube docker-env) first

kubectl set image deployment/bhukkad-app \
  bhukkad-app=bhukkad-server:v2 -n bhukkad

kubectl rollout status deployment/bhukkad-app -n bhukkad
```

## Teardown

```bash
./k8s/scripts/destroy.sh
# or:
kubectl delete -k k8s/
kubectl delete namespace bhukkad --ignore-not-found
```

## Production checklist

- [ ] Replace `k8s/secrets.yaml` with sealed secrets / external secrets
- [ ] Set `SPRING_PROFILES_ACTIVE=prod` in ConfigMap
- [ ] Push image to a registry; set `imagePullPolicy: Always` and real image URL
- [ ] Configure TLS on ingress
- [ ] Enable `app.monitoring.prometheus.require-auth` and set `PROMETHEUS_BEARER_TOKEN`
- [ ] Size MySQL/Redis PVCs for expected load
- [ ] Install metrics-server for HPA
- [ ] Configure backup for MySQL PVC

## Troubleshooting

| Symptom | Command / fix |
|---------|----------------|
| Pod `CrashLoopBackOff` | `kubectl logs -n bhukkad <pod> --previous` |
| App not ready | Check MySQL/Redis pods; `kubectl describe pod -n bhukkad -l component=api` |
| Image pull error | Build inside minikube docker env or push to registry |
| Flyway migration fail | Check MySQL logs; ensure PVC not corrupted |
| HPA not scaling | `kubectl get hpa -n bhukkad`; verify metrics-server |
