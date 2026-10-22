<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { mapsApi, registryApi } from '@/api'
import type { MapTopology, VehicleSample } from '@/api/types'

/** Trajectory and battery curve of one vehicle (VEH-09); hovering either chart marks the same moment in both. */
const open = defineModel<boolean>({ required: true })
const props = defineProps<{ vehicleCode: string }>()

const RANGES: Record<string, number> = { '1 小时': 1, '6 小时': 6, '24 小时': 24, '7 天': 168 }
const hours = ref(1)
const samples = ref<VehicleSample[]>([])
const topology = ref<MapTopology | null>(null)
const loading = ref(false)
const hover = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const to = new Date()
    const from = new Date(to.getTime() - hours.value * 3600_000)
    samples.value = await registryApi.history(props.vehicleCode, { from: from.toISOString(), to: to.toISOString(), limit: 20000 })
    const mapCode = samples.value.at(-1)?.mapCode
    topology.value = mapCode && mapCode !== topology.value?.mapCode ? await mapsApi.active(mapCode).catch(() => null) : topology.value
  } finally {
    loading.value = false
  }
}

watch([open, () => props.vehicleCode, hours], ([o]) => o && load())

// ------------------------------------------------------------------ battery chart (time → %)
const W = 640
const H = 180
const PAD = { left: 40, right: 12, top: 10, bottom: 24 }
const times = computed(() => samples.value.map((s) => new Date(s.at).getTime()))
const t0 = computed(() => times.value[0] ?? 0)
const t1 = computed(() => Math.max(times.value.at(-1) ?? 1, t0.value + 1))
const bx = (t: number) => PAD.left + ((t - t0.value) / (t1.value - t0.value)) * (W - PAD.left - PAD.right)
const by = (b: number) => PAD.top + (1 - b / 100) * (H - PAD.top - PAD.bottom)
const batteryPath = computed(() => samples.value.map((s, i) => `${i ? 'L' : 'M'}${bx(times.value[i]).toFixed(1)},${by(s.battery).toFixed(1)}`).join(''))
const timeTicks = computed(() => {
  const n = 4
  return Array.from({ length: n + 1 }, (_, i) => t0.value + ((t1.value - t0.value) * i) / n)
})

function onBatteryMove(e: MouseEvent) {
  if (!samples.value.length) return
  const svg = e.currentTarget as SVGSVGElement
  const x = ((e.clientX - svg.getBoundingClientRect().left) / svg.clientWidth) * W
  const t = t0.value + ((x - PAD.left) / (W - PAD.left - PAD.right)) * (t1.value - t0.value)
  hover.value = nearest((i) => Math.abs(times.value[i] - t))
}

// ------------------------------------------------------------------ trajectory (x, y in metres, y up)
const TW = 640
const TH = 300
const bounds = computed(() => {
  const xs = [...samples.value.map((s) => s.x), ...(topology.value?.points ?? []).map((p) => p.x)]
  const ys = [...samples.value.map((s) => s.y), ...(topology.value?.points ?? []).map((p) => p.y)]
  if (!xs.length) return { minX: 0, minY: 0, scale: 1 }
  const minX = Math.min(...xs) - 1
  const maxX = Math.max(...xs) + 1
  const minY = Math.min(...ys) - 1
  const maxY = Math.max(...ys) + 1
  return { minX, minY, maxY, scale: Math.min(TW / (maxX - minX), TH / (maxY - minY)) }
})
const tx = (x: number) => (x - bounds.value.minX) * bounds.value.scale
const ty = (y: number) => TH - (y - bounds.value.minY) * bounds.value.scale
const trajectory = computed(() => samples.value.map((s, i) => `${i ? 'L' : 'M'}${tx(s.x).toFixed(1)},${ty(s.y).toFixed(1)}`).join(''))
const pointsByCode = computed(() => new Map((topology.value?.points ?? []).map((p) => [p.code, p])))
const mapPaths = computed(() =>
  (topology.value?.paths ?? []).flatMap((p) => {
    const a = pointsByCode.value.get(p.fromPoint)
    const b = pointsByCode.value.get(p.toPoint)
    return a && b ? [{ code: p.code, x1: tx(a.x), y1: ty(a.y), x2: tx(b.x), y2: ty(b.y) }] : []
  }),
)

function onTrajectoryMove(e: MouseEvent) {
  if (!samples.value.length) return
  const svg = e.currentTarget as SVGSVGElement
  const r = svg.getBoundingClientRect()
  const x = ((e.clientX - r.left) / svg.clientWidth) * TW
  const y = ((e.clientY - r.top) / svg.clientHeight) * TH
  hover.value = nearest((i) => Math.hypot(tx(samples.value[i].x) - x, ty(samples.value[i].y) - y))
}

function nearest(distance: (i: number) => number): number {
  let best = 0
  for (let i = 1; i < samples.value.length; i++) if (distance(i) < distance(best)) best = i
  return best
}

const current = computed(() => (hover.value == null ? null : samples.value[hover.value] ?? null))
const distance = computed(() => {
  let d = 0
  for (let i = 1; i < samples.value.length; i++) {
    const a = samples.value[i - 1]
    const b = samples.value[i]
    if (a.mapCode === b.mapCode) d += Math.hypot(b.x - a.x, b.y - a.y)
  }
  return d
})

function clock(t: number | string): string {
  const d = new Date(t)
  const span = t1.value - t0.value
  if (span < 600_000) return d.toLocaleTimeString('zh-CN', { hour12: false })
  return hours.value <= 24 ? d.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <el-dialog v-model="open" :title="`${vehicleCode} 历史轨迹与电量`" width="720px">
    <div class="toolbar">
      <el-radio-group v-model="hours" size="small">
        <el-radio-button v-for="(h, label) in RANGES" :key="h" :value="h">{{ label }}</el-radio-button>
      </el-radio-group>
      <span class="spacer" />
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-empty v-if="!samples.length" description="这段时间没有记录" :image-size="60" />
    <template v-else>
      <div class="stats" data-testid="history-stats">
        <span>{{ samples.length }} 个采样</span>
        <span>行驶约 {{ distance.toFixed(1) }} m</span>
        <span>电量 {{ samples[0].battery.toFixed(0) }}% → {{ samples.at(-1)!.battery.toFixed(0) }}%</span>
      </div>
      <div class="readout">
        <template v-if="current">
          {{ new Date(current.at).toLocaleString('zh-CN', { hour12: false }) }} ·
          ({{ current.x.toFixed(2) }}, {{ current.y.toFixed(2) }}) {{ current.lastNodeId ?? '' }} ·
          电量 {{ current.battery.toFixed(1) }}%{{ current.charging ? '（充电中）' : '' }} · {{ current.connection }}
        </template>
        <template v-else>将鼠标移到图上查看某一时刻</template>
      </div>

      <h4>轨迹</h4>
      <svg :viewBox="`0 0 ${TW} ${TH}`" class="chart trajectory" @mousemove="onTrajectoryMove" @mouseleave="hover = null">
        <line v-for="p in mapPaths" :key="p.code" :x1="p.x1" :y1="p.y1" :x2="p.x2" :y2="p.y2" class="map-path" />
        <circle v-for="p in topology?.points ?? []" :key="p.code" :cx="tx(p.x)" :cy="ty(p.y)" r="3" class="map-point" />
        <path :d="trajectory" class="line" />
        <circle :cx="tx(samples[0].x)" :cy="ty(samples[0].y)" r="5" class="start" />
        <circle :cx="tx(samples.at(-1)!.x)" :cy="ty(samples.at(-1)!.y)" r="5" class="end" />
        <circle v-if="current" :cx="tx(current.x)" :cy="ty(current.y)" r="6" class="focus" />
      </svg>
      <div class="legend"><span class="dot start" />起点 <span class="dot end" />终点</div>

      <h4>电量 (%)</h4>
      <svg :viewBox="`0 0 ${W} ${H}`" class="chart" @mousemove="onBatteryMove" @mouseleave="hover = null">
        <g class="grid">
          <line v-for="b in [0, 25, 50, 75, 100]" :key="b" :x1="PAD.left" :x2="W - PAD.right" :y1="by(b)" :y2="by(b)" />
        </g>
        <text v-for="b in [0, 50, 100]" :key="b" :x="PAD.left - 6" :y="by(b) + 4" class="axis" text-anchor="end">{{ b }}</text>
        <text v-for="(t, i) in timeTicks" :key="t" :x="bx(t)" :y="H - 6" class="axis"
          :text-anchor="i === 0 ? 'start' : i === timeTicks.length - 1 ? 'end' : 'middle'">{{ clock(t) }}</text>
        <path :d="batteryPath" class="line" />
        <template v-if="current && hover != null">
          <line :x1="bx(times[hover])" :x2="bx(times[hover])" :y1="PAD.top" :y2="H - PAD.bottom" class="crosshair" />
          <circle :cx="bx(times[hover])" :cy="by(current.battery)" r="4" class="focus" />
        </template>
      </svg>
    </template>
  </el-dialog>
</template>

<style scoped>
h4 {
  margin: 12px 0 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}
.stats {
  display: flex;
  gap: 16px;
  font-size: 13px;
}
.readout {
  margin-top: 6px;
  font-size: 12px;
  min-height: 18px;
  color: var(--el-text-color-secondary);
}
.chart {
  width: 100%;
  display: block;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}
.trajectory {
  max-height: 300px;
}
.line {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 2;
  stroke-linejoin: round;
}
.map-path {
  stroke: var(--el-border-color);
  stroke-width: 1;
}
.map-point {
  fill: var(--el-border-color-darker);
}
.grid line {
  stroke: var(--el-border-color-lighter);
}
.axis {
  font-size: 11px;
  fill: var(--el-text-color-secondary);
}
.crosshair {
  stroke: var(--el-text-color-secondary);
  stroke-dasharray: 3 3;
}
.focus {
  fill: var(--el-color-primary);
  stroke: var(--el-bg-color);
  stroke-width: 2;
}
.start {
  fill: var(--el-color-success);
  stroke: var(--el-bg-color);
  stroke-width: 2;
}
.end {
  fill: var(--el-text-color-primary);
  stroke: var(--el-bg-color);
  stroke-width: 2;
}
.legend {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin: 0 4px 0 10px;
}
.dot.start {
  background: var(--el-color-success);
}
.dot.end {
  background: var(--el-text-color-primary);
}
</style>
