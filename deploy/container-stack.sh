#!/usr/bin/env bash
# Runs the whole platform in containers (docker-compose.yml + docker-compose.app.yml, with the simulator).
#
#   deploy/container-stack.sh build    # jars, web console and images
#   deploy/container-stack.sh start    # build if images are missing, start, create the demo data on a fresh database
#   deploy/container-stack.sh status
#   deploy/container-stack.sh stop     # removes the containers, keeps the database volume
#
# The jars need JDK 25: without one on the PATH they are built in an eclipse-temurin:25-jdk container
# (reusing ~/.m2, through $https_proxy if set). FLEETLOOM_ADMIN_PASSWORD, FLEETLOOM_JWT_SECRET and
# FLEETLOOM_HTTP_PORT are passed on to docker compose.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${FLEETLOOM_HTTP_PORT:-8000}
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" -f "$ROOT/deploy/docker-compose.app.yml" --profile sim)
export no_proxy=localhost,127.0.0.1${no_proxy:+,$no_proxy}

java_major() {
  java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

build_jars() {
  if [[ $(java_major 2>/dev/null || echo 0) -ge 25 ]]; then
    (cd "$ROOT" && ./mvnw -q -B install -DskipTests)
    return
  fi
  echo "  no JDK 25 here, building the jars in a container"
  local env=()
  if [[ -n ${https_proxy:-} ]]; then
    local hostport=${https_proxy#*://}
    hostport=${hostport%/}
    env=(-e "https_proxy=$https_proxy" -e "http_proxy=${http_proxy:-$https_proxy}"
      -e "JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=${hostport%:*} -Dhttp.proxyPort=${hostport##*:} -Dhttps.proxyHost=${hostport%:*} -Dhttps.proxyPort=${hostport##*:}")
  fi
  mkdir -p "$HOME/.m2"
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/home/u -e MAVEN_OPTS=-Duser.home=/home/u -v "$HOME/.m2:/home/u/.m2" -v "$ROOT:/src" -w /src \
    "${env[@]}" eclipse-temurin:25-jdk ./mvnw -q -B install -DskipTests
}

build() {
  build_jars
  (cd "$ROOT/fleetloom-web" && COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack pnpm install --frozen-lockfile \
    && COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack pnpm build)
  "${COMPOSE[@]}" build
}

# The setup scripts call fleetloom-resource directly; here it is only reachable through the gateway,
# so they run against it with an admin token supplied by a .curlrc.
seed() {
  local curl_home token
  curl_home=$(mktemp -d)
  token=$(curl -sf -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"${FLEETLOOM_ADMIN_PASSWORD:-admin12345}\"}" \
    "localhost:$PORT/api/resource/v1/auth/login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
  echo "header = \"Authorization: Bearer $token\"" >"$curl_home/.curlrc"
  export CURL_HOME=$curl_home FLEETLOOM_RESOURCE_URL=http://localhost:$PORT
  local seeded=
  # the map and storage of deploy/examples, also when an older, smaller version is there
  # (a version from before vehicle types restricted the cross aisle counts as older)
  if ! curl -sf "localhost:$PORT/api/resource/v1/maps/DEMO/active" | grep -q '"MINI"'; then
    "$ROOT/deploy/examples/setup-demo-map.sh"
    seeded=1
  fi
  if ! curl -sf "localhost:$PORT/api/resource/v1/zones" | grep -q '"code":"STORE"'; then
    "$ROOT/deploy/examples/setup-demo-storage.sh" >/dev/null
    echo "  demo storage data created"
  fi
  if ! curl -sf "localhost:$PORT/api/resource/v1/vehicle-types" | grep -q '"code":"FORK"'; then
    "$ROOT/deploy/examples/setup-demo-fleet.sh" >/dev/null
    echo "  demo vehicle types and pallets created"
  fi
  # the simulator loads its map once at startup
  if [[ -n $seeded ]]; then
    "${COMPOSE[@]}" restart simulator
  fi
  rm -rf "$curl_home"
}

start() {
  if ! docker image inspect fleetloom/gateway:"${FLEETLOOM_VERSION:-dev}" >/dev/null 2>&1; then
    build
  fi
  "${COMPOSE[@]}" up -d --wait
  seed
  echo "UI: http://localhost:$PORT (admin / FLEETLOOM_ADMIN_PASSWORD, default admin12345)"
}

case ${1:-} in
  build) build ;;
  start) start ;;
  stop) "${COMPOSE[@]}" down ;;
  status) "${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' ;;
  *) echo "usage: $0 build|start|stop|status"; exit 2 ;;
esac
