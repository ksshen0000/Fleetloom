import { describe, expect, it } from 'vitest'
import { addPoint, connect, emptyContent, History, movePoint, nextPointCode, remove, reversePath, updatePoint } from '@/map/editorModel'

describe('editor model', () => {
  const twoPoints = () => addPoint(addPoint(emptyContent(), 0, 0), 4, 0)

  it('numbers new points', () => {
    const c = twoPoints()
    expect(c.points.map((p) => p.code)).toEqual(['P1', 'P2'])
    expect(nextPointCode(remove(c, ['P1']))).toBe('P1')
  })

  it('connects points once, in either direction', () => {
    const c = connect(twoPoints(), 'P1', 'P2')
    expect(c.paths).toEqual([expect.objectContaining({ code: 'P1-P2', direction: 'BIDIRECTIONAL' })])
    expect(() => connect(c, 'P2', 'P1')).toThrow('已有路径')
    expect(() => connect(c, 'P1', 'P1')).toThrow()
  })

  it('removing a point removes its paths', () => {
    const c = connect(addPoint(connect(twoPoints(), 'P1', 'P2'), 8, 0), 'P2', 'P3')
    const after = remove(c, ['P2'])
    expect(after.points.map((p) => p.code)).toEqual(['P1', 'P3'])
    expect(after.paths).toHaveLength(0)
  })

  it('renaming a point keeps its paths connected', () => {
    const c = updatePoint(connect(twoPoints(), 'P1', 'P2'), 'P1', { code: 'A' })
    expect(c.paths[0]).toMatchObject({ fromPoint: 'A', toPoint: 'P2' })
    expect(() => updatePoint(c, 'A', { code: 'P2' })).toThrow('已存在')
  })

  it('does not mutate its input', () => {
    const c = twoPoints()
    movePoint(c, 'P1', 9, 9)
    expect(c.points[0]).toMatchObject({ x: 0, y: 0 })
  })

  it('reverses one-way paths', () => {
    const c = reversePath(connect(twoPoints(), 'P1', 'P2', 'UNIDIRECTIONAL'), 'P1-P2')
    expect(c.paths[0]).toMatchObject({ fromPoint: 'P2', toPoint: 'P1' })
  })

  it('undoes and redoes', () => {
    const history = new History()
    let c = emptyContent()
    history.push(c)
    c = addPoint(c, 1, 1)
    history.push(c)
    c = addPoint(c, 2, 2)

    c = history.undo(c)!
    expect(c.points).toHaveLength(1)
    c = history.undo(c)!
    expect(c.points).toHaveLength(0)
    expect(history.undo(c)).toBeNull()
    c = history.redo(c)!
    expect(c.points).toHaveLength(1)
    history.push(c)
    expect(history.canRedo).toBe(false)
  })
})
