<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { MapContent, MapPath, Position, StorageLocation } from '@/api/types'
import { LOCATION_COLORS, POINT_COLORS } from '@/map/colors'
import { fit, gridStep, pathPolyline, pointsByCode, toScreen, toWorld, zoomAt, type View } from '@/map/geometry'

/** A vehicle to draw; pose in world coordinates. */
export interface VehicleMarker {
  code: string
  x: number
  y: number
  theta: number
  color: string
  loaded?: boolean
  dimmed?: boolean
  /** footprint in m, default 1.2 × 0.8 */
  length?: number
  width?: number
  /** shown under the code, e.g. the vehicle type */
  caption?: string
}

/** A route to highlight: the driven part is solid, the rest dashed. */
export interface RouteMarker {
  vehicleCode: string
  nodes: string[]
  releasedIndex: number
  color: string
}

const props = withDefaults(
  defineProps<{
    content: MapContent | null
    locations?: StorageLocation[]
    vehicles?: VehicleMarker[]
    routes?: RouteMarker[]
    /** Resource ("NODE:P1", "PATH:P1-P2") → colour of the holding vehicle. */
    reservations?: Map<string, string>
    selectedPoints?: string[]
    selectedPaths?: string[]
    editable?: boolean
    showLabels?: boolean
  }>(),
  {
    locations: () => [],
    vehicles: () => [],
    routes: () => [],
    reservations: () => new Map(),
    selectedPoints: () => [],
    selectedPaths: () => [],
    editable: false,
    showLabels: true,
  },
)

const emit = defineEmits<{
  'background-click': [world: Position, event: MouseEvent]
  'point-click': [code: string, event: MouseEvent]
  'path-click': [code: string, event: MouseEvent]
  'point-drag': [code: string, world: Position]
  'point-drag-end': [code: string, world: Position]
  'cursor-move': [world: Position]
}>()

const container = ref<HTMLDivElement>()
const width = ref(800)
const height = ref(600)
const view = ref<View>({ scale: 40, offsetX: 400, offsetY: 300 })
let fitted = false
let observer: ResizeObserver | null = null

onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    width.value = Math.max(100, Math.floor(entry.contentRect.width))
    height.value = Math.max(100, Math.floor(entry.contentRect.height))
    if (!fitted) {
      fitView()
    }
  })
  observer.observe(container.value!)
})

onBeforeUnmount(() => observer?.disconnect())

watch(
  () => props.content,
  (content, previous) => {
    if (content && (!previous || !fitted)) {
      fitView()
    }
  },
)

function fitView() {
  const points = props.content?.points ?? []
  if (points.length === 0) {
    return
  }
  view.value = fit(points, width.value, height.value)
  fitted = true
}

defineExpose({ fitView })

// ------------------------------------------------------------------ scene

const points = computed(() => (props.content ? pointsByCode(props.content) : new Map()))

const gridLines = computed(() => {
  const step = gridStep(view.value.scale)
  const topLeft = toWorld(view.value, { x: 0, y: 0 })
  const bottomRight = toWorld(view.value, { x: width.value, y: height.value })
  const lines: { points: number[]; major: boolean }[] = []
  for (let x = Math.floor(topLeft.x / step) * step; x <= bottomRight.x; x += step) {
    const sx = toScreen(view.value, { x, y: 0 }).x
    lines.push({ points: [sx, 0, sx, height.value], major: Math.abs(x % (step * 5)) < 1e-9 })
  }
  for (let y = Math.floor(bottomRight.y / step) * step; y <= topLeft.y; y += step) {
    const sy = toScreen(view.value, { x: 0, y }).y
    lines.push({ points: [0, sy, width.value, sy], major: Math.abs(y % (step * 5)) < 1e-9 })
  }
  return lines
})

const areaShapes = computed(() =>
  (props.content?.areas ?? []).map((a) => ({
    code: a.code,
    points: a.polygon.flatMap((p) => {
      const s = toScreen(view.value, p)
      return [s.x, s.y]
    }),
    fill: a.type === 'FORBIDDEN' ? 'rgba(245,108,108,0.15)' : a.type === 'MUTEX' ? 'rgba(230,162,60,0.15)' : 'rgba(64,158,255,0.1)',
    stroke: a.type === 'FORBIDDEN' ? '#f56c6c' : a.type === 'MUTEX' ? '#e6a23c' : '#409eff',
    label: `${a.code} (${a.type})`,
    labelAt: toScreen(view.value, a.polygon[0] ?? { x: 0, y: 0 }),
  })),
)

const pathShapes = computed(() => {
  const selected = new Set(props.selectedPaths)
  return (props.content?.paths ?? []).flatMap((path: MapPath) => {
    const from = points.value.get(path.fromPoint)
    const to = points.value.get(path.toPoint)
    if (!from || !to) {
      return []
    }
    const line = pathPolyline(path, from, to).map((p) => toScreen(view.value, p))
    const mid = line[Math.floor(line.length / 2)]
    const before = line[Math.max(0, Math.floor(line.length / 2) - 1)]
    const angle = Math.atan2(mid.y - before.y, mid.x - before.x)
    const reservedBy = props.reservations.get(`PATH:${path.code}`)
    return [{
      code: path.code,
      points: line.flatMap((p) => [p.x, p.y]),
      color: selected.has(path.code) ? '#409eff' : reservedBy ?? (path.enabled === false ? '#c0c4cc' : '#a8abb2'),
      width: selected.has(path.code) ? 4 : reservedBy ? 4 : 2,
      dash: path.enabled === false ? [6, 4] : undefined,
      oneWay: path.direction === 'UNIDIRECTIONAL',
      arrow: [mid.x - Math.cos(angle) * 6, mid.y - Math.sin(angle) * 6, mid.x + Math.cos(angle) * 6, mid.y + Math.sin(angle) * 6],
    }]
  })
})

const routeShapes = computed(() =>
  props.routes.flatMap((r) => {
    const screen = r.nodes.map((n) => points.value.get(n)).filter((p) => p).map((p) => toScreen(view.value, p!))
    if (screen.length < 2) {
      return []
    }
    const released = screen.slice(0, r.releasedIndex + 1)
    const ahead = screen.slice(r.releasedIndex)
    return [
      { key: `${r.vehicleCode}-ahead`, points: ahead.flatMap((p) => [p.x, p.y]), color: r.color, dash: [8, 6], opacity: 0.5 },
      { key: `${r.vehicleCode}-base`, points: released.flatMap((p) => [p.x, p.y]), color: r.color, dash: undefined, opacity: 0.8 },
    ]
  }),
)

const pointShapes = computed(() => {
  const selected = new Set(props.selectedPoints)
  const radius = Math.min(10, Math.max(4, view.value.scale * 0.15))
  return (props.content?.points ?? []).map((p) => {
    const s = toScreen(view.value, p)
    const reservedBy = props.reservations.get(`NODE:${p.code}`)
    return {
      code: p.code,
      x: s.x,
      y: s.y,
      radius: selected.has(p.code) ? radius + 2 : radius,
      fill: POINT_COLORS[p.type] ?? '#606266',
      stroke: selected.has(p.code) ? '#409eff' : reservedBy ?? '#ffffff',
      strokeWidth: selected.has(p.code) || reservedBy ? 3 : 1.5,
    }
  })
})

const locationShapes = computed(() => {
  const size = Math.min(26, Math.max(10, view.value.scale * 0.6))
  return props.locations.flatMap((l) => {
    const p = points.value.get(l.accessPoint)
    if (!p) {
      return []
    }
    const s = toScreen(view.value, p)
    return [{
      code: l.code,
      x: s.x + size * 0.35,
      y: s.y - size * 1.35,
      size,
      fill: LOCATION_COLORS[l.status],
      label: l.carrierCode ?? l.code,
    }]
  })
})

const vehicleShapes = computed(() =>
  props.vehicles.map((v) => {
    const s = toScreen(view.value, v)
    const length = Math.max(14, (v.length ?? 1.2) * view.value.scale)
    const breadth = Math.max(10, (v.width ?? 0.8) * view.value.scale)
    return {
      code: v.code,
      x: s.x,
      y: s.y,
      rotation: (-v.theta * 180) / Math.PI,
      length,
      breadth,
      color: v.color,
      opacity: v.dimmed ? 0.4 : 1,
      loaded: v.loaded,
      caption: v.caption,
    }
  }),
)

// ------------------------------------------------------------------ interaction

let pan: { startX: number; startY: number; view: View; moved: boolean } | null = null

function pointer(event: MouseEvent): Position {
  const rect = container.value!.getBoundingClientRect()
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}

function onWheel(e: { evt: WheelEvent }) {
  e.evt.preventDefault()
  view.value = zoomAt(view.value, pointer(e.evt), e.evt.deltaY < 0 ? 1.15 : 1 / 1.15)
}

function onStageDown(e: { evt: MouseEvent; target: { getStage(): unknown } }) {
  if (e.target !== e.target.getStage()) {
    return
  }
  pan = { startX: e.evt.clientX, startY: e.evt.clientY, view: { ...view.value }, moved: false }
}

function onStageMove(e: { evt: MouseEvent }) {
  emit('cursor-move', toWorld(view.value, pointer(e.evt)))
  if (!pan) {
    return
  }
  const dx = e.evt.clientX - pan.startX
  const dy = e.evt.clientY - pan.startY
  if (Math.abs(dx) + Math.abs(dy) > 3) {
    pan.moved = true
  }
  if (pan.moved) {
    view.value = { ...pan.view, offsetX: pan.view.offsetX + dx, offsetY: pan.view.offsetY + dy }
  }
}

function onStageUp(e: { evt: MouseEvent }) {
  if (pan && !pan.moved) {
    emit('background-click', toWorld(view.value, pointer(e.evt)), e.evt)
  }
  pan = null
}

function dragWorld(e: { target: { x(): number; y(): number } }): Position {
  return toWorld(view.value, { x: e.target.x(), y: e.target.y() })
}
</script>

<template>
  <div ref="container" class="map-canvas">
    <v-stage
      :config="{ width, height }"
      @wheel="onWheel"
      @mousedown="onStageDown"
      @mousemove="onStageMove"
      @mouseup="onStageUp"
      @mouseleave="pan = null"
    >
      <v-layer :config="{ listening: false }">
        <v-line
          v-for="(g, i) in gridLines"
          :key="'g' + i"
          :config="{ points: g.points, stroke: g.major ? '#e4e7ed' : '#f2f3f5', strokeWidth: 1 }"
        />
        <template v-for="a in areaShapes" :key="'a' + a.code">
          <v-line :config="{ points: a.points, closed: true, fill: a.fill, stroke: a.stroke, strokeWidth: 1, dash: [4, 4] }" />
          <v-text :config="{ x: a.labelAt.x + 4, y: a.labelAt.y + 4, text: a.label, fontSize: 11, fill: a.stroke }" />
        </template>
      </v-layer>

      <v-layer>
        <template v-for="p in pathShapes" :key="'p' + p.code">
          <v-line
            :config="{ points: p.points, stroke: p.color, strokeWidth: p.width, dash: p.dash, hitStrokeWidth: 12, lineCap: 'round' }"
            @click="(e: { evt: MouseEvent }) => emit('path-click', p.code, e.evt)"
          />
          <v-arrow
            v-if="p.oneWay"
            :config="{ points: p.arrow, pointerLength: 7, pointerWidth: 7, fill: p.color, stroke: p.color, listening: false }"
          />
        </template>
        <v-line
          v-for="r in routeShapes"
          :key="'r' + r.key"
          :config="{ points: r.points, stroke: r.color, strokeWidth: 3, dash: r.dash, opacity: r.opacity, listening: false }"
        />
        <template v-for="l in locationShapes" :key="'l' + l.code">
          <v-rect
            :config="{ x: l.x, y: l.y, width: l.size, height: l.size, fill: l.fill, stroke: '#909399', strokeWidth: 1, cornerRadius: 2, listening: false }"
          />
          <v-text
            v-if="showLabels"
            :config="{ x: l.x + l.size + 3, y: l.y, text: l.label, fontSize: 11, fill: '#606266', listening: false }"
          />
        </template>
        <template v-for="p in pointShapes" :key="'n' + p.code">
          <v-circle
            :config="{ x: p.x, y: p.y, radius: p.radius, fill: p.fill, stroke: p.stroke, strokeWidth: p.strokeWidth, draggable: editable, hitStrokeWidth: 8 }"
            @click="(e: { evt: MouseEvent }) => emit('point-click', p.code, e.evt)"
            @dragmove="(e: any) => emit('point-drag', p.code, dragWorld(e))"
            @dragend="(e: any) => emit('point-drag-end', p.code, dragWorld(e))"
          />
          <v-text
            v-if="showLabels"
            :config="{ x: p.x + 8, y: p.y + 6, text: p.code, fontSize: 11, fill: '#303133', listening: false }"
          />
        </template>
      </v-layer>

      <v-layer :config="{ listening: false }">
        <v-group v-for="v in vehicleShapes" :key="'v' + v.code" :config="{ x: v.x, y: v.y, rotation: v.rotation, opacity: v.opacity }">
          <v-rect
            :config="{ x: -v.length / 2, y: -v.breadth / 2, width: v.length, height: v.breadth, fill: v.color, cornerRadius: 3, stroke: '#303133', strokeWidth: 1 }"
          />
          <v-line :config="{ points: [v.length / 2 - 6, -v.breadth / 2 + 3, v.length / 2 - 1, 0, v.length / 2 - 6, v.breadth / 2 - 3], stroke: '#ffffff', strokeWidth: 2 }" />
          <v-rect
            v-if="v.loaded"
            :config="{ x: -v.length / 4, y: -v.breadth / 4, width: v.length / 2, height: v.breadth / 2, fill: '#ffffff', opacity: 0.8 }"
          />
        </v-group>
        <v-text
          v-for="v in vehicleShapes"
          :key="'vl' + v.code"
          :config="{ x: v.x + v.length / 2 + 2, y: v.y - v.breadth - 6, text: v.caption ? `${v.code} · ${v.caption}` : v.code, fontSize: 12, fontStyle: 'bold', fill: v.color }"
        />
      </v-layer>
    </v-stage>
  </div>
</template>

<style scoped>
.map-canvas {
  width: 100%;
  height: 100%;
  min-height: 300px;
  background: #ffffff;
  overflow: hidden;
  cursor: grab;
}
</style>
