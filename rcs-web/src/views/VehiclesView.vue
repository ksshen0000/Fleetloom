<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { dispatchApi, fleetApi, mapsApi, registryApi, trafficApi } from '@/api'
import type { DispatchVehicle, MapPoint, RegisteredVehicle, RouteView, VehicleStatus, VehicleType } from '@/api/types'
import { act, confirm } from '@/composables/notify'
import { usePolling } from '@/composables/usePolling'
import { coalesce, usePush, usePushReconnect } from '@/composables/usePush'
import { push } from '@/push/channel'
import VehicleHistory from '@/components/vehicles/VehicleHistory.vue'
import VehicleMessages from '@/components/vehicles/VehicleMessages.vue'

const tab = ref('live')
const live = ref<VehicleStatus[]>([])
const dispatchView = ref<DispatchVehicle[]>([])
const routes = ref<RouteView[]>([])
const registered = ref<RegisteredVehicle[]>([])
const types = ref<VehicleType[]>([])

const polling = usePolling(async () => {
  const [l, d, r] = await Promise.all([
    fleetApi.vehicles(),
    dispatchApi.vehicles().catch(() => []),
    trafficApi.routes().catch(() => []),
  ])
  live.value = l
  dispatchView.value = d
  routes.value = r
}, () => (push.status.value === 'online' ? 5000 : 1000))

const refreshSoon = coalesce(() => polling.refresh())

usePush<{ status: VehicleStatus }>('VehicleStateUpdated', ({ payload: { status } }) => {
  const i = live.value.findIndex((v) => v.code === status.code)
  if (i >= 0) live.value.splice(i, 1, status)
  else refreshSoon()
})
usePush(['RouteProgressed', 'TrafficBlocked', 'VehicleConnectionChanged', 'VehicleForgotten', 'TransportOrderStatusChanged'], refreshSoon)
usePushReconnect(refreshSoon)

usePolling(async () => {
  const [v, t] = await Promise.all([registryApi.vehicles(), registryApi.types()])
  registered.value = v
  types.value = t
}, 10000)

const rows = computed(() =>
  live.value.map((v) => ({
    ...v,
    registry: registered.value.find((r) => r.manufacturer === v.manufacturer && r.serialNumber === v.serialNumber),
    dispatch: dispatchView.value.find((d) => d.code === v.code),
    route: routes.value.find((r) => r.vehicleCode === v.code && (r.status === 'MOVING' || r.status === 'WAITING')),
  })),
)

// ------------------------------------------------------------------ manual control (VEH-04..06)

async function togglePause(v: VehicleStatus) {
  await act(() => (v.paused ? fleetApi.resume(v.code) : fleetApi.pause(v.code)), v.paused ? '已恢复' : '已暂停')
}

async function cancelRoute(code: string) {
  if (await confirm(`取消 ${code} 当前的路线？`)) {
    await act(() => trafficApi.cancel(code), '已取消')
  }
}

async function forget(code: string) {
  if (await confirm(`从列表移除离线车辆 ${code}？它的离线和故障告警一并关闭；车辆再次上线时会重新出现。`)) {
    await act(() => fleetApi.forget(code), '已移除')
    await polling.refresh()
  }
}

async function setAccepting(code: string, accepting: boolean) {
  await act(() => dispatchApi.setAccepting(code, accepting), accepting ? '开始接单' : '停止接单')
  await polling.refresh()
}

const pointDialog = reactive({ open: false, mode: 'move' as 'move' | 'relocate', vehicle: '', mapCode: '', point: '' })
const mapPoints = ref<MapPoint[]>([])

async function openPointDialog(v: VehicleStatus, mode: 'move' | 'relocate') {
  const maps = (await mapsApi.list()).filter((m) => m.activeVersionNo !== null)
  const mapCode = v.mapCode || maps[0]?.code || ''
  Object.assign(pointDialog, { open: true, mode, vehicle: v.code, mapCode, point: '' })
  mapPoints.value = mapCode ? (await mapsApi.active(mapCode)).points : []
}

async function submitPoint() {
  const { vehicle, point, mapCode, mode } = pointDialog
  if (mode === 'move') {
    await act(() => trafficApi.move(vehicle, point), `${vehicle} 前往 ${point}`)
  } else {
    const p = mapPoints.value.find((x) => x.code === point)!
    await act(() => fleetApi.relocate(vehicle, { x: p.x, y: p.y, theta: p.theta ?? 0, mapCode, pointCode: point }), '已重定位')
  }
  pointDialog.open = false
}

// ------------------------------------------------------------------ registry (VEH-01, VEH-02)

const vehicleDialog = reactive({ open: false, editing: false })
const vehicleForm = reactive<Partial<RegisteredVehicle>>({})

function openVehicle(v?: RegisteredVehicle) {
  vehicleDialog.open = true
  vehicleDialog.editing = !!v
  Object.assign(vehicleForm, v ?? {
    code: '', name: '', typeCode: types.value[0]?.code ?? '', manufacturer: 'RCS3000-SIM', serialNumber: '',
    simulated: true, enabled: true, initialPoint: null, chargeThresholdLow: 20, chargeThresholdHigh: 90,
  })
}

async function saveVehicle() {
  const saved = await act(() => (vehicleDialog.editing ? registryApi.update(vehicleForm as RegisteredVehicle) : registryApi.register(vehicleForm)), '已保存')
  if (saved) {
    vehicleDialog.open = false
    registered.value = await registryApi.vehicles()
  }
}

async function removeVehicle(v: RegisteredVehicle) {
  if (await confirm(`删除车辆档案 ${v.code}？`)) {
    await act(() => registryApi.remove(v.code), '已删除')
    registered.value = await registryApi.vehicles()
  }
}

const typeDialog = ref(false)
const typeForm = reactive<VehicleType>({ code: '', name: '', category: 'JACKING', length: 1.2, width: 0.8, maxSpeed: 1.5, maxLoad: 500, supportedActions: ['pick', 'drop'] })

async function saveType() {
  if (await act(() => registryApi.createType({ ...typeForm }), '已保存')) {
    typeDialog.value = false
    types.value = await registryApi.types()
  }
}

const messages = reactive({ open: false, code: '' })

function showMessages(code: string) {
  Object.assign(messages, { open: true, code })
}

const history = reactive({ open: false, code: '' })

function showHistory(code: string) {
  Object.assign(history, { open: true, code })
}

async function removeType(t: VehicleType) {
  if (await confirm(`删除车型 ${t.code}？`)) {
    await act(() => registryApi.deleteType(t.code), '已删除')
    types.value = await registryApi.types()
  }
}
</script>

<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="实时状态" name="live">
        <el-table :data="rows" size="small" stripe row-key="code">
          <el-table-column prop="code" label="车辆" width="100" />
          <el-table-column label="档案" width="90">
            <template #default="{ row }">
              <span v-if="row.registry">{{ row.registry.code }}</span>
              <el-tag v-else size="small" type="warning">未登记</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="连接" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.connection === 'ONLINE' ? 'success' : 'danger'">{{ row.connection }}</el-tag>
              <el-tag v-if="row.paused" size="small" type="warning">暂停</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="电量" width="120">
            <template #default="{ row }">
              <el-progress :percentage="Math.round(row.battery)" :status="row.battery < 20 ? 'exception' : row.charging ? 'success' : undefined" />
            </template>
          </el-table-column>
          <el-table-column label="位置" width="140">
            <template #default="{ row }">{{ row.lastNodeId || '—' }}（{{ row.x.toFixed(1) }}, {{ row.y.toFixed(1) }}）</template>
          </el-table-column>
          <el-table-column label="路线 / 订单" min-width="180">
            <template #default="{ row }">
              <div v-if="row.route">→ {{ row.route.targetPoint }} {{ row.route.status }}<span v-if="row.route.blockedBy">（等 {{ row.route.blockedBy }}）</span></div>
              <div v-if="row.dispatch?.currentOrder">{{ row.dispatch.currentOrder }}</div>
            </template>
          </el-table-column>
          <el-table-column label="接单" width="110">
            <template #default="{ row }">
              <el-switch v-if="row.dispatch" :model-value="row.dispatch.accepting" @change="(v: string | number | boolean) => setAccepting(row.code, !!v)" />
              <div v-if="row.dispatch?.unavailable" class="hint">{{ row.dispatch.unavailable }}</div>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="140">
            <template #default="{ row }">
              <div v-for="e in row.errors" :key="e.errorType" class="error">{{ e.errorType }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="340" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openPointDialog(row, 'move')">移动</el-button>
              <el-button link @click="togglePause(row)">{{ row.paused ? '恢复' : '暂停' }}</el-button>
              <el-button v-if="row.route" link type="danger" @click="cancelRoute(row.code)">取消路线</el-button>
              <el-button link @click="openPointDialog(row, 'relocate')">重定位</el-button>
              <el-button link @click="showMessages(row.code)">报文</el-button>
              <el-button link @click="showHistory(row.code)">历史</el-button>
              <el-button v-if="row.connection !== 'ONLINE'" link type="danger" @click="forget(row.code)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="车辆档案" name="registry">
        <div class="toolbar">
          <span class="spacer" />
          <el-button type="primary" @click="openVehicle()">登记车辆</el-button>
        </div>
        <el-table :data="registered" size="small" stripe>
          <el-table-column prop="code" label="编码" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="typeCode" label="车型" />
          <el-table-column label="厂商 / 序列号" min-width="180">
            <template #default="{ row }">{{ row.manufacturer }} / {{ row.serialNumber }}</template>
          </el-table-column>
          <el-table-column label="充电阈值" width="110">
            <template #default="{ row }">{{ row.chargeThresholdLow }}% / {{ row.chargeThresholdHigh }}%</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              <el-tag v-if="row.simulated" size="small">模拟</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="openVehicle(row)">编辑</el-button>
              <el-button link type="danger" @click="removeVehicle(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="车型" name="types">
        <div class="toolbar">
          <span class="spacer" />
          <el-button type="primary" @click="typeDialog = true">新建车型</el-button>
        </div>
        <el-table :data="types" size="small" stripe>
          <el-table-column prop="code" label="编码" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="category" label="类别" />
          <el-table-column label="尺寸 (m)">
            <template #default="{ row }">{{ row.length }} × {{ row.width }}</template>
          </el-table-column>
          <el-table-column prop="maxSpeed" label="最大速度 (m/s)" />
          <el-table-column prop="maxLoad" label="最大载重 (kg)" />
          <el-table-column label="支持动作">
            <template #default="{ row }">{{ row.supportedActions.join(', ') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }"><el-button link type="danger" @click="removeType(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="pointDialog.open" :title="pointDialog.mode === 'move' ? `移动 ${pointDialog.vehicle}` : `重定位 ${pointDialog.vehicle}`" width="420px">
      <p v-if="pointDialog.mode === 'relocate'" class="hint">告诉车辆它现在站在哪个点位上（例如被人工推动之后）。车辆必须没有进行中的订单。</p>
      <el-select v-model="pointDialog.point" filterable placeholder="选择点位" style="width: 100%">
        <el-option v-for="p in mapPoints" :key="p.code" :label="`${p.code}（${p.type}）`" :value="p.code" />
      </el-select>
      <template #footer>
        <el-button @click="pointDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!pointDialog.point" @click="submitPoint">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="vehicleDialog.open" :title="vehicleDialog.editing ? '编辑车辆' : '登记车辆'" width="480px">
      <el-form label-width="110px">
        <el-form-item label="编码"><el-input v-model="vehicleForm.code" :disabled="vehicleDialog.editing" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="vehicleForm.name" /></el-form-item>
        <el-form-item label="车型">
          <el-select v-model="vehicleForm.typeCode" style="width: 100%">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="厂商"><el-input v-model="vehicleForm.manufacturer" /></el-form-item>
        <el-form-item label="序列号"><el-input v-model="vehicleForm.serialNumber" /></el-form-item>
        <el-form-item label="初始点位"><el-input v-model="vehicleForm.initialPoint" placeholder="可选" /></el-form-item>
        <el-form-item label="低电量阈值 %"><el-input-number v-model="vehicleForm.chargeThresholdLow" :min="0" :max="100" /></el-form-item>
        <el-form-item label="满电阈值 %"><el-input-number v-model="vehicleForm.chargeThresholdHigh" :min="0" :max="100" /></el-form-item>
        <el-form-item label="模拟车"><el-switch v-model="vehicleForm.simulated" /></el-form-item>
        <el-form-item v-if="vehicleDialog.editing" label="启用"><el-switch v-model="vehicleForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vehicleDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveVehicle">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="typeDialog" title="新建车型" width="460px">
      <el-form label-width="110px">
        <el-form-item label="编码"><el-input v-model="typeForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="typeForm.name" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="typeForm.category" style="width: 100%">
            <el-option v-for="c in ['JACKING', 'FORKLIFT', 'ROLLER', 'TOWING']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="长 × 宽 (m)">
          <el-input-number v-model="typeForm.length" :min="0.1" :step="0.1" /> × <el-input-number v-model="typeForm.width" :min="0.1" :step="0.1" />
        </el-form-item>
        <el-form-item label="最大速度 m/s"><el-input-number v-model="typeForm.maxSpeed" :min="0.1" :step="0.1" /></el-form-item>
        <el-form-item label="最大载重 kg"><el-input-number v-model="typeForm.maxLoad" :min="1" /></el-form-item>
        <el-form-item label="支持动作">
          <el-select v-model="typeForm.supportedActions" multiple allow-create filterable style="width: 100%">
            <el-option v-for="a in ['pick', 'drop', 'startCharging', 'stopCharging']" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <VehicleMessages v-model="messages.open" :vehicle-code="messages.code" />
    <VehicleHistory v-model="history.open" :vehicle-code="history.code" />
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.error {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
