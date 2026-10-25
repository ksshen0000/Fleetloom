<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { fleetApi, registryApi, simulatorApi } from '@/api'
import type { SimVehicle, VehicleType } from '@/api/types'
import { act, confirm } from '@/composables/notify'
import { usePolling } from '@/composables/usePolling'
import { planVehicles } from '@/simulator/batch'
import ScenarioPanel from '@/components/simulator/ScenarioPanel.vue'
import { useSession } from '@/stores/session'

/** Manufacturer the simulator reports (fleetloom.simulator.manufacturer); registered vehicles must match it. */
const MANUFACTURER = 'FLEETLOOM-SIM'

const vehicles = ref<SimVehicle[]>([])
const scale = ref(1)

const polling = usePolling(async () => {
  vehicles.value = await simulatorApi.vehicles()
}, 1000)

async function setScale(value: number) {
  await act(() => simulatorApi.timeScale(value), `时间倍率 ${value}x`)
}

const errorDialog = reactive({ open: false, serial: '', errorType: 'motorFault', level: 'FATAL' as 'WARNING' | 'FATAL', description: '', seconds: 0 })

function openError(v: SimVehicle) {
  Object.assign(errorDialog, { open: true, serial: v.serialNumber })
}

async function injectError() {
  const { serial, errorType, level, description, seconds } = errorDialog
  if (await act(() => simulatorApi.injectError(serial, { errorType, level, description: description || undefined, seconds: seconds || undefined }), '已注入故障')) {
    errorDialog.open = false
    await polling.refresh()
  }
}

async function run(action: () => Promise<unknown>, message: string) {
  await act(action, message)
  await polling.refresh()
}

const obstacle = (v: SimVehicle) => run(() => simulatorApi.obstacle(v.serialNumber, 10), '障碍物停车 10 秒')
const disconnect = (v: SimVehicle) => run(() => simulatorApi.disconnect(v.serialNumber, 15), '断线 15 秒')
const clearFaults = (v: SimVehicle) => run(() => simulatorApi.clearFaults(v.serialNumber), '已清除')

// ------------------------------------------------------------------ creating and removing vehicles (SIM-11)

const session = useSession()
const types = ref<VehicleType[]>([])
const points = ref<string[]>([])
const addDialog = reactive({ open: false, prefix: 'SIM', count: 2, battery: 100, register: true, typeCode: '' })

async function openAdd() {
  Object.assign(addDialog, { open: true })
  ;[types.value, points.value] = await Promise.all([registryApi.types().catch(() => []), simulatorApi.mapPoints().catch(() => [])])
  addDialog.typeCode ||= types.value[0]?.code ?? ''
}

const planned = computed(() => planVehicles(addDialog.prefix.trim() || 'SIM', addDialog.count,
  vehicles.value.map((v) => v.serialNumber), points.value, vehicles.value.map((v) => v.lastNodeId).filter((p): p is string => !!p),
  addDialog.battery))

async function addVehicles() {
  // a registered vehicle drives at the top speed of its type
  const type = addDialog.register ? types.value.find((t) => t.code === addDialog.typeCode) : undefined
  const batch = planned.value.map((v) => ({ ...v, maxSpeed: type?.maxSpeed }))
  if (!(await act(() => simulatorApi.add(batch), `已创建 ${batch.length} 台虚拟车`))) return
  if (addDialog.register && addDialog.typeCode) {
    // vehicles are only dispatched once they are in the vehicle registry
    for (const v of batch) {
      await act(() => registryApi.register({ code: v.serialNumber, name: v.serialNumber, typeCode: addDialog.typeCode,
        manufacturer: MANUFACTURER, serialNumber: v.serialNumber, simulated: true, initialPoint: v.initialPoint ?? null }))
    }
  }
  addDialog.open = false
  await polling.refresh()
}

async function remove(v: SimVehicle) {
  if (!(await confirm(`删除虚拟车 ${v.serialNumber}？车辆档案保留，需要时在「车辆」页删除。`))) return
  const removed = await act(async () => { await simulatorApi.remove(v.serialNumber); return true }, '已删除')
  if (!removed) return
  await polling.refresh()
  // it signs off over MQTT; once fleet control has seen that, drop it there too
  for (let i = 0; i < 10; i++) {
    const status = (await fleetApi.vehicles().catch(() => [])).find((s) => s.serialNumber === v.serialNumber)
    if (!status) return
    if (status.connection !== 'ONLINE') {
      await fleetApi.forget(status.code).catch(() => undefined)
      return
    }
    await new Promise((r) => setTimeout(r, 500))
  }
}
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="intro">
      创建和删除虚拟车（SIM-11），注入故障（SIM-09）以演练告警、挂起和交通处理。只有运行了 fleetloom-simulator 时可用；
      这里创建的车在模拟器重启后消失，常驻的车写在模拟器配置里。
    </el-alert>
    <div class="toolbar">
      <span>时间倍率</span>
      <el-select v-model="scale" style="width: 100px" @change="setScale">
        <el-option v-for="s in [1, 2, 5, 10]" :key="s" :label="`${s}x`" :value="s" />
      </el-select>
      <span class="spacer" />
      <el-button v-if="session.canWrite" type="primary" @click="openAdd">新增虚拟车</el-button>
      <el-button @click="polling.refresh()">刷新</el-button>
    </div>
    <el-alert v-if="polling.error.value" type="warning" :title="`模拟器不可用：${polling.error.value}`" :closable="false" />

    <el-table :data="vehicles" size="small" stripe row-key="serialNumber">
      <el-table-column prop="serialNumber" label="车辆" width="110" />
      <el-table-column label="位置" width="150">
        <template #default="{ row }">{{ row.lastNodeId || '—' }}（{{ row.x.toFixed(1) }}, {{ row.y.toFixed(1) }}）</template>
      </el-table-column>
      <el-table-column label="速度" width="80">
        <template #default="{ row }">{{ row.velocity.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="电量" width="80">
        <template #default="{ row }">{{ row.battery }}%{{ row.charging ? ' ⚡' : '' }}</template>
      </el-table-column>
      <el-table-column label="订单" prop="orderId" min-width="120" show-overflow-tooltip />
      <el-table-column label="注入的故障" min-width="160">
        <template #default="{ row }">
          <el-tag v-if="row.connectionDropped" size="small" type="danger" class="fault">断线中</el-tag>
          <el-tag v-for="f in row.faults" :key="f" size="small" type="warning" class="fault">{{ f }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="350" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="openError(row)">报错</el-button>
          <el-button link @click="obstacle(row)">障碍 10 秒</el-button>
          <el-button link @click="disconnect(row)">断线 15 秒</el-button>
          <el-button link type="primary" @click="clearFaults(row)">清除</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ScenarioPanel v-if="session.canWrite" class="scenario" />

    <el-dialog v-model="addDialog.open" title="新增虚拟车" width="520px">
      <el-form label-width="96px">
        <el-form-item label="编号前缀"><el-input v-model="addDialog.prefix" style="width: 160px" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="addDialog.count" :min="1" :max="50" /></el-form-item>
        <el-form-item label="初始电量 %"><el-input-number v-model="addDialog.battery" :min="0" :max="100" /></el-form-item>
        <el-form-item label="登记到平台">
          <el-checkbox v-model="addDialog.register">同时创建车辆档案，车型</el-checkbox>
          <el-select v-model="addDialog.typeCode" :disabled="!addDialog.register" size="small" style="width: 140px; margin-left: 8px">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="将创建">
          <div class="plan" data-testid="sim-plan">
            <div v-for="v in planned" :key="v.serialNumber">{{ v.serialNumber }} @ {{ v.initialPoint ?? '无空闲点位（未定位）' }}</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="addDialog.register && !addDialog.typeCode" @click="addVehicles">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="errorDialog.open" :title="`${errorDialog.serial} 上报错误`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="错误类型"><el-input v-model="errorDialog.errorType" /></el-form-item>
        <el-form-item label="等级">
          <el-radio-group v-model="errorDialog.level">
            <el-radio value="WARNING">WARNING</el-radio>
            <el-radio value="FATAL">FATAL（车辆停下）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="errorDialog.description" /></el-form-item>
        <el-form-item label="持续秒数">
          <el-input-number v-model="errorDialog.seconds" :min="0" />
          <span class="hint">0 = 直到清除</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="errorDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!errorDialog.errorType" @click="injectError">注入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.intro {
  margin-bottom: 8px;
}
.fault {
  margin-right: 4px;
}
.scenario {
  margin-top: 16px;
}
.plan {
  font-size: 12px;
  line-height: 20px;
  max-height: 160px;
  overflow: auto;
}
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
