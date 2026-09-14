import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../api/client'
import { AuthProvider, useAuth } from './AuthContext'

vi.mock('../api/client', () => ({
  apiClient: { post: vi.fn() },
  UNAUTHORIZED_EVENT: 'auth:unauthorized',
}))

function fakeToken(role: string): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ sub: '1', role, iat: 0, exp: 9999999999 }))
  return `${header}.${payload}.fake-signature`
}

function Harness() {
  const { isAuthenticated, role, login, logout } = useAuth()
  return (
    <div>
      <span data-testid="status">{isAuthenticated ? `authenticated:${role}` : 'anonymous'}</span>
      <button
        onClick={() => {
          login('user@test.local', 'password').catch(() => {
            // surfaced via component state in the real LoginPage; ignored here
          })
        }}
      >
        login
      </button>
      <button onClick={logout}>logout</button>
    </div>
  )
}

describe('AuthContext', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.mocked(apiClient.post).mockReset()
  })

  it('starts anonymous when no token is stored', () => {
    render(
      <AuthProvider>
        <Harness />
      </AuthProvider>,
    )

    expect(screen.getByTestId('status')).toHaveTextContent('anonymous')
  })

  it('hydrates authenticated state from a token already in sessionStorage', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    render(
      <AuthProvider>
        <Harness />
      </AuthProvider>,
    )

    expect(screen.getByTestId('status')).toHaveTextContent('authenticated:HR_ADMIN')
  })

  it('a successful login updates auth state and persists the token to sessionStorage', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { accessToken: fakeToken('EMPLOYEE') } })
    const user = userEvent.setup()

    render(
      <AuthProvider>
        <Harness />
      </AuthProvider>,
    )
    await user.click(screen.getByText('login'))

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated:EMPLOYEE'))
    expect(sessionStorage.getItem('hr_payroll_access_token')).not.toBeNull()
  })

  it('a failed login leaves auth state anonymous and stores no token', async () => {
    vi.mocked(apiClient.post).mockRejectedValue(new Error('Request failed with status code 401'))
    const user = userEvent.setup()

    render(
      <AuthProvider>
        <Harness />
      </AuthProvider>,
    )
    await user.click(screen.getByText('login'))

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'))
    expect(sessionStorage.getItem('hr_payroll_access_token')).toBeNull()
  })

  it('logout clears auth state and sessionStorage', async () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))
    const user = userEvent.setup()

    render(
      <AuthProvider>
        <Harness />
      </AuthProvider>,
    )
    await user.click(screen.getByText('logout'))

    expect(screen.getByTestId('status')).toHaveTextContent('anonymous')
    expect(sessionStorage.getItem('hr_payroll_access_token')).toBeNull()
  })
})
