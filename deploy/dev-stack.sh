#!/usr/bin/env bash
# Runs every Fleetloom service locally from the built jars, for development and demos.
#
#   deploy/dev-stack.sh start              # infrastructure (docker compose), all services and the web UI
#   deploy/dev-stack.sh restart [name...]  # e.g. after ./mvnw install -pl fleetloom-fleet -DskipTests: restart fleet
#   deploy/dev-stack.sh status
#   deploy/dev-stack.sh stop
#
# Logs go to .dev/logs. Build first with ./mvnw install -DskipTests. On a fresh database the demo map
# and storage data are created (deploy/examples/setup-demo-*.sh). The simulator drives two vehicles
# on the DEMO map; override with FLEETLOOM_SIM_ARGS. FLEETLOOM_NO_WEB=1 skips the web UI dev server.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN=$ROOT/.dev
mkdir -p "$RUN/logs"
V=0.1.0-SNAPSHOT

# name|port|jar
SERVICES=(
  "resource|8081|fleetloom-resource/target/fleetloom-resource-$V.jar"
  "fleet|8083|fleetloom-fleet/target/fleetloom-fleet-$V-exec.jar"
  "traffic|8084|fleetloom-traffic/target/fleetloom-traffic-$V-exec.jar"
  "dispatch|8082|fleetloom-dispatch/target/fleetloom-dispatch-$V.jar"
  "simulator|8090|fleetloom-simulator/target/fleetloom-simulator-$V-exec.jar"
  "gateway|8080|fleetloom-gateway/target/fleetloom-gateway-$V.jar"
)
SIM_ARGS=${FLEETLOOM_SIM_ARGS:-"--fleetloom.simulator.map.code=DEMO --fleetloom.simulator.vehicles[0].serial-number=AGV-001 --fleetloom.simulator.vehicles[0].initial-point=P10 --fleetloom.simulator.vehicles[1].serial-number=AGV-002 --fleetloom.simulator.vehicles[1].initial-point=P01"}

running() {
  [[ -f $RUN/$1.pid ]] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null
}

wait_for() {
  local name=$1 port=$2
  for _ in $(seq 1 90); do
    if curl -sf "localhost:$port/actuator/health" >/dev/null 2>&1; then
      echo "  $name up on $port"
      return 0
    fi
    running "$name" || { echo "  $name exited, see $RUN/logs/$name.log"; return 1; }
    sleep 1
  done
  echo "  $name did not become healthy on $port, see $RUN/logs/$name.log"
  return 1
}

start_service() {
  local name=$1 port=$2 jar=$3
  if running "$name"; then
    echo "  $name already running"
    return 0
  fi
  [[ -f $ROOT/$jar ]] || { echo "missing $jar, run ./mvnw install -DskipTests"; exit 1; }
  local args=()
  if [[ $name == simulator ]]; then
    read -r -a args <<<"$SIM_ARGS"
  fi
  # run a copy: rebuilding overwrites target/*.jar, which breaks a JVM still loading classes from it
  mkdir -p "$RUN/jars"
  cp "$ROOT/$jar" "$RUN/jars/$name.jar"
  nohup java -jar "$RUN/jars/$name.jar" "${args[@]}" >"$RUN/logs/$name.log" 2>&1 &
  echo $! >"$RUN/$name.pid"
  wait_for "$name" "$port"
  if [[ $name == resource ]]; then
    # the map and storage of deploy/examples, also when an older, smaller version is there
    if ! curl -sf localhost:8081/api/resource/v1/maps/DEMO/active | grep -q '"WS0"'; then
      "$ROOT/deploy/examples/setup-demo-map.sh"
    fi
    if ! curl -sf localhost:8081/api/resource/v1/zones | grep -q '"code":"STORE"'; then
      "$ROOT/deploy/examples/setup-demo-storage.sh"
    fi
  fi
}

stop_service() {
  local name=$1
  if running "$name"; then
    local pid
    pid=$(cat "$RUN/$name.pid")
    kill "$pid"
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    echo "  stopped $name"
  fi
  rm -f "$RUN/$name.pid"
}

start_web() {
  [[ -n ${FLEETLOOM_NO_WEB:-} ]] && return 0
  if running web; then
    echo "  web already running"
    return 0
  fi
  [[ -d $ROOT/fleetloom-web/node_modules ]] || (cd "$ROOT/fleetloom-web" && corepack pnpm install --frozen-lockfile)
  # its own session and process group: the dev server starts children, and stop() ends them all
  setsid nohup corepack pnpm --dir "$ROOT/fleetloom-web" dev --host 127.0.0.1 >"$RUN/logs/web.log" 2>&1 </dev/null &
  echo $! >"$RUN/web.pid"
  for _ in $(seq 1 60); do
    curl -sf localhost:5173 >/dev/null 2>&1 && { echo "  web up on 5173"; return 0; }
    sleep 1
  done
  echo "  web did not come up, see $RUN/logs/web.log"
  return 1
}

stop_web() {
  if running web; then
    # everything in its session: pnpm runs vite in a process group of its own
    pkill -s "$(cat "$RUN/web.pid")" || true
    echo "  stopped web"
  fi
  rm -f "$RUN/web.pid"
}

start() {
  docker compose -f "$ROOT/deploy/docker-compose.yml" up -d --wait
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name port jar <<<"$entry"
    start_service "$name" "$port" "$jar"
  done
  start_web
  echo "UI: http://localhost:5173 (admin / FLEETLOOM_ADMIN_PASSWORD, default admin12345)"
}

stop() {
  stop_web
  for ((i = ${#SERVICES[@]} - 1; i >= 0; i--)); do
    IFS='|' read -r name _ _ <<<"${SERVICES[$i]}"
    stop_service "$name"
  done
}

restart() {
  if [[ $# -eq 0 ]]; then
    stop
    start
    return
  fi
  for wanted in "$@"; do
    if [[ $wanted == web ]]; then
      stop_web
      start_web
      continue
    fi
    local found=
    for entry in "${SERVICES[@]}"; do
      IFS='|' read -r name port jar <<<"$entry"
      if [[ $name == "$wanted" ]]; then
        found=1
        stop_service "$name"
        start_service "$name" "$port" "$jar"
      fi
    done
    [[ -n $found ]] || { echo "unknown service $wanted"; exit 2; }
  done
}

status() {
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name port _ <<<"$entry"
    if running "$name"; then
      echo "  $name  pid $(cat "$RUN/$name.pid")  :$port"
    else
      echo "  $name  stopped"
    fi
  done
  if running web; then echo "  web  pid $(cat "$RUN/web.pid")  :5173"; else echo "  web  stopped"; fi
}

case ${1:-} in
  start) start ;;
  stop) stop ;;
  restart) shift; restart "$@" ;;
  status) status ;;
  *) echo "usage: $0 start|stop|restart [name...]|status"; exit 2 ;;
esac
