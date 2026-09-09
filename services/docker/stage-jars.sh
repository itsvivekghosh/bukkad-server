#!/usr/bin/env bash
# Stage packaged Spring Boot jars for services/docker/Dockerfile.prebuilt.
# The repo-root .dockerignore excludes **/target, so compose images COPY jars
# from services/docker/images/<module>/app.jar instead.
# Run after:  ./mvnw -f services/pom.xml package -DskipTests
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

MODULES="gateway identity restaurant order payment delivery notification admin-analytics search survey referral supportticket realtime growth personalization"

for m in ${MODULES}; do
  jar=$(find services/${m}/target -maxdepth 1 -name "${m}-*.jar" ! -name "*.original" 2>/dev/null | head -1 || true)
  if [ -z "${jar}" ]; then echo "skip ${m} (no packaged jar)"; continue; fi
  mkdir -p "services/docker/images/${m}"
  cp "${jar}" "services/docker/images/${m}/app.jar"
  echo "staged ${m} <- ${jar}"
done
