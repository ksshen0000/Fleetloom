#!/usr/bin/env bash
# Demo storage data on map DEMO (run setup-demo-map.sh first). Objects that already exist are skipped.
#   grid:      zone A, a ground location L<rc> on every grid point P<rc>, shelves S1 in L00 and S2 in L02
#   warehouse: zones IN (docks IN-1..3), STORE (ST-01..12), OUT (docks OUT-1..3), PICK (stations PS-1/2);
#              shelves SH01..SH06 with goods in ST-01..06
#   the simulated vehicles AGV-001/002
set -uo pipefail
API=${FLEETLOOM_RESOURCE_URL:-http://localhost:8081}/api/resource/v1
post() { curl -s -o /dev/null -w '%{http_code}' -X POST "$API$1" -H 'Content-Type: application/json' -d "$2"; }

post /zones '{"code":"A","name":"Area A","type":"BUFFER"}' >/dev/null
for r in 0 1 2; do for c in 0 1 2; do
  post /storage-locations "{\"code\":\"L$r$c\",\"zoneCode\":\"A\",\"type\":\"GROUND\",\"mapCode\":\"DEMO\",\"accessPoint\":\"P$r$c\"}" >/dev/null
done; done
post /carrier-types '{"code":"SHELF","name":"Shelf","category":"SHELF","length":1,"width":1,"height":1.8,"maxLoad":500}' >/dev/null
for s in "S1 L00" "S2 L02"; do
  set -- $s
  post /carriers "{\"code\":\"$1\",\"typeCode\":\"SHELF\"}" >/dev/null
  post "/carriers/$1/place" "{\"locationCode\":\"$2\"}" >/dev/null
done

# the warehouse
post /zones '{"code":"IN","name":"Inbound docks","type":"INBOUND"}' >/dev/null
post /zones '{"code":"STORE","name":"Storage","type":"STORAGE"}' >/dev/null
post /zones '{"code":"OUT","name":"Outbound docks","type":"OUTBOUND"}' >/dev/null
post /zones '{"code":"PICK","name":"Picking stations","type":"PICKING"}' >/dev/null
location() {  # code zone type point
  post /storage-locations "{\"code\":\"$1\",\"zoneCode\":\"$2\",\"type\":\"$3\",\"mapCode\":\"DEMO\",\"accessPoint\":\"$4\"}" >/dev/null
}
for k in 1 2 3; do
  location "IN-$k" IN BUFFER "IN$k"
  location "OUT-$k" OUT BUFFER "OUT$k"
done
for k in 01 02 03 04 05 06 07 08 09 10 11 12; do location "ST-$k" STORE GROUND "ST$k"; done
for k in 1 2; do location "PS-$k" PICK STATION "PS$k"; done
SKUS=(SKU-APPLE SKU-PEAR SKU-PLUM SKU-KIWI SKU-LIME SKU-FIG)
for k in 1 2 3 4 5 6; do
  code="SH0$k"
  if [[ $(post /carriers "{\"code\":\"$code\",\"typeCode\":\"SHELF\"}") == 2* ]]; then
    post "/carriers/$code/place" "{\"locationCode\":\"ST-0$k\"}" >/dev/null
    curl -s -o /dev/null -X PUT "$API/carriers/$code/goods" -H 'Content-Type: application/json' \
      -d "[{\"sku\":\"${SKUS[$((k - 1))]}\",\"quantity\":$((k * 10)),\"unit\":\"box\"}]"
  fi
done

post /vehicle-types '{"code":"JACK","name":"Jacking AGV","category":"JACKING","length":1.2,"width":0.8,"maxSpeed":1.5,"maxLoad":500,"supportedActions":["pick","drop"]}' >/dev/null
for v in AGV-001 AGV-002; do
  post /vehicles "{\"code\":\"$v\",\"name\":\"$v\",\"typeCode\":\"JACK\",\"manufacturer\":\"FLEETLOOM-SIM\",\"serialNumber\":\"$v\",\"simulated\":true}" >/dev/null
done
curl -s "$API/storage-locations" | python3 -c 'import json,sys; print("locations:", [(l["code"], l["status"], l["carrierCode"]) for l in json.load(sys.stdin)["data"]])'
