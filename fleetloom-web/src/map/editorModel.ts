import type { MapContent, MapPath, MapPoint, PathDirection, PointType } from '@/api/types'

/**
 * Pure editing operations on a draft's content (requirement MAP-03). Each returns new content and
 * never mutates its input, so the editor can keep an undo history of plain snapshots.
 */

export function emptyContent(): MapContent {
  return { points: [], paths: [], areas: [] }
}

/** Next free point code with the given prefix: P1, P2, ... */
export function nextPointCode(content: MapContent, prefix = 'P'): string {
  const used = new Set(content.points.map((p) => p.code))
  for (let i = 1; ; i++) {
    const code = `${prefix}${i}`
    if (!used.has(code)) {
      return code
    }
  }
}

export function addPoint(content: MapContent, x: number, y: number, type: PointType = 'NORMAL', code?: string): MapContent {
  const point: MapPoint = { code: code ?? nextPointCode(content), x: round(x), y: round(y), type, properties: {} }
  if (content.points.some((p) => p.code === point.code)) {
    throw new Error(`点位编码 ${point.code} 已存在`)
  }
  return { ...content, points: [...content.points, point] }
}

export function movePoint(content: MapContent, code: string, x: number, y: number): MapContent {
  return { ...content, points: content.points.map((p) => (p.code === code ? { ...p, x: round(x), y: round(y) } : p)) }
}

export function updatePoint(content: MapContent, code: string, changes: Partial<MapPoint>): MapContent {
  const newCode = changes.code ?? code
  if (newCode !== code && content.points.some((p) => p.code === newCode)) {
    throw new Error(`点位编码 ${newCode} 已存在`)
  }
  return {
    ...content,
    points: content.points.map((p) => (p.code === code ? { ...p, ...changes } : p)),
    // keep paths pointing at a renamed point
    paths: newCode === code ? content.paths : content.paths.map((p) => ({
      ...p,
      fromPoint: p.fromPoint === code ? newCode : p.fromPoint,
      toPoint: p.toPoint === code ? newCode : p.toPoint,
    })),
  }
}

/** Whether a path already connects the two points, in either direction. */
export function connected(content: MapContent, a: string, b: string): boolean {
  return content.paths.some((p) => (p.fromPoint === a && p.toPoint === b) || (p.fromPoint === b && p.toPoint === a))
}

export function connect(content: MapContent, from: string, to: string, direction: PathDirection = 'BIDIRECTIONAL'): MapContent {
  if (from === to) {
    throw new Error('路径的起点和终点不能相同')
  }
  if (connected(content, from, to)) {
    throw new Error(`${from} 和 ${to} 之间已有路径`)
  }
  const path: MapPath = { code: `${from}-${to}`, fromPoint: from, toPoint: to, direction, shape: 'LINE', controlPoints: [], enabled: true }
  return { ...content, paths: [...content.paths, path] }
}

export function updatePath(content: MapContent, code: string, changes: Partial<MapPath>): MapContent {
  return { ...content, paths: content.paths.map((p) => (p.code === code ? { ...p, ...changes } : p)) }
}

/** Reverses a one-way path. */
export function reversePath(content: MapContent, code: string): MapContent {
  return {
    ...content,
    paths: content.paths.map((p) =>
      p.code === code ? { ...p, fromPoint: p.toPoint, toPoint: p.fromPoint, controlPoints: [...(p.controlPoints ?? [])].reverse() } : p,
    ),
  }
}

/** Removes points (with every path touching them) and paths. */
export function remove(content: MapContent, pointCodes: string[], pathCodes: string[] = []): MapContent {
  const points = new Set(pointCodes)
  const paths = new Set(pathCodes)
  return {
    ...content,
    points: content.points.filter((p) => !points.has(p.code)),
    paths: content.paths.filter((p) => !paths.has(p.code) && !points.has(p.fromPoint) && !points.has(p.toPoint)),
  }
}

/** Undo / redo over content snapshots. */
export class History {
  private past: MapContent[] = []
  private future: MapContent[] = []

  constructor(private limit = 100) {}

  /** Records the content as it was before a change. */
  push(before: MapContent): void {
    this.past.push(before)
    if (this.past.length > this.limit) {
      this.past.shift()
    }
    this.future = []
  }

  undo(current: MapContent): MapContent | null {
    const previous = this.past.pop()
    if (!previous) {
      return null
    }
    this.future.push(current)
    return previous
  }

  redo(current: MapContent): MapContent | null {
    const next = this.future.pop()
    if (!next) {
      return null
    }
    this.past.push(current)
    return next
  }

  clear(): void {
    this.past = []
    this.future = []
  }

  get canUndo(): boolean {
    return this.past.length > 0
  }

  get canRedo(): boolean {
    return this.future.length > 0
  }
}

function round(v: number): number {
  return Math.round(v * 1000) / 1000
}

// ------------------------------------------------------------------ import / export (MAP-09)

export const MAP_FILE_FORMAT = 'fleetloom-map'
/** Exports made before the project was renamed from RCS3000. */
const LEGACY_MAP_FILE_FORMATS = ['rcs3000-map']

/** The platform's own map file: content plus where it came from. */
export function exportMapFile(mapCode: string, versionNo: number | null, content: MapContent): string {
  const { points, paths, areas } = content
  return JSON.stringify({ format: MAP_FILE_FORMAT, formatVersion: 1, mapCode, versionNo, exportedAt: new Date().toISOString(), content: { points, paths, areas } }, null, 2)
}

/**
 * Reads a map file written by {@link exportMapFile}, or bare content as the save-content API takes it
 * (e.g. deploy/examples/demo-map.json). Throws with a message for the user when it is not usable;
 * the server's publish validation still checks everything else.
 */
export function parseMapFile(text: string): MapContent {
  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error('不是有效的 JSON 文件')
  }
  const body = isObject(data) && (data.format === MAP_FILE_FORMAT || LEGACY_MAP_FILE_FORMATS.includes(data.format as string)) ? data.content : data
  if (!isObject(body) || !Array.isArray(body.points)) {
    throw new Error('文件里没有地图内容（缺少 points）')
  }
  const points = body.points as MapPoint[]
  const paths = (Array.isArray(body.paths) ? body.paths : []) as MapPath[]
  const areas = (Array.isArray(body.areas) ? body.areas : []) as MapContent['areas']
  const codes = new Set<string>()
  for (const p of points) {
    if (!isObject(p) || typeof p.code !== 'string' || typeof p.x !== 'number' || typeof p.y !== 'number') {
      throw new Error('点位需要 code、x、y')
    }
    if (codes.has(p.code)) throw new Error(`点位编码 ${p.code} 重复`)
    codes.add(p.code)
  }
  for (const p of paths) {
    if (!isObject(p) || typeof p.code !== 'string' || !codes.has(p.fromPoint) || !codes.has(p.toPoint)) {
      throw new Error(`路径 ${isObject(p) ? p.code : '?'} 的起点或终点不在文件的点位里`)
    }
  }
  return {
    points: points.map((p) => ({ ...p, type: p.type ?? 'NORMAL', properties: p.properties ?? {} })),
    paths: paths.map((p) => ({ ...p, direction: p.direction ?? 'BIDIRECTIONAL' })),
    areas,
  }
}

function isObject(v: unknown): v is Record<string, any> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}
