# Vault secret provisioning

Seeds the KV v2 paths consumed by the ExternalSecrets operator, which
materializes the `bhukkad-secrets` Kubernetes Secret.

Files:
- `scripts/provision-vault.sh` — seeds
  `secret/bhukkad/prod/{database,redis,auth,rabbitmq,payments}`
- `k8s/secret-store.yaml` — SecretStore → Vault (Kubernetes auth,
  role `bhukkad-api`, SA `bhukkad-app` in namespace `bhukkad`)
- `k8s/external-secret.yaml` — ExternalSecret → target Secret `bhukkad-secrets`

## 1. Install and start Vault

Local (dev, single node, no persistence):

```sh
vault server -dev
export VAULT_ADDR=http://127.0.0.1:8200   # dev server prints the root token
export VAULT_TOKEN=<root-token>
```

Production (HA): deploy the official Helm chart (`hashicorp/vault` with
`server.ha.enabled=true`, Raft storage), then `vault operator init`/`unseal`
per the chart docs.

## 2. Enable KV v2 at `secret/`

```sh
vault secrets enable -path=secret kv-v2
```

## 3. Enable Kubernetes auth and create the role

Run from inside a pod in the `bhukkad` namespace (or pass the token/CA
explicitly):

```sh
vault auth enable kubernetes

vault write auth/kubernetes/config \
  token_reviewer_jwt="$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)" \
  kubernetes_host="https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT}" \
  kubernetes_ca_cert="$(cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)"

vault write auth/kubernetes/role/bhukkad-api \
  bound_service_account_names=bhukkad-app \
  bound_service_account_namespaces=bhukkad \
  policies=default \
  ttl=1h
```

## 4. Seed the secrets

```sh
MYSQL_USER=... MYSQL_PASSWORD=... REDIS_PASSWORD=... JWT_SECRET=... \
  ./scripts/provision-vault.sh --all
./scripts/provision-vault.sh --verify
```

## 5. Apply the SecretStore and ExternalSecret

```sh
kubectl apply -f k8s/secret-store.yaml -f k8s/external-secret.yaml
```

## 6. Verify the secret materializes

```sh
kubectl get secret bhukkad-secrets
kubectl describe externalsecret bhukkad-secrets
```

`kubectl describe externalsecret bhukkad-secrets` should show a `Ready` /
`SecretSynced` condition. The ExternalSecret refreshes every `refreshInterval`
(1h) and recreates `bhukkad-secrets` (`creationPolicy: Owner`).
