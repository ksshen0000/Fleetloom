import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { addPoint, connect, emptyContent, exportMapFile, parseMapFile } from '@/map/editorModel'

describe('map files', () => {
  it('round-trips through export and import', () => {
    const content = connect(addPoint(addPoint(emptyContent(), 0, 0), 4, 0, 'CHARGING'), 'P1', 'P2')

    const parsed = parseMapFile(exportMapFile('DEMO', 3, content))

    expect(parsed).toEqual(content)
  })

  it('accepts bare content like the demo map', () => {
    const parsed = parseMapFile(readFileSync('../deploy/examples/demo-map.json', 'utf8'))

    expect(parsed.points.length).toBeGreaterThan(9)
    expect(parsed.paths.length).toBeGreaterThan(0)
  })

  it('explains what is wrong with a bad file', () => {
    expect(() => parseMapFile('not json')).toThrow('JSON')
    expect(() => parseMapFile('{"paths": []}')).toThrow('points')
    expect(() => parseMapFile('{"points": [{"code": "A", "x": 0, "y": 0}, {"code": "A", "x": 1, "y": 0}]}')).toThrow('重复')
    expect(() => parseMapFile('{"points": [{"code": "A", "x": 0, "y": 0}], "paths": [{"code": "A-B", "fromPoint": "A", "toPoint": "B"}]}'))
      .toThrow('A-B')
  })
})
