export interface NewSimVehicle {
  serialNumber: string
  initialPoint?: string
  battery?: number
}

/**
 * Serial numbers PREFIX-NNN counting up from the first free number, each starting on its own point
 * (points already taken are skipped; with more vehicles than free points they start unplaced).
 */
export function planVehicles(
  prefix: string,
  count: number,
  existing: string[],
  points: string[],
  takenPoints: string[],
  battery?: number,
): NewSimVehicle[] {
  const used = new Set(existing)
  const free = points.filter((p) => !takenPoints.includes(p))
  const planned: NewSimVehicle[] = []
  for (let n = 1; planned.length < count; n++) {
    const serialNumber = `${prefix}-${String(n).padStart(3, '0')}`
    if (used.has(serialNumber)) continue
    planned.push({ serialNumber, initialPoint: free[planned.length], battery })
  }
  return planned
}
