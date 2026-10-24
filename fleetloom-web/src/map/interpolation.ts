/**
 * Smooth vehicle display between status samples that arrive about once per second: the vehicle is
 * drawn moving from the previous sample to the latest one over one sample interval, so the picture
 * lags reality by up to one interval but never jumps.
 */
export interface Pose {
  x: number
  y: number
  theta: number
}

interface Track {
  from: Pose
  to: Pose
  startedAt: number
  duration: number
}

export class PoseInterpolator {
  private tracks = new Map<string, Track>()

  constructor(private maxDurationMs = 1500) {}

  /** Records a new sample for {@code id} received at {@code now} (ms). */
  update(id: string, pose: Pose, now: number): void {
    const track = this.tracks.get(id)
    if (!track) {
      this.tracks.set(id, { from: pose, to: pose, startedAt: now, duration: 1 })
      return
    }
    const current = this.at(id, now) ?? pose
    const sinceLast = now - track.startedAt
    this.tracks.set(id, { from: current, to: pose, startedAt: now, duration: Math.min(Math.max(sinceLast, 1), this.maxDurationMs) })
  }

  /** Pose to draw at {@code now}; null for unknown ids. */
  at(id: string, now: number): Pose | null {
    const track = this.tracks.get(id)
    if (!track) {
      return null
    }
    const t = Math.min(1, Math.max(0, (now - track.startedAt) / track.duration))
    return {
      x: track.from.x + (track.to.x - track.from.x) * t,
      y: track.from.y + (track.to.y - track.from.y) * t,
      theta: track.from.theta + angleDiff(track.from.theta, track.to.theta) * t,
    }
  }

  forget(id: string): void {
    this.tracks.delete(id)
  }
}

/** Signed smallest rotation from a to b in radians. */
export function angleDiff(a: number, b: number): number {
  let d = (b - a) % (2 * Math.PI)
  if (d > Math.PI) d -= 2 * Math.PI
  if (d < -Math.PI) d += 2 * Math.PI
  return d
}
