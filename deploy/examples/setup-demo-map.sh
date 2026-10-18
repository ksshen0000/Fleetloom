#!/usr/bin/env bash
# Creates and publishes the demo map DEMO: a 3x3 grid of points 4 m apart (P00..P22) plus a
# charging point CH1 left of P00. Needs rcs-resource on localhost:8081.
set -euo pipefail
cd "$(dirname "$0")"
API=${RCS_RESOURCE_URL:-http://localhost:8081}/api/resource/v1/maps

curl -sf -X POST "$API" -H 'Content-Type: application/json' -d '{"code":"DEMO","name":"Demo grid"}' >/dev/null \
  || echo "map DEMO exists already"
VERSION=$(curl -sf -X POST "$API/DEMO/versions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["versionNo"])')
curl -sf -X PUT "$API/DEMO/versions/$VERSION/content" -H 'Content-Type: application/json' -d @demo-map.json >/dev/null
curl -sf -X POST "$API/DEMO/versions/$VERSION/publish" >/dev/null
echo "published DEMO version $VERSION"
