<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fleetApi } from '@/api'
import type { VehicleMessage } from '@/api/types'

/** The VDA 5050 messages exchanged with one vehicle, newest first (FLT-07), for troubleshooting. */
const open = defineModel<boolean>({ required: true })
const props = defineProps<{ vehicleCode: string }>()

const messages = ref<VehicleMessage[]>([])
const range = ref<[Date, Date] | null>(null)
const topic = ref('')
const loading = ref(false)

const TOPICS: Record<string, string> = {
  order: '订单',
  instantActions: '即时动作',
  state: '状态',
  connection: '连接',
  factsheet: '规格书',
}

async function load() {
  loading.value = true
  try {
    messages.value = await fleetApi.messages(props.vehicleCode, {
      from: range.value?.[0].toISOString(),
      to: range.value?.[1].toISOString(),
      limit: 500,
    })
  } finally {
    loading.value = false
  }
}

watch([open, () => props.vehicleCode], ([o]) => o && load())

const shown = computed(() => (topic.value ? messages.value.filter((m) => m.topic === topic.value) : messages.value))

type Json = Record<string, any>

function parse(m: VehicleMessage): Json | null {
  try {
    return JSON.parse(m.payload)
  } catch {
    return null
  }
}

/** One line telling what the message was about. */
function summary(m: VehicleMessage): string {
  const p = parse(m)
  if (!p) return m.payload.slice(0, 120)
  switch (m.topic) {
    case 'order':
      return `${p.orderId} #${p.orderUpdateId}：${(p.nodes ?? []).map((n: Json) => n.nodeId + (n.released ? '' : '*')).join(' → ')}`
    case 'instantActions':
      return (p.actions ?? []).map((a: Json) => a.actionType).join(', ')
    case 'connection':
      return p.connectionState
    case 'state': {
      const parts = [p.orderId ? `订单 ${p.orderId} #${p.orderUpdateId}` : '无订单', `点 ${p.lastNodeId || '—'}`]
      if (p.driving) parts.push('行驶中')
      if (p.paused) parts.push('暂停')
      const running = (p.actionStates ?? []).filter((a: Json) => a.actionStatus !== 'FINISHED' && a.actionStatus !== 'FAILED')
      if (running.length) parts.push('动作 ' + running.map((a: Json) => `${a.actionType}:${a.actionStatus}`).join(' '))
      if (p.errors?.length) parts.push('错误 ' + p.errors.map((e: Json) => e.errorType).join(' '))
      return parts.join('，')
    }
    default:
      return ''
  }
}

function pretty(m: VehicleMessage): string {
  const p = parse(m)
  return p ? JSON.stringify(p, null, 2) : m.payload
}

function time(at: string): string {
  const d = new Date(at)
  return d.toLocaleString('zh-CN', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0')
}
</script>

<template>
  <el-drawer v-model="open" :title="`${vehicleCode} 报文日志`" size="760px">
    <div class="toolbar">
      <el-date-picker v-model="range" type="datetimerange" start-placeholder="开始" end-placeholder="结束" size="small" @change="load" />
      <el-select v-model="topic" placeholder="全部主题" clearable size="small" style="width: 120px">
        <el-option v-for="(label, t) in TOPICS" :key="t" :label="label" :value="t" />
      </el-select>
      <span class="spacer" />
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="hint">保留 3 天。下发的指令和连接变化全部记录；状态只在订单、点位、动作、错误等变化时记录，位置电量变化每分钟记一条。</p>
    <el-table :data="shown" size="small" stripe row-key="id" data-testid="vehicle-messages">
      <el-table-column type="expand">
        <template #default="{ row }"><pre class="payload">{{ pretty(row) }}</pre></template>
      </el-table-column>
      <el-table-column label="时间" width="190">
        <template #default="{ row }">{{ time(row.at) }}</template>
      </el-table-column>
      <el-table-column label="方向" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.direction === 'OUT' ? 'primary' : 'info'">{{ row.direction === 'OUT' ? '下发' : '上报' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="主题" width="90">
        <template #default="{ row }">{{ TOPICS[row.topic] ?? row.topic }}</template>
      </el-table-column>
      <el-table-column label="内容" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ summary(row) }}</template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 8px;
}
.payload {
  margin: 0 16px;
  font-size: 12px;
  max-height: 360px;
  overflow: auto;
}
</style>
