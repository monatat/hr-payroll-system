import { beforeEach, describe, expect, it } from 'vitest'
import { apiClient } from './client'

// Axios interceptors aren't otherwise directly invokable without a live
// server/mock adapter; exercising the registered handler function directly
// keeps this test fast and dependency-free.
function runRequestInterceptor(config: { headers: Record<string, string> }) {
  const handlers = (apiClient.interceptors.request as unknown as { handlers: Array<{ fulfilled: (c: any) => any }> })
    .handlers
  return handlers[0].fulfilled(config)
}

describe('apiClient request interceptor', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('attaches the Bearer token when one is present in sessionStorage', () => {
    sessionStorage.setItem('hr_payroll_access_token', 'abc123')

    const config = runRequestInterceptor({ headers: {} })

    expect(config.headers['Authorization']).toBe('Bearer abc123')
  })

  it('omits the Authorization header when no token is present', () => {
    const config = runRequestInterceptor({ headers: {} })

    expect(config.headers['Authorization']).toBeUndefined()
  })
})
