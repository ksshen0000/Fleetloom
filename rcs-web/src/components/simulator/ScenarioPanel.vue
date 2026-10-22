<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { simulatorApi } from '@/api'
import type { ScenarioReport } from '@/api/types'
import { act } from '@/composables/notify'
import demoMix from '../../../../deploy/examples/scenario-demo.yaml?raw'
import warehouse from '../../../../deploy/examples/scenario-warehouse.yaml?raw'
import peak from '../../../../deploy/examples/scenario-warehouse-peak.yaml?raw'

/** Scenario runs (SIM-12): pick or paste a YAML scenario, start it, watch the numbers. */
const PRESETS = [
  { label: '仓库作业：入库 / 拣选 / 出库 / 移库', yaml: warehouse },
  { label: '仓库高峰压测', yaml: peak },
  { label: '演示网格：移动 + 搬运', yaml: demoMix },
]
const preset = ref(PRESETS[0].label)
const yaml = ref(PRESETS[0].yaml)
function choose(label: string) {
  yaml.value = PRESETS.find((p) => p.label === label)?.yaml ?? yaml.value
}
const report = ref<ScenarioReport | null>(null)
let timer: ReturnType<typeof setInterval> | undefined

async function refresh() {
  report.value = (await simulatorApi.scenario().catch(() => report.value)) ?? null
}

onMounted(() => {
  refresh()
  timer = setInterval(() => report.value?.state === 'RUNNING' && refresh(), 2000)
})
onBeforeUnmount(() => clearInterval(timer))

async function start() {
  const started = await act(() => simulatorApi.startScenario(yaml.value), '场景已开始')
  if (started) report.value = started
}

async function stop() {
  const stopped = await act(() => simulatorApi.stopScenario(), '已停止发单')
  if (stopped) report.value = stopped
}

const STATES = { RUNNING: '运行中', FINISHED: '已完成', STOPPED: '已停止' }
const n = (v: number | null) => (v == null ? '—' : v)
</script>

<template>
  <el-card shadow="never" header="场景脚本（SIM-12）">
    <div class="layout">
      <div class="editor">
        <el-select v-model="preset" class="preset" data-testid="scenario-preset" @change="choose">
          <el-option v-for="p in PRESETS" :key="p.label" :label="p.label" :value="p.label" />
        </el-select>
        <el-input v-model="yaml" type="textarea" :rows="14" spellcheck="false" class="yaml" data-testid="scenario-yaml" />
        <div class="toolbar">
          <span class="hint">vehicles 可加虚拟车（registerAs 车型即登记）；orders 按 start / every / count 定时下单，列表轮流取用。搬运可写 arrive（到货入库）、fromZone（从库区取空闲载具，可按 sku）、depart（送达后发运）、returnAfter（拣选后送回）。时间是真实时间。</span>
          <span class="spacer" />
          <el-button v-if="report?.state === 'RUNNING'" @click="stop">停止发单</el-button>
          <el-button type="primary" :disabled="report?.state === 'RUNNING'" @click="start">开始</el-button>
        </div>
      </div>
      <div v-if="report" class="report" data-testid="scenario-report">
        <div class="title">{{ report.name ?? '场景' }} · <el-tag size="small" :type="report.state === 'RUNNING' ? 'primary' : 'success'">{{ STATES[report.state] }}</el-tag></div>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="已发 / 计划">{{ report.sent }} / {{ report.planned }}</el-descriptions-item>
          <el-descriptions-item label="完成">{{ report.completed }}</el-descriptions-item>
          <el-descriptions-item label="被拒">{{ report.rejected }}</el-descriptions-item>
          <el-descriptions-item label="失败 / 取消">{{ report.failed }} / {{ report.cancelled }}</el-descriptions-item>
          <el-descriptions-item label="进行中">{{ report.open }}</el-descriptions-item>
          <el-descriptions-item label="每小时完成">{{ n(report.perHour) }}</el-descriptions-item>
          <el-descriptions-item label="平均耗时">{{ n(report.avgSeconds) }} s</el-descriptions-item>
          <el-descriptions-item label="P95 / 最长">{{ n(report.p95Seconds) }} / {{ n(report.maxSeconds) }} s</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="report.streams?.length" :data="report.streams" size="small" class="streams" data-testid="scenario-streams">
          <el-table-column prop="name" label="作业流" min-width="140" />
          <el-table-column label="完成 / 计划" width="96">
            <template #default="{ row }">{{ row.completed }} / {{ row.planned }}</template>
          </el-table-column>
          <el-table-column label="被拒 / 失败" width="96">
            <template #default="{ row }">{{ row.rejected }} / {{ row.failed }}</template>
          </el-table-column>
          <el-table-column prop="open" label="进行中" width="70" />
          <el-table-column prop="waited" label="排队" width="60" />
          <el-table-column prop="departed" label="已发运" width="70" />
          <el-table-column label="平均 / P95 (s)" width="110">
            <template #default="{ row }">{{ n(row.avgSeconds) }} / {{ n(row.p95Seconds) }}</template>
          </el-table-column>
        </el-table>
        <div v-for="(e, i) in report.errors" :key="i" class="error">{{ e }}</div>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
@media (max-width: 1100px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
.yaml :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.toolbar {
  margin-top: 8px;
}
.preset {
  width: 100%;
  margin-bottom: 8px;
}
.streams {
  margin-top: 12px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.title {
  margin-bottom: 8px;
}
.error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
