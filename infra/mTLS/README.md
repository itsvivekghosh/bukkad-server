# mTLS / TLS Infrastructure

This directory contains the scripts and instructions to generate a local
internal CA and per-service certificates for mTLS between the gateway and
backend services.

## Prerequisites

- OpenSSL 3.x (for local dev)
- cert-manager (for Kubernetes — see separate k8s plan)

## Local CA + Certs (Docker Compose / local dev)

Run the generation script:

```bash
cd infra/mTLS
chmod +x generate-certs.sh
./generate-certs.sh
```

This produces:

```
infra/mTLS/
  ca/
    ca.key          # DO NOT COMMIT
    ca.crt          # internal CA certificate (shared trust anchor)
  gateway/
    gateway.key     # DO NOT COMMIT
    gateway.crt
    gateway.p12     # PKCS12 keystore for Spring Boot server.ssl.key-store
  identity/
    identity.key    # DO NOT COMMIT
    identity.crt
    identity.p12
  restaurant/
    ...
  order/
    ...
  payment/
    ...
  delivery/
    ...
  search/
    ...
  truststore.p12    # DO NOT COMMIT — contains CA cert, used by every service
```

## Kubernetes (cert-manager)

In K8s, replace the local script with cert-manager `ClusterIssuer` + `Certificate`
resources. The `k8s/components/tls-internal` directory already exists (currently
commented out in kustomization); extend it with per-service `Certificate` resources
for `gateway`, `identity`, `restaurant`, `order`, `payment`, `delivery`, `search`.

## Security Notes

- **Never commit** `*.key`, `*.p12`, or `ca.key` to version control.
- Add them to `.gitignore` (verify it already covers `infra/mTLS/ca/*.key` and `**/*.p12`).
- Rotate CA annually; rotate service certs every 90 days (cert-manager can automate).
- The truststore password and keystore passwords must come from environment
  variables or a secret manager — never hardcoded.
