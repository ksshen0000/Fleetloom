import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, toApiError } from '@/api/http'

function axiosError(status: number | null, data?: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const response = status === null ? undefined
    : { status, statusText: '', headers: {}, config, data }
  return new AxiosError('failed', 'ERR', config, null, response as never)
}

describe('toApiError', () => {
  it('keeps the backend error code and message', () => {
    const e = toApiError(axiosError(409, { code: 'MAP_REVISION_CONFLICT', message: 'changed by someone else', data: null }))
    expect(e).toBeInstanceOf(ApiError)
    expect(e.code).toBe('MAP_REVISION_CONFLICT')
    expect(e.message).toBe('changed by someone else')
    expect(e.status).toBe(409)
  })

  it('keeps structured details such as a validation report', () => {
    const report = { issues: [{ severity: 'ERROR', rule: 'EMPTY_MAP' }] }
    expect(toApiError(axiosError(422, { code: 'MAP_VALIDATION_FAILED', message: 'x', data: report })).details).toEqual(report)
  })

  it('reports unreachable services', () => {
    expect(toApiError(axiosError(null)).code).toBe('UNAVAILABLE')
  })

  it('falls back to the HTTP status', () => {
    expect(toApiError(axiosError(502, '<html>bad gateway</html>')).code).toBe('HTTP_502')
  })
})

describe('auth hooks', () => {
  it('sends the token and reports a rejected one, but not a failed login', async () => {
    const { auth, http } = await import('@/api/http')
    const onUnauthorized = vi.fn()
    let token: string | null = 'tok'
    auth.token = () => token
    auth.onUnauthorized = onUnauthorized
    const seen: (string | undefined)[] = []
    http.defaults.adapter = async (config) => {
      seen.push(config.headers.get('Authorization') as string | undefined)
      const response = { status: 401, statusText: '', headers: {}, config, data: { code: 'UNAUTHORIZED', message: 'x', data: null } }
      throw new AxiosError('401', 'ERR_BAD_REQUEST', config, null, response as never)
    }

    await expect(http.get('/api/x')).rejects.toBeInstanceOf(AxiosError)
    expect(seen[0]).toBe('Bearer tok')
    expect(onUnauthorized).toHaveBeenCalledTimes(1)

    token = null
    await expect(http.post('/api/resource/v1/auth/login')).rejects.toBeInstanceOf(AxiosError)
    expect(seen[1]).toBeUndefined()
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
