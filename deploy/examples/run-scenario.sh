#!/usr/bin/env bash
# Runs a scenario (SIM-12) on the simulator and prints its progress until it ends.
#   deploy/examples/run-scenario.sh [scenario.yaml]     default: scenario-demo.yaml next to this script
# Needs the dev stack (deploy/dev-stack.sh start). RCS_SIMULATOR_URL overrides http://localhost:8090.
set -euo pipefail
FILE=${1:-$(dirname "$0")/scenario-demo.yaml}
API=${RCS_SIMULATOR_URL:-http://localhost:8090}/api/simulator/v1/scenarios
show() {
  python3 -c '
import json, sys
d = json.load(sys.stdin)
if d["code"] != "OK":
    print(d["code"], d.get("message", ""))
else:
    r = {k: ("-" if v is None else v) for k, v in d["data"].items()}
    print("{state}: sent {sent}/{planned}, completed {completed}, rejected {rejected}, failed {failed}, open {open},"
          " avg {avgSeconds}s, p95 {p95Seconds}s, {perHour}/h".format(**r))'
}
curl -s -X POST "$API" -H 'Content-Type: application/yaml' --data-binary @"$FILE" | show
while sleep 5; do
  OUT=$(curl -s "$API/current" | show)
  echo "$OUT"
  [[ $OUT == *RUNNING* ]] || break
done
curl -s "$API/current" | python3 -c '
import json, sys
d = json.load(sys.stdin)["data"]
for s in d.get("streams", []):
    s = {k: ("-" if v is None else v) for k, v in s.items()}
    print("  {name:<20} completed {completed}/{planned}, rejected {rejected}, failed {failed}, waited {waited},"
          " departed {departed}, avg {avgSeconds}s, p95 {p95Seconds}s".format(**s))
for e in d["errors"]:
    print("  !", e)'
