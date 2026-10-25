#!/usr/bin/env bash
# A mixed fleet for the DEMO map (run setup-demo-map.sh and setup-demo-storage.sh first); existing
# types are updated, so it can be run again. Used by scenario-mixed-fleet.yaml.
#   vehicle types  JACK  jacking AGV           1.2 x 0.8 m, 1.5 m/s   shelves
#                  MINI  compact jacking AGV   0.9 x 0.6 m, 2.0 m/s   shelves, light
#                  HEAVY heavy jacking AGV     1.6 x 1.2 m, 0.8 m/s   shelves
#                  FORK  forklift AGV          2.0 x 1.0 m, 1.0 m/s   pallets
#   carrier types  SHELF for JACK, MINI and HEAVY; PALLET for FORK only
#   pallets        PL01, PL02 with SKU-RICE in the grid yard (zone A, L11 and L12)
# The narrow cross aisle WN4-WM-WS4 of the map is open to JACK and MINI only: HEAVY and FORK go round.
set -uo pipefail
API=${FLEETLOOM_RESOURCE_URL:-http://localhost:8081}/api/resource/v1
send() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$API$2" -H 'Content-Type: application/json' -d "$3"; }
upsert() {  # path code body: create, or update if it exists
  [[ $(send POST "$1" "$3") == 2* ]] || send PUT "$1/$2" "$3" >/dev/null
}

vehicle_type() {  # code name category length width speed load
  upsert /vehicle-types "$1" "{\"code\":\"$1\",\"name\":\"$2\",\"category\":\"$3\",\"length\":$4,\"width\":$5,\"maxSpeed\":$6,\"maxLoad\":$7,\"supportedActions\":[\"pick\",\"drop\"]}"
}
vehicle_type JACK '顶升 AGV' JACKING 1.2 0.8 1.5 500
vehicle_type MINI '小型顶升 AGV' JACKING 0.9 0.6 2.0 300
vehicle_type HEAVY '重载顶升 AGV' JACKING 1.6 1.2 0.8 1500
vehicle_type FORK '叉车 AGV' FORKLIFT 2.0 1.0 1.0 1200

upsert /carrier-types SHELF '{"code":"SHELF","name":"Shelf","category":"SHELF","length":1,"width":1,"height":1.8,"maxLoad":500,"compatibleVehicleTypes":["JACK","MINI","HEAVY"]}'
upsert /carrier-types PALLET '{"code":"PALLET","name":"Pallet","category":"PALLET","length":1.2,"width":1.0,"height":0.15,"maxLoad":1000,"compatibleVehicleTypes":["FORK"]}'

for p in "PL01 L11" "PL02 L12"; do
  set -- $p
  if [[ $(send POST /carriers "{\"code\":\"$1\",\"typeCode\":\"PALLET\"}") == 2* ]]; then
    send POST "/carriers/$1/place" "{\"locationCode\":\"$2\"}" >/dev/null
    send PUT "/carriers/$1/goods" '[{"sku":"SKU-RICE","quantity":40,"unit":"bag"}]' >/dev/null
  fi
done
curl -s "$API/vehicle-types" | python3 -c 'import json,sys; print("vehicle types:", [(t["code"], t["maxSpeed"]) for t in json.load(sys.stdin)["data"]])'
