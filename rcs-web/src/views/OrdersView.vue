<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { carriersApi, dispatchApi, fleetApi, mapsApi, storageApi } from '@/api'
import type { Carrier, CreateOrder, MapPoint, MapView, OrderStatus, OrderTemplate, OrderView, StorageLocation, VehicleStatus, Zone } from '@/api/types'
import OrderTemplates from '@/components/orders/OrderTemplates.vue'
import { act, confirm, formatTime } from '@/composables/notify'
import { usePolling } from '@/composables/usePolling'
import { coalesce, usePush, usePushReconnect } from '@/composables/usePush'
import { push } from '@/push/channel'
import { ORDER_LABELS, ORDER_TAG } from '@/map/colors'

const orders = ref<OrderView[]>([])
const statusFilter = ref<OrderStatus | ''>('')

const polling = usePolling(async () => {
  orders.value = await dispatchApi.orders(statusFilter.value || undefined, 200)
}, () => (push.status.value === 'online' ? 15000 : 2000))

const refreshSoon = coalesce(() => polling.refresh(), 300)
usePush('TransportOrderStatusChanged', refreshSoon)
usePushReconnect(refreshSoon)

watch(statusFilter, () => polling.refresh())

async function cancel(o: OrderView) {
  if (await confirm(`取消订单 ${o.orderNo}？已锁定的库位会释放。`)) {
    await act(() => dispatchApi.cancel(o.orderNo), '订单已取消')
    await polling.refresh()
  }
}

async function suspend(o: OrderView) {
  await act(() => dispatchApi.suspend(o.orderNo), '订单已挂起，车辆已停下')
  await polling.refresh()
}

async function resume(o: OrderView) {
  await act(() => dispatchApi.resume(o.orderNo), '订单已恢复')
  await polling.refresh()
}

function describe(o: OrderView): string {
  if (o.type === 'TRANSPORT') {
    return `${o.carrierCode}：${o.sourceLocation ?? '?'} → ${o.targetLocation ?? '?'}`
  }
  return `→ ${o.legs[0]?.point ?? '?'}`
}

const TYPE_LABELS = { TRANSPORT: '搬运', MOVE: '移动', CHARGE: '充电' }
const FINAL = ['COMPLETED', 'CANCELLED', 'REJECTED', 'FAILED']

function overdue(o: OrderView): boolean {
  return !!o.deadline && !FINAL.includes(o.status) && Date.parse(o.deadline) < Date.now()
}

// ------------------------------------------------------------------ create

const dialog = ref(false)
const form = reactive({
  type: 'TRANSPORT' as 'TRANSPORT' | 'MOVE',
  externalNo: '',
  priority: 5,
  carrierCode: '',
  targetMode: 'location' as 'location' | 'zone',
  targetLocation: '',
  targetZone: '',
  mapCode: '',
  targetPoint: '',
  vehicleCode: '',
  deadline: null as Date | null,
  template: '',
})
const templates = ref<OrderTemplate[]>([])
const templatesOpen = ref(false)
const carriers = ref<Carrier[]>([])
const emptyLocations = ref<StorageLocation[]>([])
const zones = ref<Zone[]>([])
const maps = ref<MapView[]>([])
const points = ref<MapPoint[]>([])
const vehicles = ref<VehicleStatus[]>([])

const storedCarriers = computed(() => carriers.value.filter((c) => c.locationType === 'STORAGE' && c.status === 'IDLE'))

async function openCreate() {
  dialog.value = true
  dispatchApi.templates().then((t) => (templates.value = t)).catch(() => {})
  const [c, l, z, m, v] = await Promise.all([
    carriersApi.list(),
    storageApi.locations({ status: 'EMPTY' }),
    storageApi.zones(),
    mapsApi.list(),
    fleetApi.vehicles().catch(() => []),
  ])
  carriers.value = c
  emptyLocations.value = l
  zones.value = z
  maps.value = m.filter((x) => x.activeVersionNo !== null)
  vehicles.value = v
  if (!form.mapCode && maps.value.length) {
    form.mapCode = maps.value[0].code
  }
}

watch(
  () => form.mapCode,
  async (code) => {
    points.value = code ? (await mapsApi.active(code)).points : []
  },
)

async function submit() {
  const order: CreateOrder = { type: form.type, priority: form.priority }
  if (form.externalNo) order.externalNo = form.externalNo
  if (form.vehicleCode) order.vehicleCode = form.vehicleCode
  if (form.deadline) order.deadline = form.deadline.toISOString()
  if (form.type === 'TRANSPORT') {
    order.carrierCode = form.carrierCode
    if (form.template) order.template = form.template
    if (form.targetMode === 'location') order.targetLocation = form.targetLocation
    else order.targetZone = form.targetZone
  } else {
    order.mapCode = form.mapCode
    order.targetPoint = form.targetPoint
  }
  const created = await act(() => dispatchApi.create(order))
  if (!created) {
    return
  }
  if (created.status === 'REJECTED') {
    ElMessage.error(`订单被拒绝：${created.reason}`)
  } else {
    dialog.value = false
    ElMessage.success(`已创建 ${created.orderNo}`)
  }
  await polling.refresh()
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="(label, s) in ORDER_LABELS" :key="s" :label="label" :value="s" />
      </el-select>
      <el-button @click="polling.refresh()">刷新</el-button>
      <span class="spacer" />
      <el-button @click="templatesOpen = true">订单模板</el-button>
      <el-button type="primary" @click="openCreate">新建订单</el-button>
    </div>

    <el-table :data="orders" size="small" stripe row-key="orderNo">
      <el-table-column prop="orderNo" label="订单号" width="130" />
      <el-table-column label="类型" width="70">
        <template #default="{ row }">{{ TYPE_LABELS[row.type as keyof typeof TYPE_LABELS] }}</template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="ORDER_TAG[row.status as OrderStatus]">{{ ORDER_LABELS[row.status as OrderStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="200">
        <template #default="{ row }">{{ describe(row) }}</template>
      </el-table-column>
      <el-table-column prop="assignedVehicle" label="车辆" width="100" />
      <el-table-column label="进度" min-width="180">
        <template #default="{ row }">
          <el-tag
            v-for="(l, i) in row.legs"
            :key="i"
            size="small"
            :type="l.status === 'DONE' ? 'success' : l.status === 'RUNNING' ? 'primary' : 'info'"
            class="leg"
          >{{ l.point }}{{ l.action ? ' ' + l.action : '' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="创建时间" width="176">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="截止时间" width="176">
        <template #default="{ row }">
          <span :class="{ overdue: overdue(row) }">{{ formatTime(row.deadline) }}</span>
          <el-tag v-if="overdue(row)" size="small" type="danger" class="leg">超时</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'EXECUTING'" link type="warning" @click="suspend(row)">挂起</el-button>
          <el-button v-if="row.status === 'SUSPENDED'" link type="primary" @click="resume(row)">恢复</el-button>
          <el-button v-if="['QUEUED', 'EXECUTING', 'SUSPENDED'].includes(row.status)" link type="danger" @click="cancel(row)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="新建订单" width="520px">
      <el-form label-width="96px">
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio-button value="TRANSPORT">搬运</el-radio-button>
            <el-radio-button value="MOVE">移动</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.type === 'TRANSPORT'">
          <el-form-item label="载具">
            <el-select v-model="form.carrierCode" filterable placeholder="选择在库的载具" style="width: 100%">
              <el-option v-for="c in storedCarriers" :key="c.code" :label="`${c.code}（${c.typeCode}，在 ${c.locationRef}）`" :value="c.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="模板">
            <el-select v-model="form.template" clearable placeholder="标准取放（STANDARD）" style="width: 100%">
              <el-option v-for="t in templates" :key="t.code" :label="`${t.name}（${t.code}）`" :value="t.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标">
            <el-radio-group v-model="form.targetMode">
              <el-radio value="location">指定库位</el-radio>
              <el-radio value="zone">库区内自动选择</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.targetMode === 'location'" label="目标库位">
            <el-select v-model="form.targetLocation" filterable placeholder="空闲库位" style="width: 100%">
              <el-option v-for="l in emptyLocations" :key="l.code" :label="`${l.code}（${l.zoneCode}，点位 ${l.accessPoint}）`" :value="l.code" />
            </el-select>
          </el-form-item>
          <el-form-item v-else label="目标库区">
            <el-select v-model="form.targetZone" placeholder="离源库位最近的空库位" style="width: 100%">
              <el-option v-for="z in zones" :key="z.code" :label="`${z.name}（${z.code}）`" :value="z.code" />
            </el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="地图">
            <el-select v-model="form.mapCode" style="width: 100%">
              <el-option v-for="m in maps" :key="m.code" :label="m.name" :value="m.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标点位">
            <el-select v-model="form.targetPoint" filterable style="width: 100%">
              <el-option v-for="p in points" :key="p.code" :label="`${p.code}（${p.type}）`" :value="p.code" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="优先级">
          <el-slider v-model="form.priority" :min="1" :max="9" show-stops style="width: 90%" />
        </el-form-item>
        <el-form-item label="指定车辆">
          <el-select v-model="form.vehicleCode" clearable placeholder="自动选择" style="width: 100%">
            <el-option v-for="v in vehicles" :key="v.code" :label="v.code" :value="v.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker v-model="form.deadline" type="datetime" placeholder="可选；超时未完成会告警" style="width: 100%" />
        </el-form-item>
        <el-form-item label="上游单号">
          <el-input v-model="form.externalNo" placeholder="可选；重复提交返回同一订单" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <OrderTemplates v-model="templatesOpen" />
  </div>
</template>

<style scoped>
.leg {
  margin-right: 4px;
}
.overdue {
  color: var(--el-color-danger);
}
</style>
