#!/usr/bin/env bash
# Creates and publishes the demo map DEMO (a new version if it exists already). Needs fleetloom-resource on
# localhost:8081. Two parts:
#   - a 3x3 grid of points 4 m apart (P00..P22), all paths both ways, charging point CH1 left of P00
#   - east of it a small warehouse with one-way aisles: a loop (WS* eastbound on y=0, WE* north,
#     WN* westbound on y=12, WW* south) with a southbound cross aisle WN4-WM-WS4, and dead-end bays
#     off the aisles: storage ST01..ST12, inbound docks IN1..3 (north wall), outbound docks OUT1..3
#     (south wall), picking stations PS1/PS2 and charger CH2 (east wall), parking PARK1..3.
#     It is entered from P02 and left towards P22.
set -euo pipefail
cd "$(dirname "$0")"
API=${FLEETLOOM_RESOURCE_URL:-http://localhost:8081}/api/resource/v1/maps

curl -sf -X POST "$API" -H 'Content-Type: application/json' -d '{"code":"DEMO","name":"Demo grid"}' >/dev/null \
  || echo "map DEMO exists already"
VERSION=$(curl -sf -X POST "$API/DEMO/versions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["versionNo"])')
curl -sf -X PUT "$API/DEMO/versions/$VERSION/content" -H 'Content-Type: application/json' -d @demo-map.json >/dev/null
curl -sf -X POST "$API/DEMO/versions/$VERSION/publish" >/dev/null
echo "published DEMO version $VERSION"
