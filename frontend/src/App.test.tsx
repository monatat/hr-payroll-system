import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { AuthProvider } from './auth/AuthContext'

vi.mock('./api/client', () => ({
  apiClient: {
    get: vi.fn().mockRejectedValue(new Error('no backend in unit tests')),
  },
  UNAUTHORIZED_EVENT: 'auth:unauthorized',
}))

function fakeToken(role: string, employeeId?: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ sub: '1', role, employeeId, iat: 0, exp: 9999999999 }))
  return `${header}.${payload}.fake-signature`
}

function renderApp(initialEntry: string) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[initialEntry]}>
        <App />
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('App', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
  })

  it('renders the application shell', () => {
    renderApp('/login')

    expect(screen.getByText('HappyMon')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from an admin route to login', () => {
    renderApp('/admin/employees')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into an admin route', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/employees')

    expect(screen.getByRole('heading', { name: 'Employees' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from an admin route', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/employees')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Employees' })).not.toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into their own dashboard', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee')

    expect(screen.getByRole('heading', { name: 'Employee Dashboard' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin/departments to login', () => {
    renderApp('/admin/departments')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into /admin/departments', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/departments')

    expect(screen.getByRole('heading', { name: 'Departments' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/departments', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/departments')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into their own profile route', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/profile')

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /employee/leave to login', () => {
    renderApp('/employee/leave')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated HR_ADMIN from /employee/leave', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/employee/leave')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My Leave' })).not.toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into /employee/leave', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/leave')

    expect(screen.getByRole('heading', { name: 'My Leave' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin/leave to login', () => {
    renderApp('/admin/leave')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/leave', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/leave')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Leave Requests' })).not.toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into /admin/leave', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/leave')

    expect(screen.getByRole('heading', { name: 'Leave Requests' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin/leave/:id to login', () => {
    renderApp('/admin/leave/1')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/leave/:id', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/leave/1')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into /admin/leave/:id', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/leave/1')

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('falls back to the 404 route for unknown paths', () => {
    renderApp('/does-not-exist')

    expect(screen.getByText('404 — Page not found')).toBeInTheDocument()
  })

  describe('Header navigation and branding', () => {
    it('shows only the brand and Login when logged out, with no role label, sidebar, or logout', () => {
      renderApp('/login')

      expect(screen.getByText('HappyMon')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Login' })).toBeInTheDocument()
      expect(screen.queryByText('Employee')).not.toBeInTheDocument()
      expect(screen.queryByText('HR Admin')).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'My Leave' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Leave Requests' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Logout' })).not.toBeInTheDocument()
      expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    })

    it('shows the Employee role label (not a link) and Logout in the header for an authenticated EMPLOYEE', () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

      renderApp('/employee')

      const header = screen.getByRole('banner')
      expect(within(header).getByText('Employee')).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Employee' })).not.toBeInTheDocument()
      expect(within(header).getByRole('button', { name: 'Logout' })).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Login' })).not.toBeInTheDocument()
      expect(within(header).queryByText('HR Admin')).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'My Leave' })).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Leave Requests' })).not.toBeInTheDocument()
    })

    it('shows the HR Admin role label (not a link) and Logout in the header for an authenticated HR_ADMIN', () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

      renderApp('/admin')

      const header = screen.getByRole('banner')
      expect(within(header).getByText('HR Admin')).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'HR Admin' })).not.toBeInTheDocument()
      expect(within(header).getByRole('button', { name: 'Logout' })).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Login' })).not.toBeInTheDocument()
      expect(within(header).queryByText('Employee')).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'My Leave' })).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Leave Requests' })).not.toBeInTheDocument()
    })

    it('opens the leave dashboard from the sidebar My Leave link', async () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))
      const user = userEvent.setup()
      renderApp('/employee')

      await user.click(screen.getByRole('link', { name: 'My Leave' }))

      expect(await screen.findByRole('heading', { name: 'My Leave' })).toBeInTheDocument()
    })

    it('opens the admin leave list from the sidebar Leave Requests link', async () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))
      const user = userEvent.setup()
      renderApp('/admin')

      await user.click(screen.getByRole('link', { name: 'Leave Requests' }))

      expect(await screen.findByRole('heading', { name: 'Leave Requests' })).toBeInTheDocument()
    })

    it('logs out, clears the session, and returns to login with protected content gone', async () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))
      const user = userEvent.setup()
      renderApp('/employee')
      expect(await screen.findByRole('heading', { name: 'Employee Dashboard' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Logout' }))

      expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Employee Dashboard' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Logout' })).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Login' })).toBeInTheDocument()
      expect(sessionStorage.getItem('hr_payroll_access_token')).toBeNull()
    })
  })

  it('applies Tailwind utility classes to rendered elements', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/employees')

    const heading = screen.getByRole('heading', { name: 'Employees' })
    expect(heading).toHaveClass('text-xl', 'font-semibold', 'text-slate-900')
  })
})
