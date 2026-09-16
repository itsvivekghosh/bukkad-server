#!/usr/bin/env bash
set -euo pipefail

# generate-certs.sh — local internal CA + per-service certs for mTLS dev
# Produces PKCS12 keystores and a shared truststore under infra/mTLS/
# Requires: openssl, keytool (JDK)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PASS="changeit"
DAYS=365
KEY_SIZE=2048

echo "=== Generating local CA ==="
mkdir -p ca
openssl genrsa -out ca/ca.key ${KEY_SIZE}
openssl req -x509 -new -nodes -key ca/ca.key -sha256 -days ${DAYS} \
  -out ca/ca.crt \
  -subj "/CN=bhukkad-local-ca/O=Bhukkad/C=IN"

echo "=== Generating truststore ==="
keytool -importcert -noprompt -trustcacerts \
  -alias bhukkad-ca \
  -file ca/ca.crt \
  -keystore truststore.p12 \
  -storetype PKCS12 \
  -storepass "${PASS}"

echo "=== Generating per-service certs ==="
for svc in gateway identity restaurant order payment delivery search; do
  echo "  -> ${svc}"
  mkdir -p "${svc}"
  openssl genrsa -out "${svc}/${svc}.key" ${KEY_SIZE}

  cat > "${svc}/${svc}.cnf" <<CNF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = ${svc}

[v3_req]
keyUsage = keyEncipherment, digitalSignature
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = ${svc}
DNS.2 = localhost
DNS.3 = 127.0.0.1
IP.1 = 127.0.0.1
CNF

  openssl req -new -key "${svc}/${svc}.key" -out "${svc}/${svc}.csr" -config "${svc}/${svc}.cnf"
  openssl x509 -req -in "${svc}/${svc}.csr" -CA ca/ca.crt -CAkey ca/ca.key \
    -CAcreateserial -out "${svc}/${svc}.crt" -days ${DAYS} -sha256 -extensions v3_req -extfile "${svc}/${svc}.cnf"

  # PKCS12 keystore for Spring Boot server.ssl.key-store
  openssl pkcs12 -export -in "${svc}/${svc}.crt" -inkey "${svc}/${svc}.key" \
    -certfile ca/ca.crt \
    -out "${svc}/${svc}.p12" \
    -name "${svc}" \
    -passout pass:"${PASS}"

  rm -f "${svc}/${svc}.csr" "${svc}/${svc}.cnf" ca/ca.srl
done

echo ""
echo "=== Done ==="
echo "Truststore: ${SCRIPT_DIR}/truststore.p12  (password: ${PASS})"
echo "Keystores:  ${SCRIPT_DIR}/*/\${svc}.p12   (password: ${PASS})"
echo ""
echo "NEXT:"
echo "  1. Verify .gitignore covers infra/mTLS/ca/*.key, **/*.p12, **/*.key"
echo "  2. Mount these into your Docker Compose services or K8s Secrets"
echo "  3. Enable mTLS via spring profiles (see README.md)"
