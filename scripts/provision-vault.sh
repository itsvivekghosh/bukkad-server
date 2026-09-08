#!/usr/bin/env bash
# =============================================================================
# Bhukkad Vault secret provisioning (KV v2)
#
# Seeds the Vault paths referenced by k8s/external-secret.yaml so the
# ExternalSecrets operator can materialize bhukkad-secrets in the cluster.
#
# Usage:
#   ./scripts/provision-vault.sh --all          # seed all documented paths
#   ./scripts/provision-vault.sh --path secret/bhukkad/prod/database \
#       username=bhukkad password='...'
#   ./scripts/provision-vault.sh --verify       # print wiring (no changes)
#   ./scripts/provision-vault.sh --help
#
# Key names written here MUST match the remoteRef.properties in
# k8s/external-secret.yaml (path segments
# secret/bhukkad/prod/{database,redis,auth,rabbitmq,payments}).
#
# Requires: vault CLI authenticated (VAULT_ADDR/VAULT_TOKEN or kube auth).
# This is an operational script — run by an operator with Vault access.
# =============================================================================
set -euo pipefail

VAULT_ADDR="${VAULT_ADDR:?set VAULT_ADDR (e.g. https://vault.bhukkad.internal:8200)}"
export VAULT_ADDR

MYSQL_USER="${MYSQL_USER:?set MYSQL_USER}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"
REDIS_PASSWORD="${REDIS_PASSWORD:?set REDIS_PASSWORD}"
JWT_SECRET="${JWT_SECRET:?set JWT_SECRET (>=64 bytes base64 for HS512)}"
JWT_EXPIRATION="${JWT_EXPIRATION:-3600}"
JWT_REFRESH_EXPIRATION="${JWT_REFRESH_EXPIRATION:-604800}"
RABBITMQ_USER="${RABBITMQ_USER:-bhukkad}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-bhukkad}"
RAZORPAY_KEY_ID="${RAZORPAY_KEY_ID:-}"
RAZORPAY_KEY_SECRET="${RAZORPAY_KEY_SECRET:-}"
RAZORPAY_WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-}"

write_secret() {
    local path="$1"; shift
    echo "== writing $path =="
    vault kv put "$path" "$@"
}

seed_all() {
    write_secret secret/bhukkad/prod/database \
        username="$MYSQL_USER" password="$MYSQL_PASSWORD"
    write_secret secret/bhukkad/prod/redis \
        password="$REDIS_PASSWORD"
    write_secret secret/bhukkad/prod/auth \
        jwt_secret="$JWT_SECRET" \
        jwt_expiration="$JWT_EXPIRATION" \
        jwt_refresh_expiration="$JWT_REFRESH_EXPIRATION"
    write_secret secret/bhukkad/prod/rabbitmq \
        username="$RABBITMQ_USER" password="$RABBITMQ_PASSWORD"
    write_secret secret/bhukkad/prod/payments \
        razorpay_key_id="$RAZORPAY_KEY_ID" \
        razorpay_key_secret="$RAZORPAY_KEY_SECRET" \
        razorpay_webhook_secret="$RAZORPAY_WEBHOOK_SECRET"
}

MODE="${1:---all}"
case "$MODE" in
    --all) seed_all ;;
    --path)
        shift
        local_path="$1"; shift
        write_secret "$local_path" "$@"
        ;;
    --verify)
        echo "Verifying ExternalSecrets wiring:"
        echo "  k8s/secret-store.yaml    -> $VAULT_ADDR"
        echo "  k8s/external-secret.yaml -> secret/bhukkad/prod/{database,redis,auth,rabbitmq,payments}"
        ;;
    --help)
        grep '^#' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *) echo "Unknown mode: $MODE" >&2; exit 2 ;;
esac
