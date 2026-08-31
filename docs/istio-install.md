# Istio mTLS runbook

Installs Istio and enables STRICT per-service mTLS for the `bhukkad`
namespace.

Manifests:
- `services/k8s/mtls-istio.yaml` — `PeerAuthentication` (STRICT) +
  `DestinationRule` (ISTIO_MUTUAL)
- `services/k8s/network-policy.yaml` — default-deny ingress NetworkPolicy

## 1. Install Istio

```sh
istioctl install --set profile=demo -y
istioctl verify-install
```

## 2. Label the namespace for sidecar injection

```sh
kubectl label namespace bhukkad istio-injection=enabled --overwrite
kubectl rollout restart deployment --all -n bhukkad
```

STRICT mTLS requires **every** workload in `bhukkad` to run a sidecar — any
pod without one cannot send or receive plaintext traffic.

## 3. Apply the mesh manifests

```sh
kubectl apply -f services/k8s/mtls-istio.yaml
kubectl apply -f services/k8s/network-policy.yaml
```

## 4. Verify

```sh
istioctl analyze
istioctl proxy-status
kubectl -n bhukkad get peerauthentication,destinationrule,networkpolicy
```

## Notes

- `network-policy.yaml` restricts ingress only and already allows the sidecar
  inbound ports (15008, 15006, 15021, 15090) plus the app ports (8080, 9090).
- Egress is unrestricted by the policy. If an egress rule is added later, do
  not block the sidecar's outbound traffic: the standard kubelet/mesh ports
  (15012, 15021, 15090, etc.) are covered by Istio's own default network
  policies — only app-egress restrictions are safe to add.
