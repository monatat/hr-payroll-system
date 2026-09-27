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

  it('redirects an unauthenticated visitor away from /employee/payslips to login', () => {
    renderApp('/employee/payslips')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated HR_ADMIN from /employee/payslips', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/employee/payslips')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My Payslips' })).not.toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into the real /employee/payslips page (not the old placeholder)', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/payslips')

    expect(screen.getByRole('heading', { name: 'My Payslips' })).toBeInTheDocument()
    expect(screen.queryByText('View and download payslips.')).not.toBeInTheDocument()
    expect(screen.queryByText(/placeholder screen/i)).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /employee/attendance to login', () => {
    renderApp('/employee/attendance')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated HR_ADMIN from /employee/attendance', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/employee/attendance')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My Attendance' })).not.toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into the real /employee/attendance page (not the old placeholder)', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/attendance')

    expect(screen.getByRole('heading', { name: 'My Attendance' })).toBeInTheDocument()
    expect(screen.queryByText('Clock in/out and view history.')).not.toBeInTheDocument()
    expect(screen.queryByText(/placeholder screen/i)).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /employee/payslips/:id to login', () => {
    renderApp('/employee/payslips/1')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated HR_ADMIN from /employee/payslips/:id', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/employee/payslips/1')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
  })

  it('allows an authenticated EMPLOYEE into the real /employee/payslips/:id detail route', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/payslips/1')

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
    expect(screen.queryByText('404 — Page not found')).not.toBeInTheDocument()
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

  it('redirects an unauthenticated visitor away from /admin/payroll to login', () => {
    renderApp('/admin/payroll')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/payroll', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/payroll')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Payroll Runs' })).not.toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into /admin/payroll', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/payroll')

    expect(screen.getByRole('heading', { name: 'Payroll Runs' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin/reports to login', () => {
    renderApp('/admin/reports')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/reports', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/reports')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Reports' })).not.toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into the real /admin/reports page (not the old placeholder)', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/reports')

    expect(screen.getByRole('heading', { name: 'Reports' })).toBeInTheDocument()
    expect(screen.queryByText('Payroll cost and headcount reports.')).not.toBeInTheDocument()
    expect(screen.queryByText(/placeholder screen/i)).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin to login', () => {
    renderApp('/admin')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Admin Dashboard' })).not.toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from the /admin dashboard', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Admin Dashboard' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Active Employees' })).not.toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into the real /admin dashboard (not the old static stub)', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin')

    expect(screen.getByRole('heading', { level: 1, name: 'Admin Dashboard' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Active Employees' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Pending Leave Requests' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Latest Payroll Run' })).toBeInTheDocument()
    expect(screen.queryByText('HR Admin overview.')).not.toBeInTheDocument()
    expect(screen.queryByText(/placeholder screen/i)).not.toBeInTheDocument()
  })

  it('introduces no Employee-facing reports route', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/employee/reports')

    expect(screen.getByText('404 — Page not found')).toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor away from /admin/payroll/:id to login', () => {
    renderApp('/admin/payroll/1')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('blocks an authenticated EMPLOYEE from /admin/payroll/:id', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))

    renderApp('/admin/payroll/1')

    expect(screen.getByRole('heading', { name: 'Not authorized' })).toBeInTheDocument()
  })

  it('allows an authenticated HR_ADMIN into /admin/payroll/:id', () => {
    sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))

    renderApp('/admin/payroll/1')

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
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

      // Scoped to the sidebar: the Admin Dashboard also has a "Leave Requests" quick action.
      const sidebar = screen.getByRole('navigation', { name: 'Sidebar navigation' })
      await user.click(within(sidebar).getByRole('link', { name: 'Leave Requests' }))

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
