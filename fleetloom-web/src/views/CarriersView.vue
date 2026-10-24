<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { carriersApi, storageApi } from '@/api'
import type { Carrier, CarrierMovement, CarrierType, StockedGoods, StorageLocation } from '@/api/types'
import CarrierGoods from '@/components/carriers/CarrierGoods.vue'
import { act, confirm, formatTime } from '@/composables/notify'
import { useSession } from '@/stores/session'

const types = ref<CarrierType[]>([])
const carriers = ref<Carrier[]>([])

async function load() {
  ;[types.value, carriers.value] = await Promise.all([carriersApi.types(), carriersApi.list()])
}

onMounted(load)

const STATUS_LABELS = { IDLE: '空闲', IN_TRANSIT: '运输中', DISABLED: '禁用' }
const WHERE = { STORAGE: '库位', VEHICLE: '车上', NONE: '不在系统内' }

// ------------------------------------------------------------------ types (CAR-01)

const typeDialog = reactive({ open: false, editing: false })
const typeForm = reactive<CarrierType>({ code: '', name: '', category: 'SHELF', length: 1, width: 1, height: 1, maxLoad: 500, compatibleVehicleTypes: [] })

function openType(t?: CarrierType) {
  Object.assign(typeDialog, { open: true, editing: !!t })
  Object.assign(typeForm, t ? { ...t, compatibleVehicleTypes: [...t.compatibleVehicleTypes] }
    : { code: '', name: '', category: 'SHELF', length: 1, width: 1, height: 1, maxLoad: 500, compatibleVehicleTypes: [] })
}

async function saveType() {
  if (await act(() => (typeDialog.editing ? carriersApi.updateType({ ...typeForm }) : carriersApi.createType({ ...typeForm })), '已保存')) {
    typeDialog.open = false
    await load()
  }
}

async function deleteType(t: CarrierType) {
  if (await confirm(`删除载具类型 ${t.code}？`)) {
    await act(() => carriersApi.deleteType(t.code), '已删除')
    await load()
  }
}

// ------------------------------------------------------------------ carriers (CAR-02, CAR-06)

const carrierDialog = ref(false)
const carrierForm = reactive({ code: '', typeCode: '' })

async function createCarrier() {
  if (await act(() => carriersApi.create({ ...carrierForm }), '已创建')) {
    carrierDialog.value = false
    await load()
  }
}

const placeDialog = reactive({ open: false, carrier: '', location: '' })
const emptyLocations = ref<StorageLocation[]>([])

async function openPlace(c: Carrier) {
  Object.assign(placeDialog, { open: true, carrier: c.code, location: '' })
  emptyLocations.value = await storageApi.locations({ status: 'EMPTY' })
}

async function place() {
  if (await act(() => carriersApi.place(placeDialog.carrier, placeDialog.location), '已登记放入')) {
    placeDialog.open = false
    await load()
  }
}

async function takeOut(c: Carrier) {
  if (await confirm(`登记 ${c.code} 已被人工取出 ${c.locationRef}？`)) {
    await act(() => carriersApi.takeOut(c.code), '已登记取出')
    await load()
  }
}

async function toggleDisabled(c: Carrier) {
  await act(() => (c.status === 'DISABLED' ? carriersApi.enable(c.code) : carriersApi.disable(c.code)))
  await load()
}

async function remove(c: Carrier) {
  if (await confirm(`删除载具 ${c.code}？`)) {
    await act(() => carriersApi.remove(c.code), '已删除')
    await load()
  }
}

// ------------------------------------------------------------------ goods (CAR-05)

const session = useSession()
const goodsDrawer = reactive({ open: false, carrier: '' })
const skuQuery = ref('')
const found = ref<StockedGoods[] | null>(null)

function showGoods(c: Carrier) {
  Object.assign(goodsDrawer, { open: true, carrier: c.code })
}

async function findGoods() {
  const sku = skuQuery.value.trim()
  found.value = sku ? await carriersApi.findGoods(sku) : null
}

const history = reactive({ open: false, carrier: '', movements: [] as CarrierMovement[] })

async function showHistory(c: Carrier) {
  Object.assign(history, { open: true, carrier: c.code, movements: await carriersApi.movements(c.code) })
}

function where(type: string, ref: string | null): string {
  return type === 'NONE' ? '系统外' : `${WHERE[type as keyof typeof WHERE]} ${ref}`
}
</script>

<template>
  <div class="page">
    <h3>载具</h3>
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <el-input v-model="skuQuery" placeholder="按 SKU 查找货物" clearable style="width: 220px" @keyup.enter="findGoods" @clear="found = null" />
      <el-button @click="findGoods">查找</el-button>
      <span class="spacer" />
      <el-button type="primary" @click="carrierDialog = true">新建载具</el-button>
    </div>
    <el-table v-if="found" :data="found" size="small" stripe class="found" data-testid="goods-found">
      <el-table-column label="SKU" width="160"><template #default="{ row }">{{ row.goods.sku }}</template></el-table-column>
      <el-table-column label="名称" min-width="140"><template #default="{ row }">{{ row.goods.name }}</template></el-table-column>
      <el-table-column label="数量" width="120"><template #default="{ row }">{{ row.goods.quantity }} {{ row.goods.unit ?? '' }}</template></el-table-column>
      <el-table-column label="批次" width="110"><template #default="{ row }">{{ row.goods.batchNo }}</template></el-table-column>
      <el-table-column prop="carrierCode" label="载具" width="130" />
      <el-table-column label="位置" min-width="150"><template #default="{ row }">{{ row.locationType ? where(row.locationType, row.locationRef) : '' }}</template></el-table-column>
      <template #empty>没有这个 SKU 的货物</template>
    </el-table>
    <el-table :data="carriers" size="small" stripe>
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="typeCode" label="类型" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ STATUS_LABELS[row.status as keyof typeof STATUS_LABELS] }}</template>
      </el-table-column>
      <el-table-column label="位置" min-width="150">
        <template #default="{ row }">{{ where(row.locationType, row.locationRef) }}</template>
      </el-table-column>
      <el-table-column label="最近移动" width="160">
        <template #default="{ row }">{{ formatTime(row.lastMovedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.locationType === 'NONE'" link type="primary" @click="openPlace(row)">放入库位</el-button>
          <el-button v-if="row.locationType === 'STORAGE'" link @click="takeOut(row)">人工取出</el-button>
          <el-button link @click="showGoods(row)">货物</el-button>
          <el-button link @click="showHistory(row)">移动记录</el-button>
          <el-button v-if="row.status !== 'IN_TRANSIT'" link @click="toggleDisabled(row)">{{ row.status === 'DISABLED' ? '启用' : '禁用' }}</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3>载具类型</h3>
    <div class="toolbar">
      <span class="spacer" />
      <el-button type="primary" @click="openType()">新建类型</el-button>
    </div>
    <el-table :data="types" size="small" stripe>
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column prop="category" label="类别" width="100" />
      <el-table-column label="尺寸 (m)" width="160">
        <template #default="{ row }">{{ row.length }} × {{ row.width }} × {{ row.height }}</template>
      </el-table-column>
      <el-table-column prop="maxLoad" label="最大载重 (kg)" width="120" />
      <el-table-column label="可搬运车型">
        <template #default="{ row }">{{ row.compatibleVehicleTypes.length ? row.compatibleVehicleTypes.join(', ') : '不限' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="openType(row)">编辑</el-button>
          <el-button link type="danger" @click="deleteType(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="carrierDialog" title="新建载具" width="380px">
      <el-form label-width="60px">
        <el-form-item label="编码"><el-input v-model="carrierForm.code" placeholder="条码或 RFID" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="carrierForm.typeCode" style="width: 100%">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="carrierDialog = false">取消</el-button>
        <el-button type="primary" @click="createCarrier">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="placeDialog.open" :title="`登记 ${placeDialog.carrier} 被放入库位`" width="400px">
      <el-select v-model="placeDialog.location" filterable placeholder="空闲库位" style="width: 100%">
        <el-option v-for="l in emptyLocations" :key="l.code" :label="`${l.code}（${l.zoneCode}）`" :value="l.code" />
      </el-select>
      <template #footer>
        <el-button @click="placeDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!placeDialog.location" @click="place">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="typeDialog.open" :title="typeDialog.editing ? '编辑载具类型' : '新建载具类型'" width="460px">
      <el-form label-width="100px">
        <el-form-item label="编码"><el-input v-model="typeForm.code" :disabled="typeDialog.editing" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="typeForm.name" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="typeForm.category" style="width: 100%">
            <el-option v-for="c in ['PALLET', 'SHELF', 'CART', 'BIN']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="长 × 宽 × 高">
          <el-input-number v-model="typeForm.length" :min="0.1" :step="0.1" size="small" />
          <el-input-number v-model="typeForm.width" :min="0.1" :step="0.1" size="small" />
          <el-input-number v-model="typeForm.height" :min="0.1" :step="0.1" size="small" />
        </el-form-item>
        <el-form-item label="最大载重 kg"><el-input-number v-model="typeForm.maxLoad" :min="1" /></el-form-item>
        <el-form-item label="可搬运车型">
          <el-select v-model="typeForm.compatibleVehicleTypes" multiple allow-create filterable placeholder="不限" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <CarrierGoods v-model="goodsDrawer.open" :carrier-code="goodsDrawer.carrier" :readonly="!session.canWrite" />

    <el-drawer v-model="history.open" :title="`${history.carrier} 的移动记录`" size="420px">
      <el-timeline>
        <el-timeline-item v-for="(m, i) in history.movements" :key="i" :timestamp="formatTime(m.movedAt)">
          {{ where(m.fromType, m.fromRef) }} → {{ where(m.toType, m.toRef) }}
          <div class="hint">{{ m.orderRef ? `订单 ${m.orderRef}` : '人工登记' }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-drawer>
  </div>
</template>

<style scoped>
.found {
  margin-bottom: 12px;
}
h3 {
  margin: 8px 0;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
