import { describe, expect, it } from 'vitest'
import { fit, gridStep, pathPolyline, snap, toScreen, toWorld, zoomAt } from '@/map/geometry'
import type { MapPath, MapPoint } from '@/api/types'

describe('geometry', () => {
  const view = { scale: 10, offsetX: 100, offsetY: 200 }

  it('flips the y axis between world and screen', () => {
    expect(toScreen(view, { x: 1, y: 2 })).toEqual({ x: 110, y: 180 })
    expect(toWorld(view, { x: 110, y: 180 })).toEqual({ x: 1, y: 2 })
  })

  it('fits content into the canvas, centered', () => {
    const v = fit([{ x: 0, y: 0 }, { x: 10, y: 5 }], 600, 400, 50)
    const topLeft = toScreen(v, { x: 0, y: 5 })
    const bottomRight = toScreen(v, { x: 10, y: 0 })
    expect(v.scale).toBe(50) // limited by width: (600 - 100) / 10
    expect((topLeft.x + bottomRight.x) / 2).toBeCloseTo(300)
    expect((topLeft.y + bottomRight.y) / 2).toBeCloseTo(200)
  })

  it('zooms around the cursor', () => {
    const cursor = { x: 150, y: 120 }
    const before = toWorld(view, cursor)
    const zoomed = zoomAt(view, cursor, 2)
    expect(zoomed.scale).toBe(20)
    const after = toWorld(zoomed, cursor)
    expect(after.x).toBeCloseTo(before.x)
    expect(after.y).toBeCloseTo(before.y)
  })

  it('clamps zoom', () => {
    expect(zoomAt(view, { x: 0, y: 0 }, 1000).scale).toBe(400)
  })

  it('snaps to a grid', () => {
    expect(snap(1.26, 0.5)).toBe(1.5)
    expect(snap(1.26, 0)).toBe(1.26)
  })

  it('samples Bezier paths through their ends', () => {
    const a: MapPoint = { code: 'A', x: 0, y: 0, type: 'NORMAL' }
    const b: MapPoint = { code: 'B', x: 10, y: 10, type: 'NORMAL' }
    const curve: MapPath = { code: 'AB', fromPoint: 'A', toPoint: 'B', direction: 'BIDIRECTIONAL', shape: 'BEZIER',
      controlPoints: [{ x: 10, y: 0 }, { x: 10, y: 5 }] }
    const line = pathPolyline(curve, a, b, 10)
    expect(line).toHaveLength(11)
    expect(line[0]).toEqual({ x: 0, y: 0 })
    expect(line[10]).toEqual({ x: 10, y: 10 })
    expect(pathPolyline({ ...curve, shape: 'LINE' }, a, b)).toEqual([a, b])
  })

  it('picks a readable grid step', () => {
    expect(gridStep(60)).toBe(1)
    expect(gridStep(10)).toBe(10)
  })
})
