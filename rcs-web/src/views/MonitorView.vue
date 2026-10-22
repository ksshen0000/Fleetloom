<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { dispatchApi, fleetApi, mapsApi, storageApi, trafficApi } from '@/api'
import type { DispatchVehicle, MapTopology, MapView, OrderStatus, OrderView, Reservation, RouteView, StorageLocation, VehicleStatus } from '@/api/types'
import MapCanvas, { type RouteMarker, type VehicleMarker } from '@/components/map/MapCanvas.vue'
import { act, confirm } from '@/composables/notify'
import { useAnimationClock, usePolling } from '@/composables/usePolling'
import { coalesce, usePush, usePushReconnect } from '@/composables/usePush'
import { LOCATION_COLORS, LOCATION_LABELS, ORDER_LABELS, ORDER_TAG, vehicleColor } from '@/map/colors'
import { PoseInterpolator } from '@/map/interpolation'
import { push } from '@/push/channel'

const maps = ref<MapView[]>([])
const mapCode = ref<string>('')
const topology = ref<MapTopology | null>(null)
const vehicles = ref<VehicleStatus[]>([])
const dispatchVehicles = ref<DispatchVehicle[]>([])
const routes = ref<RouteView[]>([])
const reservations = ref<Reservation[]>([])
const orders = ref<OrderView[]>([])
const locations = ref<StorageLocation[]>([])
const selectedVehicle = ref<string | null>(null)
const tab = ref('vehicles')

const interpolator = new PoseInterpolator()
const now = useAnimationClock()

// ------------------------------------------------------------------ data

async function loadMaps() {
  maps.value = (await mapsApi.list()).filter((m) => m.activeVersionNo !== null)
  if (!mapCode.value && maps.value.length > 0) {
    mapCode.value = maps.value[0].code
  }
}

watch(mapCode, async (code) => {
  topology.value = code ? await mapsApi.active(code) : null
  await locationPolling.refresh()
})

const live = usePolling(async () => {
  if (maps.value.length === 0) {
    await loadMaps()
  }
  const [v, r, res, o, dv] = await Promise.all([
    fleetApi.vehicles(),
    trafficApi.routes().catch(() => []),
    trafficApi.reservations().catch(() => []),
    dispatchApi.orders(undefined, 50).catch(() => []),
    dispatchApi.vehicles().catch(() => []),
  ])
  const t = performance.now()
  v.forEach((s) => interpolator.update(s.code, { x: s.x, y: s.y, theta: s.theta }, t))
  vehicles.value = v
  routes.value = r
  reservations.value = res
  orders.value = o
  dispatchVehicles.value = dv
}, () => (push.status.value === 'online' ? 5000 : 1000))

const locationPolling = usePolling(async () => {
  if (mapCode.value) {
    locations.value = await storageApi.locations({ map: mapCode.value })
  }
}, 5000)

// ------------------------------------------------------------------ push: state as it happens, polling only fills gaps

const refreshSoon = coalesce(() => live.refresh())

usePush<{ status: VehicleStatus }>('VehicleStateUpdated', ({ payload: { status } }) => {
  interpolator.update(status.code, { x: status.x, y: status.y, theta: status.theta }, performance.now())
  const i = vehicles.value.findIndex((v) => v.code === status.code)
  if (i >= 0) vehicles.value.splice(i, 1, status)
  else refreshSoon()
})

usePush<{ order: OrderView; previous: OrderStatus | null }>('TransportOrderStatusChanged', ({ payload: { order } }) => {
  const i = orders.value.findIndex((o) => o.orderNo === order.orderNo)
  if (i >= 0) orders.value.splice(i, 1, order)
  else orders.value.unshift(order)
  refreshSoon()
})

// routes, reservations and dispatch state are not pushed themselves; these events say they changed
usePush(['RouteProgressed', 'TrafficBlocked', 'VehicleConnectionChanged', 'VehicleForgotten'], refreshSoon)

usePush<{ mapCode: string }>('MapPublished', async ({ payload }) => {
  await loadMaps()
  if (payload.mapCode === mapCode.value) {
    topology.value = await mapsApi.active(payload.mapCode)
  }
})

usePushReconnect(refreshSoon)

// ------------------------------------------------------------------ scene

const vehicleCodes = computed(() => vehicles.value.map((v) => v.code))
const colorOf = (code: string) => vehicleColor(code, vehicleCodes.value)

const markers = computed<VehicleMarker[]>(() => {
  void now.value
  return vehicles.value
    .filter((v) => v.mapCode === mapCode.value && v.positionInitialized)
    .map((v) => {
      const pose = interpolator.at(v.code, now.value) ?? v
      return {
        code: v.code,
        x: pose.x,
        y: pose.y,
        theta: pose.theta,
        color: colorOf(v.code),
        loaded: v.loadIds.length > 0,
        dimmed: v.connection !== 'ONLINE',
      }
    })
})

const routeMarkers = computed<RouteMarker[]>(() =>
  routes.value
    .filter((r) => r.mapCode === mapCode.value && (r.status === 'MOVING' || r.status === 'WAITING'))
    .map((r) => ({ vehicleCode: r.vehicleCode, nodes: r.nodes.slice(r.passedIndex), releasedIndex: r.releasedIndex - r.passedIndex, color: colorOf(r.vehicleCode) })),
)

const reservationColors = computed(
  () => new Map(reservations.value.filter((r) => r.mapCode === mapCode.value).map((r) => [r.resource, colorOf(r.vehicleCode)])),
)

const activeOrders = computed(() => orders.value.filter((o) => !['COMPLETED', 'CANCELLED', 'REJECTED', 'FAILED'].includes(o.status)))
const recentOrders = computed(() => orders.value.slice(0, 20))

function routeOf(code: string) {
  return routes.value.find((r) => r.vehicleCode === code && (r.status === 'MOVING' || r.status === 'WAITING'))
}

function dispatchOf(code: string) {
  return dispatchVehicles.value.find((v) => v.code === code)
}

// ------------------------------------------------------------------ commands

async function onPointClick(point: string) {
  if (!selectedVehicle.value) {
    return
  }
  const vehicle = selectedVehicle.value
  if (await confirm(`让 ${vehicle} 移动到 ${point}？`)) {
    await act(() => trafficApi.move(vehicle, point), `${vehicle} 前往 ${point}`)
  }
}

async function pause(v: VehicleStatus) {
  await act(() => (v.paused ? fleetApi.resume(v.code) : fleetApi.pause(v.code)), v.paused ? '已恢复' : '已暂停')
}

async function cancelRoute(code: string) {
  if (await confirm(`取消 ${code} 当前的路线？车辆将停下。`)) {
    await act(() => trafficApi.cancel(code), '已取消')
  }
}
</script>

<template>
  <div class="monitor">
    <div class="map-pane">
      <div class="map-toolbar">
        <el-select v-model="mapCode" placeholder="选择地图" style="width: 240px">
          <el-option v-for="m in maps" :key="m.code" :label="`${m.name}（${m.code} v${m.activeVersionNo}）`" :value="m.code" />
        </el-select>
        <span class="legend">
          <span v-for="(color, status) in LOCATION_COLORS" :key="status" class="legend-item">
            <i :style="{ background: color }" />{{ LOCATION_LABELS[status] }}
          </span>
        </span>
        <span class="spacer" />
        <el-tag v-if="selectedVehicle" closable type="primary" @close="selectedVehicle = null">
          已选 {{ selectedVehicle }}：点击地图点位让它移动过去
        </el-tag>
        <el-tag v-if="live.error.value" type="danger">数据刷新失败：{{ live.error.value }}</el-tag>
      </div>
      <MapCanvas
        :content="topology"
        :locations="locations"
        :vehicles="markers"
        :routes="routeMarkers"
        :reservations="reservationColors"
        @point-click="onPointClick"
      />
    </div>

    <div class="side">
      <el-tabs v-model="tab" stretch>
        <el-tab-pane :label="`车辆（${vehicles.length}）`" name="vehicles">
          <el-empty v-if="vehicles.length === 0" description="没有发现车辆" />
          <el-card
            v-for="v in vehicles"
            :key="v.code"
            shadow="never"
            class="vehicle-card"
            :class="{ selected: selectedVehicle === v.code }"
            @click="selectedVehicle = selectedVehicle === v.code ? null : v.code"
          >
            <div class="row">
              <span class="dot" :style="{ background: colorOf(v.code) }" />
              <b>{{ v.code }}</b>
              <el-tag size="small" :type="v.connection === 'ONLINE' ? 'success' : 'danger'">{{ v.connection }}</el-tag>
              <el-tag v-if="v.paused" size="small" type="warning">暂停</el-tag>
              <el-tag v-if="v.charging" size="small" type="success">充电中</el-tag>
              <span class="spacer" />
              <span class="battery">{{ v.battery.toFixed(0) }}%</span>
            </div>
            <div class="meta">
              位置 {{ v.lastNodeId || '—' }} · {{ v.velocity.toFixed(2) }} m/s
              <template v-if="v.loadIds.length"> · 载 {{ v.loadIds.join(', ') }}</template>
            </div>
            <div v-if="routeOf(v.code)" class="meta">
              路线 → {{ routeOf(v.code)!.targetPoint }}（{{ routeOf(v.code)!.status === 'WAITING' ? '等待' : '行驶' }}
              <template v-if="routeOf(v.code)!.blockedBy">，被 {{ routeOf(v.code)!.blockedBy }} 阻挡</template>）
            </div>
            <div v-if="dispatchOf(v.code)?.currentOrder" class="meta">订单 {{ dispatchOf(v.code)!.currentOrder }}</div>
            <div v-for="e in v.errors" :key="e.errorType" class="error">{{ e.level }} {{ e.errorType }}：{{ e.description }}</div>
            <div class="actions" @click.stop>
              <el-button size="small" @click="pause(v)">{{ v.paused ? '恢复' : '暂停' }}</el-button>
              <el-button v-if="routeOf(v.code)" size="small" type="danger" plain @click="cancelRoute(v.code)">取消路线</el-button>
            </div>
          </el-card>
        </el-tab-pane>

        <el-tab-pane :label="`订单（进行中 ${activeOrders.length}）`" name="orders">
          <el-empty v-if="recentOrders.length === 0" description="暂无订单" />
          <div v-for="o in recentOrders" :key="o.orderNo" class="order">
            <div class="row">
              <b>{{ o.orderNo }}</b>
              <el-tag size="small" :type="ORDER_TAG[o.status]">{{ ORDER_LABELS[o.status] }}</el-tag>
              <span class="spacer" />
              <span class="meta">{{ o.assignedVehicle ?? '未分配' }}</span>
            </div>
            <div class="meta">
              <template v-if="o.type === 'TRANSPORT'">{{ o.carrierCode }}：{{ o.sourceLocation }} → {{ o.targetLocation }}</template>
              <template v-else>{{ o.type }} → {{ o.legs[0]?.point }}</template>
            </div>
            <el-steps :active="o.legs.filter((l) => l.status === 'DONE').length" simple class="legs">
              <el-step v-for="(l, i) in o.legs" :key="i" :title="`${l.point}${l.action ? ' ' + l.action : ''}`" />
            </el-steps>
            <div v-if="o.reason" class="meta">{{ o.reason }}</div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.monitor {
  display: flex;
  height: 100%;
}
.map-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.map-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.legend {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.legend-item i {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: 4px;
  border-radius: 2px;
  vertical-align: -1px;
}
.spacer {
  flex: 1;
}
.side {
  width: 340px;
  border-left: 1px solid var(--el-border-color-light);
  padding: 0 12px;
  overflow: auto;
}
.vehicle-card {
  margin-bottom: 8px;
  cursor: pointer;
}
.vehicle-card.selected {
  border-color: var(--el-color-primary);
}
.row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.battery {
  font-variant-numeric: tabular-nums;
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.error {
  font-size: 12px;
  color: var(--el-color-danger);
  margin-top: 4px;
}
.actions {
  margin-top: 8px;
}
.order {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.legs {
  margin-top: 6px;
  padding: 6px 8px;
}
</style>
