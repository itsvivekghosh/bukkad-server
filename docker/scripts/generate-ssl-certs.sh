#!/bin/bash
# =============================================================================
# Generate a self-signed certificate for the nginx TLS front door.
#
# For production, replace docker/nginx/ssl/fullchain.pem + privkey.pem with
# real certificates (Let's Encrypt / your CA). This script is only for local
# dev / staging so nginx can bind :443 and test HTTP/2 end-to-end.
# =============================================================================
set -euo pipefail

SSL_DIR="$(cd "$(dirname "$0")/../nginx/ssl" && pwd)"
mkdir -p "${SSL_DIR}"

CERT_DAYS="${CERT_DAYS:-365}"
CERT_CN="${CERT_CN:-api.bhukkad.com}"

if [ -f "${SSL_DIR}/fullchain.pem" ] && [ -f "${SSL_DIR}/privkey.pem" ]; then
  echo "Certificates already exist in ${SSL_DIR} — skipping (remove them to regenerate)."
  exit 0
fi

openssl req -x509 -nodes \
  -newkey rsa:2048 \
  -days "${CERT_DAYS}" \
  -subj "/CN=${CERT_CN}" \
  -addext "subjectAltName=DNS:${CERT_CN},DNS:localhost,IP:127.0.0.1" \
  -keyout "${SSL_DIR}/privkey.pem" \
  -out "${SSL_DIR}/fullchain.pem"

chmod 600 "${SSL_DIR}/privkey.pem"
echo "Self-signed certificate written to ${SSL_DIR}/"
echo "  fullchain.pem  (public cert)"
echo "  privkey.pem    (private key)"