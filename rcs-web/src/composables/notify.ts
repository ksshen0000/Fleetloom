import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'

/** Runs an action, reports success or the backend's error message. Returns the result or null. */
export async function act<T>(action: () => Promise<T>, success?: string): Promise<T | null> {
  try {
    const result = await action()
    if (success) {
      ElMessage.success(success)
    }
    return result
  } catch (e) {
    const message = e instanceof ApiError && e.code === 'FORBIDDEN' ? '当前账号没有执行此操作的权限'
      : e instanceof ApiError ? `${e.message}（${e.code}）` : String(e)
    ElMessage.error(message)
    return null
  }
}

export async function confirm(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '确认', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
    return true
  } catch {
    return false
  }
}

export function formatTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : ''
}
