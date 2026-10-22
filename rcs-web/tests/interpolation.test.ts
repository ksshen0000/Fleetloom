import { describe, expect, it } from 'vitest'
import { angleDiff, PoseInterpolator } from '@/map/interpolation'

describe('pose interpolation', () => {
  it('moves from the previous to the latest sample over one interval', () => {
    const i = new PoseInterpolator()
    i.update('V', { x: 0, y: 0, theta: 0 }, 0)
    i.update('V', { x: 10, y: 0, theta: 0 }, 1000)

    expect(i.at('V', 1000)).toEqual({ x: 0, y: 0, theta: 0 })
    expect(i.at('V', 1500)!.x).toBeCloseTo(5)
    expect(i.at('V', 3000)!.x).toBe(10)
  })

  it('continues from where it is drawn when a sample arrives early', () => {
    const i = new PoseInterpolator()
    i.update('V', { x: 0, y: 0, theta: 0 }, 0)
    i.update('V', { x: 10, y: 0, theta: 0 }, 1000)
    i.update('V', { x: 20, y: 0, theta: 0 }, 1500)

    expect(i.at('V', 1500)!.x).toBeCloseTo(5)
  })

  it('turns the short way round', () => {
    expect(angleDiff(3, -3)).toBeCloseTo(2 * Math.PI - 6)
    const i = new PoseInterpolator()
    i.update('V', { x: 0, y: 0, theta: 3 }, 0)
    i.update('V', { x: 0, y: 0, theta: -3 }, 1000)
    expect(i.at('V', 1500)!.theta).toBeGreaterThan(3)
  })

  it('knows nothing about unknown vehicles', () => {
    expect(new PoseInterpolator().at('X', 0)).toBeNull()
  })
})
