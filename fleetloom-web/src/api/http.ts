import axios, { AxiosError, type AxiosResponse } from 'axios'
import type { ApiResponse } from './types'

/** A failed backend call, carrying the backend's error code (e.g. MAP_REVISION_CONFLICT). */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number | null,
    readonly details: unknown = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const http = axios.create({ timeout: 15_000 })

/** Hooks the session installs: where the token comes from and what to do when it is rejected. */
export const auth = {
  token: (): string | null => null,
  onUnauthorized: (): void => {},
}

http.interceptors.request.use((config) => {
  const token = auth.token()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(undefined, (error) => {
  // a rejected token means the session is over; a failed login (no token sent) is just an error
  if (error instanceof AxiosError && error.response?.status === 401 && error.config?.headers?.has('Authorization')) {
    auth.onUnauthorized()
  }
  return Promise.reject(error)
})

/** Unwraps the ApiResponse envelope; turns every failure into an {@link ApiError}. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof AxiosError) {
    const body = error.response?.data as Partial<ApiResponse<unknown>> | undefined
    if (body && typeof body.code === 'string') {
      return new ApiError(body.code, body.message ?? body.code, error.response?.status ?? null, body.data ?? null)
    }
    if (!error.response) {
      return new ApiError('UNAVAILABLE', '服务不可达', null)
    }
    return new ApiError(`HTTP_${error.response.status}`, error.message, error.response.status)
  }
  return new ApiError('UNKNOWN', String(error), null)
}

export async function call<T>(request: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
  try {
    const response = await request
    return response.data.data
  } catch (e) {
    throw toApiError(e)
  }
}
