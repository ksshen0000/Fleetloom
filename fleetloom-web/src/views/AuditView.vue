<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditApi } from '@/api'
import type { AuditEntry } from '@/api/types'
import { formatTime } from '@/composables/notify'

const SERVICES = [
  { value: '/api/resource', label: '基础资源（地图、库位、载具、用户）' },
  { value: '/api/dispatch', label: '订单调度' },
  { value: '/api/fleet', label: '车辆控制' },
  { value: '/api/traffic', label: '交通管制' },
  { value: '/api/simulator', label: '模拟器' },
]

const entries = ref<AuditEntry[]>([])
const loading = ref(false)
const filter = reactive({ user: '', path: '', failedOnly: false, range: null as [Date, Date] | null })

async function load() {
  loading.value = true
  try {
    entries.value = await auditApi.find({
      user: filter.user || undefined,
      path: filter.path || undefined,
      failedOnly: filter.failedOnly || undefined,
      from: filter.range?.[0].toISOString(),
      to: filter.range?.[1].toISOString(),
      limit: 500,
    })
  } finally {
    loading.value = false
  }
}

onMounted(load)

function statusType(status: number) {
  return status === 0 || status >= 500 ? 'danger' : status >= 400 ? 'warning' : 'success'
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="filter.user" placeholder="用户名" clearable style="width: 140px" @change="load" />
      <el-select v-model="filter.path" placeholder="全部服务" clearable style="width: 240px" @change="load">
        <el-option v-for="s in SERVICES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-date-picker v-model="filter.range" type="datetimerange" start-placeholder="开始" end-placeholder="结束" @change="load" />
      <el-checkbox v-model="filter.failedOnly" @change="load">只看失败 / 被拒绝</el-checkbox>
      <span class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="entries" size="small" stripe empty-text="没有记录">
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.at) }}</template>
      </el-table-column>
      <el-table-column label="用户" width="140">
        <template #default="{ row }">
          <span v-if="row.username">{{ row.username }} <span class="hint">{{ row.role }}</span></span>
          <span v-else class="hint">未登录</span>
        </template>
      </el-table-column>
      <el-table-column prop="method" label="方法" width="80" />
      <el-table-column label="路径" min-width="280" show-overflow-tooltip>
        <template #default="{ row }">{{ row.path }}{{ row.query ? '?' + row.query : '' }}</template>
      </el-table-column>
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ row.status || '中断' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.durationMs }} ms</template>
      </el-table-column>
      <el-table-column prop="clientIp" label="来源 IP" width="130" />
    </el-table>
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
