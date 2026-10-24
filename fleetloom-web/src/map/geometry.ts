import type { MapContent, MapPath, MapPoint, Position } from '@/api/types'

/**
 * Maps world coordinates (meters, y up, as in VDA 5050) to screen pixels (y down):
 * screenX = x * scale + offsetX, screenY = -y * scale + offsetY.
 */
export interface View {
  scale: number
  offsetX: number
  offsetY: number
}

export function toScreen(view: View, p: Position): Position {
  return { x: p.x * view.scale + view.offsetX, y: -p.y * view.scale + view.offsetY }
}

export function toWorld(view: View, p: Position): Position {
  return { x: (p.x - view.offsetX) / view.scale, y: -(p.y - view.offsetY) / view.scale }
}

export interface Bounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export function bounds(points: Position[]): Bounds | null {
  if (points.length === 0) {
    return null
  }
  return points.reduce(
    (b, p) => ({ minX: Math.min(b.minX, p.x), minY: Math.min(b.minY, p.y), maxX: Math.max(b.maxX, p.x), maxY: Math.max(b.maxY, p.y) }),
    { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity },
  )
}

/** A view that shows {@code content} centered in a width × height canvas with {@code margin} pixels around it. */
export function fit(points: Position[], width: number, height: number, margin = 90): View {
  const b = bounds(points)
  if (!b) {
    return { scale: 40, offsetX: width / 2, offsetY: height / 2 }
  }
  const spanX = Math.max(b.maxX - b.minX, 1)
  const spanY = Math.max(b.maxY - b.minY, 1)
  const scale = Math.max(1, Math.min((width - 2 * margin) / spanX, (height - 2 * margin) / spanY))
  const centerX = (b.minX + b.maxX) / 2
  const centerY = (b.minY + b.maxY) / 2
  return { scale, offsetX: width / 2 - centerX * scale, offsetY: height / 2 + centerY * scale }
}

/** Zooms by {@code factor} keeping the world point under {@code screen} in place. */
export function zoomAt(view: View, screen: Position, factor: number, minScale = 2, maxScale = 400): View {
  const scale = Math.min(maxScale, Math.max(minScale, view.scale * factor))
  const world = toWorld(view, screen)
  return { scale, offsetX: screen.x - world.x * scale, offsetY: screen.y + world.y * scale }
}

export function snap(value: number, grid: number): number {
  return grid > 0 ? Math.round(value / grid) * grid : value
}

/** Polyline (world coordinates) of a path: straight, or a cubic Bezier sampled into {@code segments}. */
export function pathPolyline(path: MapPath, from: MapPoint, to: MapPoint, segments = 24): Position[] {
  const c = path.controlPoints ?? []
  if (path.shape !== 'BEZIER' || c.length !== 2) {
    return [from, to]
  }
  const result: Position[] = []
  for (let i = 0; i <= segments; i++) {
    const t = i / segments
    const u = 1 - t
    const a = u * u * u
    const b = 3 * u * u * t
    const cc = 3 * u * t * t
    const d = t * t * t
    result.push({
      x: a * from.x + b * c[0].x + cc * c[1].x + d * to.x,
      y: a * from.y + b * c[0].y + cc * c[1].y + d * to.y,
    })
  }
  return result
}

export function pointsByCode(content: Pick<MapContent, 'points'>): Map<string, MapPoint> {
  return new Map(content.points.map((p) => [p.code, p]))
}

/** Grid spacing (meters) of a readable background grid for the current zoom. */
export function gridStep(scale: number): number {
  const target = 60 / scale
  const steps = [0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 50, 100]
  return steps.find((s) => s >= target) ?? 100
}
