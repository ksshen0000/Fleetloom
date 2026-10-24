import type { LocationStatus, OrderStatus, PointType } from '@/api/types'

export const POINT_COLORS: Record<PointType, string> = {
  NORMAL: '#606266',
  WORKSTATION: '#e6a23c',
  CHARGING: '#67c23a',
  PARKING: '#409eff',
  ROTATION: '#909399',
  WAITING: '#b37feb',
}

export const LOCATION_COLORS: Record<LocationStatus, string> = {
  EMPTY: '#dcdfe6',
  OCCUPIED: '#409eff',
  LOCKED_IN: '#e6a23c',
  LOCKED_OUT: '#f56c6c',
  DISABLED: '#909399',
}

export const LOCATION_LABELS: Record<LocationStatus, string> = {
  EMPTY: '空闲',
  OCCUPIED: '占用',
  LOCKED_IN: '入库锁定',
  LOCKED_OUT: '出库锁定',
  DISABLED: '禁用',
}

export const ORDER_LABELS: Record<OrderStatus, string> = {
  QUEUED: '排队中',
  EXECUTING: '执行中',
  SUSPENDED: '已挂起',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  REJECTED: '已拒绝',
}

export const ORDER_TAG: Record<OrderStatus, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  QUEUED: 'info',
  EXECUTING: 'primary',
  SUSPENDED: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELLED: 'info',
  REJECTED: 'danger',
}

/** Distinct, readable colours for vehicles, assigned by sorted vehicle code. */
const VEHICLE_PALETTE = ['#409eff', '#e6a23c', '#67c23a', '#b37feb', '#f56c6c', '#13c2c2', '#fa8c16', '#2f54eb']

export function vehicleColor(code: string, allCodes: string[]): string {
  const index = [...allCodes].sort().indexOf(code)
  return VEHICLE_PALETTE[(index < 0 ? 0 : index) % VEHICLE_PALETTE.length]
}

export const ALARM_LEVEL_LABELS = { WARNING: '警告', ERROR: '错误', FATAL: '严重' } as const
export const ALARM_LEVEL_TAG = { WARNING: 'warning', ERROR: 'danger', FATAL: 'danger' } as const
export const ALARM_STATUS_LABELS = { ACTIVE: '未确认', ACKNOWLEDGED: '已确认', CLEARED: '已关闭' } as const
export const ALARM_CATEGORY_LABELS = {
  VEHICLE_ERROR: '车辆故障',
  VEHICLE_OFFLINE: '车辆离线',
  TRAFFIC_BLOCKED: '交通阻塞',
  DEADLOCK: '死锁',
  ORDER_SUSPENDED: '订单挂起',
  ORDER_FAILED: '订单失败',
  ORDER_OVERDUE: '订单超时',
} as const
