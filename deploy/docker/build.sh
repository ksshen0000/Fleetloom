#!/usr/bin/env bash
# Builds everything the container deployment needs: service jars, the web console, and the images.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
./mvnw -q install -DskipTests
(cd rcs-web && corepack pnpm install --frozen-lockfile && corepack pnpm build)
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml --profile sim build
