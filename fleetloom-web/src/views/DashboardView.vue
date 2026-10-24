<script setup lang="ts">
import { computed, ref } from 'vue'
import { alarmsApi, dispatchApi } from '@/api'
import type { AlarmSummary, DispatchVehicle, OrderStatistics } from '@/api/types'
import { usePolling } from '@/composables/usePolling'
import { coalesce, usePush } from '@/composables/usePush'

const stats = ref<OrderStatistics | null>(null)
const vehicles = ref<DispatchVehicle[]>([])
const alarms = ref<AlarmSummary | null>(null)

const polling = usePolling(async () => {
  const [s, v, a] = await Promise.all([dispatchApi.statistics(24), dispatchApi.vehicles(), alarmsApi.summary().catch(() => null)])
  stats.value = s
  vehicles.value = v
  alarms.value = a
}, 10_000)

usePush(['TransportOrderStatusChanged', 'AlarmChanged', 'VehicleConnectionChanged', 'VehicleForgotten'], coalesce(() => polling.refresh(), 1000))

const online = computed(() => vehicles.value.filter((v) => v.connection === 'ONLINE'))
const busy = computed(() => online.value.filter((v) => v.currentOrder))
const charging = computed(() => online.value.filter((v) => v.charging))
const utilization = computed(() => (online.value.length ? Math.round((busy.value.length / online.value.length) * 100) : 0))
const avgBattery = computed(() =>
  online.value.length ? Math.round(online.value.reduce((sum, v) => sum + v.battery, 0) / online.value.length) : 0,
)

const hours = computed(() => stats.value?.hours ?? [])
const peak = computed(() => Math.max(1, ...hours.value.map((h) => h.completed + h.failed + h.cancelled)))
const openOrders = computed(() => Object.values(stats.value?.open ?? {}).reduce((a, b) => a + (b ?? 0), 0))

function duration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  const m = Math.floor(seconds / 60)
  const s = Math.round(seconds % 60)
  return m ? `${m} 分 ${s} 秒` : `${s} 秒`
}

function hourLabel(iso: string | null): string {
  return iso ? new Date(iso).getHours().toString().padStart(2, '0') : ''
}

function batteryColor(b: number): string {
  return b < 20 ? 'var(--el-color-danger)' : b < 40 ? 'var(--el-color-warning)' : 'var(--el-color-success)'
}
</script>

<template>
  <div class="page dashboard">
    <div class="cards">
      <el-card shadow="never">
        <div class="label">在线车辆</div>
        <div class="value">{{ online.length }}<small> / {{ vehicles.length }}</small></div>
        <div class="sub">执行中 {{ busy.length }} · 充电 {{ charging.length }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="label">车辆利用率</div>
        <div class="value">{{ utilization }}<small>%</small></div>
        <div class="sub">平均电量 {{ avgBattery }}%</div>
      </el-card>
      <el-card shadow="never">
        <div class="label">24 小时完成订单</div>
        <div class="value">{{ stats?.total.completed ?? 0 }}</div>
        <div class="sub">失败 {{ stats?.total.failed ?? 0 }} · 取消 {{ stats?.total.cancelled ?? 0 }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="label">平均完成时长</div>
        <div class="value small">{{ duration(stats?.total.avgSeconds) }}</div>
        <div class="sub">从下单到完成</div>
      </el-card>
      <el-card shadow="never">
        <div class="label">进行中订单</div>
        <div class="value">{{ openOrders }}</div>
        <div class="sub">排队 {{ stats?.open.QUEUED ?? 0 }} · 执行 {{ stats?.open.EXECUTING ?? 0 }} · 挂起 {{ stats?.open.SUSPENDED ?? 0 }}</div>
      </el-card>
      <el-card shadow="never" :class="{ alert: (alarms?.unacknowledged ?? 0) > 0 }">
        <div class="label">未关闭告警</div>
        <div class="value">{{ alarms?.open ?? 0 }}</div>
        <div class="sub">未确认 {{ alarms?.unacknowledged ?? 0 }}</div>
      </el-card>
    </div>

    <div class="panels">
      <el-card shadow="never" class="chart-card">
        <template #header>每小时订单（近 24 小时，不含充电）</template>
        <div class="chart" data-testid="hourly-chart">
          <div v-for="h in hours" :key="h.hour ?? ''" class="bar-col">
            <el-tooltip :content="`${hourLabel(h.hour)} 时：完成 ${h.completed}，失败 ${h.failed}，取消 ${h.cancelled}，平均 ${duration(h.avgSeconds)}`">
              <div class="bar">
                <div class="seg failed" :style="{ height: `${(h.failed / peak) * 100}%` }" />
                <div class="seg cancelled" :style="{ height: `${(h.cancelled / peak) * 100}%` }" />
                <div class="seg completed" :style="{ height: `${(h.completed / peak) * 100}%` }" />
              </div>
            </el-tooltip>
            <span class="tick">{{ hourLabel(h.hour) }}</span>
          </div>
        </div>
        <div class="legend">
          <span><i class="completed" />完成</span><span><i class="cancelled" />取消</span><span><i class="failed" />失败</span>
        </div>
      </el-card>

      <el-card shadow="never" class="battery-card">
        <template #header>电量</template>
        <el-empty v-if="vehicles.length === 0" description="没有车辆" :image-size="60" />
        <div v-for="v in vehicles" :key="v.code" class="battery-row">
          <span class="code">{{ v.code }}</span>
          <el-progress :percentage="Math.round(v.battery)" :color="batteryColor(v.battery)" :stroke-width="10" class="progress" />
          <el-tag v-if="v.connection !== 'ONLINE'" size="small" type="info">离线</el-tag>
          <el-tag v-else-if="v.charging" size="small" type="success">充电</el-tag>
          <el-tag v-else-if="v.currentOrder" size="small">作业</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">空闲</el-tag>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  overflow: auto;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.value {
  font-size: 30px;
  font-weight: 600;
  margin: 6px 0 2px;
  font-variant-numeric: tabular-nums;
}
.value.small {
  font-size: 22px;
  line-height: 45px;
}
.value small {
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.alert {
  border-color: var(--el-color-danger-light-5);
}
.alert .value {
  color: var(--el-color-danger);
}
.panels {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr);
  gap: 12px;
}
.chart {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 220px;
}
.bar-col {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.bar {
  flex: 1;
  width: 100%;
  display: flex;
  flex-direction: column-reverse;
  background: var(--el-fill-color-lighter);
  border-radius: 3px 3px 0 0;
  overflow: hidden;
}
.seg.completed,
.legend i.completed {
  background: var(--el-color-success);
}
.seg.cancelled,
.legend i.cancelled {
  background: var(--el-color-info-light-3);
}
.seg.failed,
.legend i.failed {
  background: var(--el-color-danger);
}
.tick {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 8px;
}
.legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin-right: 4px;
  vertical-align: -1px;
}
.battery-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.code {
  width: 70px;
  font-size: 13px;
}
.progress {
  flex: 1;
}
@media (max-width: 900px) {
  .panels {
    grid-template-columns: 1fr;
  }
}
</style>
