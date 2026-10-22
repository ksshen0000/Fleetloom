import { describe, expect, it } from 'vitest'
import { planVehicles } from '@/simulator/batch'

describe('planVehicles', () => {
  it('numbers vehicles after the existing ones and gives each a free point', () => {
    const planned = planVehicles('SIM', 3, ['SIM-001', 'SIM-003'], ['P00', 'P01', 'P02', 'P10'], ['P01'], 80)

    expect(planned).toEqual([
      { serialNumber: 'SIM-002', initialPoint: 'P00', battery: 80 },
      { serialNumber: 'SIM-004', initialPoint: 'P02', battery: 80 },
      { serialNumber: 'SIM-005', initialPoint: 'P10', battery: 80 },
    ])
  })

  it('leaves vehicles unplaced when the points run out', () => {
    const planned = planVehicles('X', 2, [], ['P00'], [])

    expect(planned.map((v) => v.initialPoint)).toEqual(['P00', undefined])
  })
})
