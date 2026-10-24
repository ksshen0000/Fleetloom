<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { alarmsApi } from '@/api'
import type { Alarm, AlarmLevel, AlarmStatus } from '@/api/types'
import { act, formatTime } from '@/composables/notify'
import { coalesce, usePush, usePushReconnect } from '@/composables/usePush'
import { ALARM_CATEGORY_LABELS, ALARM_LEVEL_LABELS, ALARM_LEVEL_TAG, ALARM_STATUS_LABELS } from '@/map/colors'
import { useSession } from '@/stores/session'

const session = useSession()
const alarms = ref<Alarm[]>([])
const filter = reactive({ scope: 'open' as 'open' | 'closed' | 'all', level: '' as AlarmLevel | '', vehicle: '' })

async function load() {
  alarms.value = await alarmsApi.list({
    open: filter.scope === 'all' ? undefined : filter.scope === 'open',
    level: filter.level || undefined,
    vehicle: filter.vehicle.trim() || undefined,
    limit: 500,
  })
}

onMounted(load)
watch(() => [filter.scope, filter.level], load)

const reloadSoon = coalesce(load, 300)
usePush<{ alarm: Alarm }>('AlarmChanged', reloadSoon)
usePushReconnect(reloadSoon)

async function acknowledge(a: Alarm) {
  await act(() => alarmsApi.acknowledge(a.id), '已确认')
  await load()
}

async function close(a: Alarm) {
  await act(() => alarmsApi.close(a.id), '已关闭')
  await load()
}

const STATUS_TAG: Record<AlarmStatus, 'danger' | 'warning' | 'info'> = { ACTIVE: 'danger', ACKNOWLEDGED: 'warning', CLEARED: 'info' }

function subject(a: Alarm): string {
  return [a.vehicleCode, a.orderNo].filter(Boolean).join(' / ')
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-radio-group v-model="filter.scope">
        <el-radio-button value="open">未关闭</el-radio-button>
        <el-radio-button value="closed">已关闭</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <el-select v-model="filter.level" placeholder="全部等级" clearable style="width: 120px">
        <el-option v-for="(label, level) in ALARM_LEVEL_LABELS" :key="level" :label="label" :value="level" />
      </el-select>
      <el-input v-model="filter.vehicle" placeholder="车辆编码" clearable style="width: 140px" @change="load" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="alarms" size="small" stripe row-key="id" empty-text="没有告警">
      <el-table-column label="等级" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="ALARM_LEVEL_TAG[row.level as AlarmLevel]" :effect="row.level === 'FATAL' ? 'dark' : 'light'">
            {{ ALARM_LEVEL_LABELS[row.level as AlarmLevel] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="90">
        <template #default="{ row }">{{ ALARM_CATEGORY_LABELS[row.category as keyof typeof ALARM_CATEGORY_LABELS] }}</template>
      </el-table-column>
      <el-table-column label="对象" width="150">
        <template #default="{ row }">{{ subject(row) }}</template>
      </el-table-column>
      <el-table-column prop="message" label="内容" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_TAG[row.status as AlarmStatus]">{{ ALARM_STATUS_LABELS[row.status as AlarmStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="occurrences" label="次数" width="60" />
      <el-table-column label="发生时间" width="160">
        <template #default="{ row }">{{ formatTime(row.raisedAt) }}</template>
      </el-table-column>
      <el-table-column label="处理" min-width="170">
        <template #default="{ row }">
          <div v-if="row.acknowledgedBy" class="hint">{{ row.acknowledgedBy }} 确认于 {{ formatTime(row.acknowledgedAt) }}</div>
          <div v-if="row.clearedAt" class="hint">
            {{ row.clearedBy ? `${row.clearedBy} 关闭于` : '自动恢复于' }} {{ formatTime(row.clearedAt) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="session.canWrite" label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="acknowledge(row)">确认</el-button>
          <el-button v-if="row.status !== 'CLEARED'" link @click="close(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
