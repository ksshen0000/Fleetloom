<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { mapsApi } from '@/api'
import { ApiError } from '@/api/http'
import type { MapContent, MapPath, MapPoint, MapVersionSummary, MapView, PathDirection, PointType, Position, ValidationIssue, ValidationReport } from '@/api/types'
import MapCanvas from '@/components/map/MapCanvas.vue'
import { act, confirm, formatTime } from '@/composables/notify'
import { addPoint, connect, emptyContent, exportMapFile, History, movePoint, parseMapFile, remove, reversePath, updatePath, updatePoint } from '@/map/editorModel'
import { snap } from '@/map/geometry'

const POINT_TYPES: PointType[] = ['NORMAL', 'WORKSTATION', 'CHARGING', 'PARKING', 'ROTATION', 'WAITING']

const maps = ref<MapView[]>([])
const mapCode = ref('')
const versions = ref<MapVersionSummary[]>([])
const versionNo = ref<number | null>(null)
const status = ref<string>('')
const revision = ref(0)
const content = ref<MapContent>(emptyContent())
const dirty = ref(false)
const issues = ref<ValidationIssue[]>([])
const history = new History()
const historyTick = ref(0)

const tool = ref<'select' | 'point' | 'path'>('select')
const newPointType = ref<PointType>('NORMAL')
const pathDirection = ref<PathDirection>('BIDIRECTIONAL')
const grid = ref(0.5)
const pathStart = ref<string | null>(null)
const selectedPoints = ref<string[]>([])
const selectedPaths = ref<string[]>([])
const cursor = ref<Position>({ x: 0, y: 0 })
const canvas = ref<InstanceType<typeof MapCanvas>>()

const editable = computed(() => status.value === 'DRAFT')
const hasDraft = computed(() => versions.value.some((v) => v.status === 'DRAFT'))
const canUndo = computed(() => (void historyTick.value, history.canUndo))
const canRedo = computed(() => (void historyTick.value, history.canRedo))

// ------------------------------------------------------------------ loading

async function loadMaps() {
  maps.value = await mapsApi.list()
  if (!mapCode.value && maps.value.length) {
    mapCode.value = maps.value[0].code
  }
}

async function loadVersions(preferNo?: number) {
  versions.value = await mapsApi.versions(mapCode.value)
  const draft = versions.value.find((v) => v.status === 'DRAFT')
  const published = versions.value.find((v) => v.status === 'PUBLISHED')
  const no = preferNo ?? draft?.versionNo ?? published?.versionNo ?? versions.value[0]?.versionNo ?? null
  if (no === versionNo.value) {
    // same number on another map, or reloaded after a publish: the watcher would not fire
    await loadVersion(no)
  } else {
    versionNo.value = no
  }
}

watch(mapCode, async (code) => {
  if (code && (await guardUnsaved())) {
    await loadVersions()
  }
})

watch(versionNo, (no) => loadVersion(no))

async function loadVersion(no: number | null) {
  if (no === null) {
    content.value = emptyContent()
    status.value = ''
    return
  }
  const v = await mapsApi.version(mapCode.value, no)
  content.value = { points: v.points, paths: v.paths, areas: v.areas }
  status.value = v.status
  revision.value = v.revision
  dirty.value = false
  issues.value = []
  history.clear()
  historyTick.value++
  clearSelection()
  requestAnimationFrame(() => canvas.value?.fitView())
}

onMounted(loadMaps)

async function guardUnsaved(): Promise<boolean> {
  return !dirty.value || confirm('当前草稿有未保存的修改，放弃这些修改？')
}

// ------------------------------------------------------------------ editing

function change(update: (c: MapContent) => MapContent) {
  try {
    const next = update(content.value)
    history.push(content.value)
    historyTick.value++
    content.value = next
    dirty.value = true
  } catch (e) {
    ElMessage.warning(e instanceof Error ? e.message : String(e))
  }
}

function undo() {
  const previous = history.undo(content.value)
  if (previous) {
    content.value = previous
    dirty.value = true
    historyTick.value++
  }
}

function redo() {
  const next = history.redo(content.value)
  if (next) {
    content.value = next
    dirty.value = true
    historyTick.value++
  }
}

function clearSelection() {
  selectedPoints.value = []
  selectedPaths.value = []
  pathStart.value = null
}

function deleteSelection() {
  if (!editable.value || (selectedPoints.value.length === 0 && selectedPaths.value.length === 0)) {
    return
  }
  change((c) => remove(c, selectedPoints.value, selectedPaths.value))
  clearSelection()
}

function onBackgroundClick(world: Position) {
  if (editable.value && tool.value === 'point') {
    change((c) => addPoint(c, snap(world.x, grid.value), snap(world.y, grid.value), newPointType.value))
    return
  }
  clearSelection()
}

function onPointClick(code: string, event: MouseEvent) {
  if (editable.value && tool.value === 'path') {
    if (pathStart.value && pathStart.value !== code) {
      const from = pathStart.value
      change((c) => connect(c, from, code, pathDirection.value))
    }
    pathStart.value = code
    selectedPoints.value = [code]
    selectedPaths.value = []
    return
  }
  toggle(selectedPoints, code, event.shiftKey)
  if (!event.shiftKey) {
    selectedPaths.value = []
  }
}

function onPathClick(code: string, event: MouseEvent) {
  toggle(selectedPaths, code, event.shiftKey)
  if (!event.shiftKey) {
    selectedPoints.value = []
  }
}

function toggle(list: typeof selectedPoints, code: string, additive: boolean) {
  if (!additive) {
    list.value = [code]
  } else {
    list.value = list.value.includes(code) ? list.value.filter((c) => c !== code) : [...list.value, code]
  }
}

let dragOrigin: MapContent | null = null

function onPointDrag(code: string, world: Position) {
  if (!editable.value) {
    return
  }
  dragOrigin ??= content.value
  content.value = movePoint(content.value, code, world.x, world.y)
}

function onPointDragEnd(code: string, world: Position) {
  if (!editable.value || !dragOrigin) {
    return
  }
  const origin = dragOrigin
  dragOrigin = null
  content.value = origin
  change((c) => movePoint(c, code, snap(world.x, grid.value), snap(world.y, grid.value)))
}

function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement
  if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
    return
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    deleteSelection()
  } else if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
    e.preventDefault()
    undo()
  } else if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
    e.preventDefault()
    redo()
  } else if (e.key === 'Escape') {
    clearSelection()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// ------------------------------------------------------------------ properties panel

const selectedPoint = computed<MapPoint | undefined>(() =>
  selectedPoints.value.length === 1 && selectedPaths.value.length === 0
    ? content.value.points.find((p) => p.code === selectedPoints.value[0])
    : undefined,
)

const selectedPath = computed<MapPath | undefined>(() =>
  selectedPaths.value.length === 1 && selectedPoints.value.length === 0
    ? content.value.paths.find((p) => p.code === selectedPaths.value[0])
    : undefined,
)

function editPoint(changes: Partial<MapPoint>) {
  const code = selectedPoint.value!.code
  change((c) => updatePoint(c, code, changes))
  if (changes.code) {
    selectedPoints.value = [changes.code]
  }
}

function editPath(changes: Partial<MapPath>) {
  const code = selectedPath.value!.code
  change((c) => updatePath(c, code, changes))
}

function setCurved(curved: boolean) {
  const path = selectedPath.value!
  if (!curved) {
    editPath({ shape: 'LINE', controlPoints: [] })
    return
  }
  const from = content.value.points.find((p) => p.code === path.fromPoint)!
  const to = content.value.points.find((p) => p.code === path.toPoint)!
  // control points a third of the way along, pushed sideways so the curve is visible
  const dx = to.x - from.x
  const dy = to.y - from.y
  const nx = -dy * 0.25
  const ny = dx * 0.25
  editPath({
    shape: 'BEZIER',
    controlPoints: [
      { x: from.x + dx / 3 + nx, y: from.y + dy / 3 + ny },
      { x: from.x + (2 * dx) / 3 + nx, y: from.y + (2 * dy) / 3 + ny },
    ],
  })
}

const thetaDegrees = computed({
  get: () => (selectedPoint.value?.theta == null ? null : Math.round((selectedPoint.value.theta * 180) / Math.PI)),
  set: (deg: number | null) => editPoint({ theta: deg == null ? null : (deg * Math.PI) / 180 }),
})

function selectIssue(issue: ValidationIssue) {
  if (!issue.elementCode) {
    return
  }
  if (content.value.points.some((p) => p.code === issue.elementCode)) {
    selectedPoints.value = [issue.elementCode]
    selectedPaths.value = []
  } else if (content.value.paths.some((p) => p.code === issue.elementCode)) {
    selectedPaths.value = [issue.elementCode]
    selectedPoints.value = []
  }
}

// ------------------------------------------------------------------ versions

const newMap = reactive({ open: false, code: '', name: '' })

async function createMap() {
  const created = await act(() => mapsApi.create({ code: newMap.code, name: newMap.name }), '地图已创建')
  if (created) {
    newMap.open = false
    await loadMaps()
    mapCode.value = created.code
  }
}

async function createDraft() {
  const active = maps.value.find((m) => m.code === mapCode.value)?.activeVersionNo ?? undefined
  const draft = await act(() => mapsApi.createDraft(mapCode.value, active), active ? `已基于 v${active} 创建草稿` : '已创建空白草稿')
  if (draft) {
    await loadVersions(draft.versionNo)
  }
}

async function save(): Promise<boolean> {
  if (!versionNo.value) {
    return false
  }
  try {
    const result = await mapsApi.saveContent(mapCode.value, versionNo.value, revision.value, content.value)
    revision.value = result.revision
    issues.value = result.validation.issues
    dirty.value = false
    ElMessage.success(`已保存（修订 ${result.revision}）`)
    return true
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.code === 'MAP_REVISION_CONFLICT' ? '草稿已被他人修改，请重新加载后再编辑' : `${err.message}（${err.code}）`)
    return false
  }
}

async function validate() {
  if (dirty.value && !(await save())) {
    return
  }
  const report = await act(() => mapsApi.validate(mapCode.value, versionNo.value!))
  if (report) {
    issues.value = report.issues
    ElMessage[report.issues.some((i) => i.severity === 'ERROR') ? 'warning' : 'success'](
      report.issues.length ? `发现 ${report.issues.length} 个问题` : '校验通过',
    )
  }
}

async function publish() {
  if (dirty.value && !(await save())) {
    return
  }
  if (!(await confirm(`发布 v${versionNo.value}？发布后成为当前生效版本，旧版本归档。`))) {
    return
  }
  try {
    const report = await mapsApi.publish(mapCode.value, versionNo.value!)
    ElMessage.success('已发布')
    await loadMaps()
    await loadVersions(versionNo.value!)
    // warnings do not stop a publish; keep showing them
    issues.value = report.issues
  } catch (e) {
    const err = e as ApiError
    if (err.code === 'MAP_VALIDATION_FAILED') {
      issues.value = (err.details as ValidationReport).issues
    }
    ElMessage.error(err.message)
  }
}

async function deleteDraft() {
  if (await confirm(`删除草稿 v${versionNo.value}？`)) {
    if ((await act(() => mapsApi.deleteDraft(mapCode.value, versionNo.value!), '草稿已删除')) !== null) {
      dirty.value = false
      await loadVersions()
    }
  }
}

// ------------------------------------------------------------------ import / export (MAP-09)

const importInput = ref<HTMLInputElement>()

function exportMap() {
  const blob = new Blob([exportMapFile(mapCode.value, versionNo.value, content.value)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${mapCode.value}-v${versionNo.value ?? 0}.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

async function importMap(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    const imported = parseMapFile(await file.text())
    if (content.value.points.length && !(await confirm(`用 ${file.name} 的内容（${imported.points.length} 个点位、${imported.paths.length} 条路径）替换当前草稿？保存前可撤销。`))) {
      return
    }
    change(() => imported)
    clearSelection()
    ElMessage.success('已导入，检查后保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function reload() {
  if (await guardUnsaved()) {
    const no = versionNo.value
    versionNo.value = null
    await loadVersions(no ?? undefined)
  }
}

const STATUS_LABELS: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '生效', ARCHIVED: '归档' }
</script>

<template>
  <div class="editor">
    <div class="toolbar bar">
      <el-select v-model="mapCode" placeholder="选择地图" style="width: 160px">
        <el-option v-for="m in maps" :key="m.code" :label="m.name" :value="m.code" />
      </el-select>
      <el-button size="small" @click="newMap.open = true">新建地图</el-button>
      <el-select v-model="versionNo" placeholder="版本" style="width: 150px">
        <el-option v-for="v in versions" :key="v.versionNo" :label="`v${v.versionNo} ${STATUS_LABELS[v.status]}`" :value="v.versionNo" />
      </el-select>
      <el-button v-if="mapCode && !hasDraft" size="small" @click="createDraft">新建草稿</el-button>
      <template v-if="editable">
        <el-divider direction="vertical" />
        <el-radio-group v-model="tool" size="small" @change="pathStart = null">
          <el-radio-button value="select">选择</el-radio-button>
          <el-radio-button value="point">加点</el-radio-button>
          <el-radio-button value="path">连线</el-radio-button>
        </el-radio-group>
        <el-select v-if="tool === 'point'" v-model="newPointType" size="small" style="width: 130px">
          <el-option v-for="t in POINT_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-if="tool === 'path'" v-model="pathDirection" size="small" style="width: 100px">
          <el-option label="双向" value="BIDIRECTIONAL" />
          <el-option label="单向" value="UNIDIRECTIONAL" />
        </el-select>
        <span class="label">网格</span>
        <el-input-number v-model="grid" :min="0" :max="5" :step="0.25" size="small" style="width: 100px" />
        <el-button size="small" :disabled="!canUndo" @click="undo">撤销</el-button>
        <el-button size="small" :disabled="!canRedo" @click="redo">重做</el-button>
        <el-button size="small" :disabled="!selectedPoints.length && !selectedPaths.length" @click="deleteSelection">删除</el-button>
        <span class="spacer" />
        <el-button size="small" @click="importInput?.click()">导入</el-button>
        <el-button size="small" @click="exportMap">导出</el-button>
        <input ref="importInput" type="file" accept=".json,application/json" hidden data-testid="map-import" @change="importMap" />
        <el-button size="small" @click="reload">重新加载</el-button>
        <el-button size="small" type="danger" plain @click="deleteDraft">删除草稿</el-button>
        <el-button size="small" @click="validate">校验</el-button>
        <el-button size="small" type="primary" :disabled="!dirty" @click="save">保存</el-button>
        <el-button size="small" type="success" @click="publish">发布</el-button>
      </template>
      <template v-else-if="status">
        <span class="spacer" />
        <el-button size="small" @click="exportMap">导出</el-button>
        <el-tag type="info">只读：{{ STATUS_LABELS[status] }}版本，新建草稿后才能编辑</el-tag>
      </template>
    </div>

    <div class="body">
      <div class="canvas">
        <MapCanvas
          ref="canvas"
          :content="content"
          :editable="editable && tool === 'select'"
          :selected-points="selectedPoints"
          :selected-paths="selectedPaths"
          @background-click="onBackgroundClick"
          @point-click="onPointClick"
          @path-click="onPathClick"
          @point-drag="onPointDrag"
          @point-drag-end="onPointDragEnd"
          @cursor-move="(p: Position) => (cursor = p)"
        />
        <div class="status-line">
          ({{ cursor.x.toFixed(2) }}, {{ cursor.y.toFixed(2) }}) m · {{ content.points.length }} 点 · {{ content.paths.length }} 路径
          <template v-if="dirty"> · <b>未保存</b></template>
          <template v-if="pathStart"> · 连线起点 {{ pathStart }}，继续点击下一个点位</template>
        </div>
      </div>

      <div class="panel">
        <template v-if="selectedPoint">
          <h4>点位 {{ selectedPoint.code }}</h4>
          <el-form label-width="64px" size="small" :disabled="!editable">
            <el-form-item label="编码">
              <el-input :model-value="selectedPoint.code" @change="(v: string) => v && editPoint({ code: v })" />
            </el-form-item>
            <el-form-item label="X (m)">
              <el-input-number :model-value="selectedPoint.x" :step="grid || 0.1" @change="(v: number | undefined) => editPoint({ x: v ?? 0 })" />
            </el-form-item>
            <el-form-item label="Y (m)">
              <el-input-number :model-value="selectedPoint.y" :step="grid || 0.1" @change="(v: number | undefined) => editPoint({ y: v ?? 0 })" />
            </el-form-item>
            <el-form-item label="朝向 °">
              <el-input-number v-model="thetaDegrees" :min="-180" :max="180" placeholder="不限" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select :model-value="selectedPoint.type" @change="(v: PointType) => editPoint({ type: v })">
                <el-option v-for="t in POINT_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-form>
        </template>

        <template v-else-if="selectedPath">
          <h4>路径 {{ selectedPath.code }}</h4>
          <p class="hint">{{ selectedPath.fromPoint }} → {{ selectedPath.toPoint }}<template v-if="selectedPath.length">，{{ selectedPath.length.toFixed(2) }} m</template></p>
          <el-form label-width="72px" size="small" :disabled="!editable">
            <el-form-item label="方向">
              <el-radio-group :model-value="selectedPath.direction" @change="(v: string | number | boolean | undefined) => editPath({ direction: v as PathDirection })">
                <el-radio value="BIDIRECTIONAL">双向</el-radio>
                <el-radio value="UNIDIRECTIONAL">单向</el-radio>
              </el-radio-group>
              <el-button v-if="selectedPath.direction === 'UNIDIRECTIONAL'" link @click="change((c) => reversePath(c, selectedPath!.code))">反转</el-button>
            </el-form-item>
            <el-form-item label="曲线">
              <el-switch :model-value="selectedPath.shape === 'BEZIER'" @change="(v: string | number | boolean) => setCurved(!!v)" />
            </el-form-item>
            <el-form-item label="限速 m/s">
              <el-input-number :model-value="selectedPath.maxSpeed ?? undefined" :min="0.1" :step="0.1" placeholder="不限"
                @change="(v: number | undefined) => editPath({ maxSpeed: v ?? null })" />
            </el-form-item>
            <el-form-item label="权重">
              <el-input-number :model-value="selectedPath.weightFactor ?? 1" :min="0.1" :step="0.1" @change="(v: number | undefined) => editPath({ weightFactor: v ?? 1 })" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch :model-value="selectedPath.enabled !== false" @change="(v: string | number | boolean) => editPath({ enabled: !!v })" />
            </el-form-item>
          </el-form>
        </template>

        <template v-else-if="selectedPoints.length + selectedPaths.length > 1">
          <h4>已选 {{ selectedPoints.length }} 个点位、{{ selectedPaths.length }} 条路径</h4>
          <el-button size="small" type="danger" :disabled="!editable" @click="deleteSelection">删除所选</el-button>
        </template>

        <template v-else>
          <h4>操作说明</h4>
          <ul class="hint">
            <li>滚轮缩放，拖动空白处平移</li>
            <li>「加点」：点击空白处添加点位，按网格对齐</li>
            <li>「连线」：依次点击点位，连成一串路径</li>
            <li>「选择」：点击选中，Shift 多选，拖动点位移动；Delete 删除，Ctrl+Z / Ctrl+Y 撤销重做</li>
          </ul>
        </template>

        <el-divider />
        <h4>校验结果</h4>
        <el-empty v-if="issues.length === 0" description="保存或校验后显示" :image-size="40" />
        <div v-for="(i, n) in issues" :key="n" class="issue" :class="i.severity" @click="selectIssue(i)">
          <el-tag size="small" :type="i.severity === 'ERROR' ? 'danger' : 'warning'">{{ i.severity === 'ERROR' ? '错误' : '警告' }}</el-tag>
          <span>{{ i.elementCode ? i.elementCode + '：' : '' }}{{ i.message }}</span>
        </div>
        <template v-if="versions.length">
          <el-divider />
          <h4>版本历史</h4>
          <div v-for="v in versions" :key="v.versionNo" class="hint">
            v{{ v.versionNo }} {{ STATUS_LABELS[v.status] }}
            <template v-if="v.publishedAt">· {{ formatTime(v.publishedAt) }} {{ v.publishedBy }}</template>
          </div>
        </template>
      </div>
    </div>

    <el-dialog v-model="newMap.open" title="新建地图" width="380px">
      <el-form label-width="60px">
        <el-form-item label="编码"><el-input v-model="newMap.code" placeholder="字母、数字、- 或 _" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="newMap.name" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newMap.open = false">取消</el-button>
        <el-button type="primary" @click="createMap">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.bar {
  padding: 8px 12px;
  margin: 0;
  border-bottom: 1px solid var(--el-border-color-light);
}
.label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.canvas {
  flex: 1;
  position: relative;
  min-width: 0;
}
.status-line {
  position: absolute;
  left: 8px;
  bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: rgba(255, 255, 255, 0.85);
  padding: 2px 6px;
  border-radius: 3px;
}
.panel {
  width: 300px;
  border-left: 1px solid var(--el-border-color-light);
  padding: 8px 12px;
  overflow: auto;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding-left: 16px;
}
.issue {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  font-size: 12px;
  padding: 4px 0;
  cursor: pointer;
}
</style>
