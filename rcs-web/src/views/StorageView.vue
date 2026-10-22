<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { carriersApi, mapsApi, storageApi, type LocationInput } from '@/api'
import type { CarrierType, LocationStatus, LocationType, MapPoint, MapView, StorageLocation, Zone } from '@/api/types'
import { act, confirm, formatTime } from '@/composables/notify'
import { LOCATION_COLORS, LOCATION_LABELS } from '@/map/colors'

const TYPES: LocationType[] = ['GROUND', 'RACK', 'STATION', 'BUFFER']

const zones = ref<Zone[]>([])
const locations = ref<StorageLocation[]>([])
const maps = ref<MapView[]>([])
const carrierTypes = ref<CarrierType[]>([])
const filter = reactive({ zone: '', status: '' as LocationStatus | '' })

async function loadZones() {
  zones.value = await storageApi.zones()
}

async function loadLocations() {
  locations.value = await storageApi.locations({ zone: filter.zone || undefined, status: filter.status || undefined })
}

onMounted(async () => {
  await Promise.all([loadZones(), loadLocations()])
  maps.value = (await mapsApi.list()).filter((m) => m.activeVersionNo !== null)
  carrierTypes.value = await carriersApi.types()
})

watch(filter, loadLocations)

// ------------------------------------------------------------------ zones (STO-01)

const zoneDialog = reactive({ open: false, editing: false })
const zoneForm = reactive<Zone>({ code: '', name: '', type: 'BUFFER', description: null })

function openZone(z?: Zone) {
  Object.assign(zoneDialog, { open: true, editing: !!z })
  Object.assign(zoneForm, z ?? { code: '', name: '', type: 'BUFFER', description: null })
}

async function saveZone() {
  const z = { ...zoneForm, description: zoneForm.description || undefined }
  if (await act(() => (zoneDialog.editing ? storageApi.updateZone(zoneForm) : storageApi.createZone(z)), '已保存')) {
    zoneDialog.open = false
    await loadZones()
  }
}

async function deleteZone(z: Zone) {
  if (await confirm(`删除库区 ${z.code}？库区内不能有库位。`)) {
    await act(() => storageApi.deleteZone(z.code), '已删除')
    await loadZones()
  }
}

// ------------------------------------------------------------------ locations (STO-02, STO-03)

const points = ref<MapPoint[]>([])
const locDialog = reactive({ open: false, editing: false, batch: false })
const locForm = reactive({
  code: '', zoneCode: '', type: 'GROUND' as LocationType, mapCode: '', accessPoint: '', level: 0, height: 0,
  allowedCarrierTypes: [] as string[], batchPoints: [] as string[], prefix: 'L-',
})

watch(
  () => locForm.mapCode,
  async (code) => {
    points.value = code ? (await mapsApi.active(code)).points : []
  },
)

function openLocation(l?: StorageLocation, batch = false) {
  Object.assign(locDialog, { open: true, editing: !!l, batch })
  Object.assign(locForm, l
    ? { ...l, allowedCarrierTypes: [...l.allowedCarrierTypes], batchPoints: [] }
    : { code: '', zoneCode: filter.zone || zones.value[0]?.code || '', type: 'GROUND', mapCode: maps.value[0]?.code ?? '',
        accessPoint: '', level: 0, height: 0, allowedCarrierTypes: [], batchPoints: [], prefix: 'L-' })
}

function input(code: string, accessPoint: string): LocationInput {
  return { code, zoneCode: locForm.zoneCode, type: locForm.type, mapCode: locForm.mapCode, accessPoint,
    level: locForm.level, height: locForm.height, allowedCarrierTypes: locForm.allowedCarrierTypes }
}

async function saveLocation() {
  let ok
  if (locDialog.batch) {
    // one location per selected point, created one by one so a clash shows which code failed
    ok = await act(async () => {
      for (const p of locForm.batchPoints) {
        await storageApi.createLocation(input(locForm.prefix + p, p))
      }
    }, `已创建 ${locForm.batchPoints.length} 个库位`)
  } else {
    const body = input(locForm.code, locForm.accessPoint)
    ok = await act(() => (locDialog.editing ? storageApi.updateLocation(body) : storageApi.createLocation(body)), '已保存')
  }
  if (ok !== null) {
    locDialog.open = false
  }
  await loadLocations()
}

async function toggleDisabled(l: StorageLocation) {
  await act(() => (l.status === 'DISABLED' ? storageApi.enable(l.code) : storageApi.disable(l.code)))
  await loadLocations()
}

async function deleteLocation(l: StorageLocation) {
  if (await confirm(`删除库位 ${l.code}？`)) {
    await act(() => storageApi.deleteLocation(l.code), '已删除')
    await loadLocations()
  }
}
</script>

<template>
  <div class="page">
    <h3>库区</h3>
    <div class="toolbar">
      <span class="spacer" />
      <el-button type="primary" @click="openZone()">新建库区</el-button>
    </div>
    <el-table :data="zones" size="small" stripe>
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column prop="description" label="说明" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="openZone(row)">编辑</el-button>
          <el-button link type="danger" @click="deleteZone(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3>库位</h3>
    <div class="toolbar">
      <el-select v-model="filter.zone" placeholder="全部库区" clearable style="width: 160px">
        <el-option v-for="z in zones" :key="z.code" :label="z.name" :value="z.code" />
      </el-select>
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="(label, s) in LOCATION_LABELS" :key="s" :label="label" :value="s" />
      </el-select>
      <span class="spacer" />
      <el-button @click="openLocation(undefined, true)">按点位批量创建</el-button>
      <el-button type="primary" @click="openLocation()">新建库位</el-button>
    </div>
    <el-table :data="locations" size="small" stripe>
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="zoneCode" label="库区" width="100" />
      <el-table-column prop="type" label="类型" width="90" />
      <el-table-column label="地图 / 点位" width="150">
        <template #default="{ row }">{{ row.mapCode }} / {{ row.accessPoint }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <span class="status"><i :style="{ background: LOCATION_COLORS[row.status as LocationStatus] }" />{{ LOCATION_LABELS[row.status as LocationStatus] }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="carrierCode" label="载具" width="110" />
      <el-table-column prop="lockOwner" label="锁定者" width="130" />
      <el-table-column label="允许载具" min-width="120">
        <template #default="{ row }">{{ row.allowedCarrierTypes.length ? row.allowedCarrierTypes.join(', ') : '不限' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLocation(row)">编辑</el-button>
          <el-button link @click="toggleDisabled(row)">{{ row.status === 'DISABLED' ? '启用' : '禁用' }}</el-button>
          <el-button link type="danger" @click="deleteLocation(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="zoneDialog.open" :title="zoneDialog.editing ? '编辑库区' : '新建库区'" width="400px">
      <el-form label-width="60px">
        <el-form-item label="编码"><el-input v-model="zoneForm.code" :disabled="zoneDialog.editing" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="zoneForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="zoneForm.type" allow-create filterable style="width: 100%">
            <el-option v-for="t in ['RAW', 'WIP', 'FINISHED', 'BUFFER']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="zoneForm.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="zoneDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveZone">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="locDialog.open" :title="locDialog.batch ? '按点位批量创建库位' : locDialog.editing ? '编辑库位' : '新建库位'" width="480px">
      <el-form label-width="90px">
        <el-form-item v-if="!locDialog.batch" label="编码"><el-input v-model="locForm.code" :disabled="locDialog.editing" /></el-form-item>
        <el-form-item label="库区">
          <el-select v-model="locForm.zoneCode" style="width: 100%">
            <el-option v-for="z in zones" :key="z.code" :label="z.name" :value="z.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="locForm.type" style="width: 100%">
            <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="地图">
          <el-select v-model="locForm.mapCode" style="width: 100%">
            <el-option v-for="m in maps" :key="m.code" :label="m.name" :value="m.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!locDialog.batch" label="取放点位">
          <el-select v-model="locForm.accessPoint" filterable style="width: 100%">
            <el-option v-for="p in points" :key="p.code" :label="`${p.code}（${p.type}）`" :value="p.code" />
          </el-select>
        </el-form-item>
        <template v-else>
          <el-form-item label="点位">
            <el-select v-model="locForm.batchPoints" multiple filterable style="width: 100%" placeholder="每个点位创建一个库位">
              <el-option v-for="p in points" :key="p.code" :label="p.code" :value="p.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="编码前缀">
            <el-input v-model="locForm.prefix" />
            <div class="hint">库位编码 = 前缀 + 点位编码，例如 {{ locForm.prefix }}{{ locForm.batchPoints[0] ?? 'P1' }}</div>
          </el-form-item>
        </template>
        <el-form-item label="层 / 高度">
          <el-input-number v-model="locForm.level" :min="0" /> / <el-input-number v-model="locForm.height" :min="0" :step="0.1" />
        </el-form-item>
        <el-form-item label="允许载具">
          <el-select v-model="locForm.allowedCarrierTypes" multiple placeholder="不限" style="width: 100%">
            <el-option v-for="t in carrierTypes" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveLocation">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
h3 {
  margin: 8px 0;
}
.status i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin-right: 6px;
  vertical-align: -1px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
