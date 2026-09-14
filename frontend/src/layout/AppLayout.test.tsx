import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { AppLayout } from './AppLayout'

vi.mock('../api/client', () => ({
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

function renderLayout(initialEntry: string) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/login" element={<h1>Sign in</h1>} />
            <Route path="/admin" element={<h1>Admin Dashboard</h1>} />
            <Route path="/admin/departments" element={<h1>Departments</h1>} />
            <Route path="/admin/employees" element={<h1>Employees</h1>} />
            <Route path="/admin/employees/:id" element={<h1>Employee Detail</h1>} />
            <Route path="/admin/leave" element={<h1>Leave Requests</h1>} />
            <Route path="/admin/leave/:id" element={<h1>Leave Detail</h1>} />
            <Route path="/employee" element={<h1>Employee Dashboard</h1>} />
            <Route path="/employee/profile" element={<h1>My Profile</h1>} />
            <Route path="/employee/salary" element={<h1>Salary History</h1>} />
            <Route path="/employee/leave" element={<h1>My Leave</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('AppLayout', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
  })

  describe('logged out', () => {
    it('shows no sidebar, no menu toggle, and no logout', () => {
      renderLayout('/login')

      expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
      expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /menu/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /logout/i })).not.toBeInTheDocument()
      expect(screen.queryByText('Employee')).not.toBeInTheDocument()
      expect(screen.queryByText('HR Admin')).not.toBeInTheDocument()
    })

    it('shows Login and API status', () => {
      renderLayout('/login')

      expect(screen.getByText('HappyMon')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Login' })).toBeInTheDocument()
      expect(screen.getByTestId('api-status')).toBeInTheDocument()
    })
  })

  describe('EMPLOYEE', () => {
    beforeEach(() => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))
    })

    it('shows exactly the four employee sidebar items and no HR admin items', () => {
      renderLayout('/employee')

      const nav = screen.getByRole('navigation')
      const links = within(nav).getAllByRole('link')
      expect(links.map((link) => link.textContent)).toEqual(['Dashboard', 'My Profile', 'Salary History', 'My Leave'])
      expect(within(nav).queryByRole('link', { name: 'Manage Departments' })).not.toBeInTheDocument()
      expect(within(nav).queryByRole('link', { name: 'Manage Employees' })).not.toBeInTheDocument()
      expect(within(nav).queryByRole('link', { name: 'Leave Requests' })).not.toBeInTheDocument()
    })

    it('shows Employee as a non-link role label and Logout in the header', () => {
      renderLayout('/employee')

      const header = screen.getByRole('banner')
      expect(within(header).getByText('Employee')).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Employee' })).not.toBeInTheDocument()
      expect(within(header).getByRole('button', { name: 'Logout' })).toBeInTheDocument()
    })

    it('no longer shows the old horizontal My Leave/Employee feature links in the header', () => {
      renderLayout('/employee')

      const header = screen.getByRole('banner')
      expect(within(header).queryByRole('link', { name: 'My Leave' })).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'Employee' })).not.toBeInTheDocument()
    })

    it('navigates to /employee/leave via the sidebar My Leave link', async () => {
      const user = userEvent.setup()
      renderLayout('/employee')

      await user.click(screen.getByRole('link', { name: 'My Leave' }))

      expect(await screen.findByRole('heading', { name: 'My Leave' })).toBeInTheDocument()
    })
  })

  describe('HR_ADMIN', () => {
    beforeEach(() => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))
    })

    it('shows exactly the four admin sidebar items and no employee items', () => {
      renderLayout('/admin')

      const nav = screen.getByRole('navigation')
      const links = within(nav).getAllByRole('link')
      expect(links.map((link) => link.textContent)).toEqual([
        'Dashboard',
        'Manage Departments',
        'Manage Employees',
        'Leave Requests',
      ])
      expect(within(nav).queryByRole('link', { name: 'My Profile' })).not.toBeInTheDocument()
      expect(within(nav).queryByRole('link', { name: 'Salary History' })).not.toBeInTheDocument()
      expect(within(nav).queryByRole('link', { name: 'My Leave' })).not.toBeInTheDocument()
    })

    it('shows HR Admin as a non-link role label and Logout in the header', () => {
      renderLayout('/admin')

      const header = screen.getByRole('banner')
      expect(within(header).getByText('HR Admin')).toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'HR Admin' })).not.toBeInTheDocument()
      expect(within(header).getByRole('button', { name: 'Logout' })).toBeInTheDocument()
    })

    it('no longer shows the old horizontal HR Admin/Leave Requests feature links in the header', () => {
      renderLayout('/admin')

      const header = screen.getByRole('banner')
      expect(within(header).queryByRole('link', { name: 'Leave Requests' })).not.toBeInTheDocument()
      expect(within(header).queryByRole('link', { name: 'HR Admin' })).not.toBeInTheDocument()
    })

    it('navigates to /admin/leave via the sidebar Leave Requests link', async () => {
      const user = userEvent.setup()
      renderLayout('/admin')

      await user.click(screen.getByRole('link', { name: 'Leave Requests' }))

      expect(await screen.findByRole('heading', { name: 'Leave Requests' })).toBeInTheDocument()
    })
  })

  describe('navigation active states', () => {
    beforeEach(() => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('HR_ADMIN'))
    })

    it('every sidebar item has the exact approved href', () => {
      renderLayout('/admin')

      expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/admin')
      expect(screen.getByRole('link', { name: 'Manage Departments' })).toHaveAttribute('href', '/admin/departments')
      expect(screen.getByRole('link', { name: 'Manage Employees' })).toHaveAttribute('href', '/admin/employees')
      expect(screen.getByRole('link', { name: 'Leave Requests' })).toHaveAttribute('href', '/admin/leave')
    })

    it('marks only Dashboard active on the exact /admin route', () => {
      renderLayout('/admin')

      expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')
      expect(screen.getByRole('link', { name: 'Manage Departments' })).not.toHaveAttribute('aria-current')
    })

    it('does not keep Dashboard active on a nested admin route', () => {
      renderLayout('/admin/employees')

      expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
      expect(screen.getByRole('link', { name: 'Manage Employees' })).toHaveAttribute('aria-current', 'page')
    })

    it('keeps Manage Employees active on an employee detail route', () => {
      renderLayout('/admin/employees/42')

      expect(screen.getByRole('link', { name: 'Manage Employees' })).toHaveAttribute('aria-current', 'page')
    })

    it('keeps Leave Requests active on a leave detail route', () => {
      renderLayout('/admin/leave/7')

      expect(screen.getByRole('link', { name: 'Leave Requests' })).toHaveAttribute('aria-current', 'page')
    })
  })

  describe('mobile menu toggle', () => {
    beforeEach(() => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))
    })

    it('has an accessible Menu button with aria-expanded false initially and aria-controls the sidebar', () => {
      renderLayout('/employee')

      const menuButton = screen.getByRole('button', { name: /menu/i })
      expect(menuButton).toHaveAttribute('aria-expanded', 'false')
      const controlsId = menuButton.getAttribute('aria-controls')
      expect(controlsId).toBeTruthy()
      expect(document.getElementById(controlsId!)).toBe(screen.getByRole('navigation'))
    })

    it('opens the sidebar and sets aria-expanded true when Menu is clicked', async () => {
      const user = userEvent.setup()
      renderLayout('/employee')

      const menuButton = screen.getByRole('button', { name: /menu/i })
      const nav = screen.getByRole('navigation')
      expect(nav).toHaveClass('hidden')

      await user.click(menuButton)

      expect(menuButton).toHaveAttribute('aria-expanded', 'true')
      expect(nav).not.toHaveClass('hidden')
    })

    it('closes the sidebar and resets aria-expanded when a sidebar link is clicked', async () => {
      const user = userEvent.setup()
      renderLayout('/employee')

      const menuButton = screen.getByRole('button', { name: /menu/i })
      await user.click(menuButton)
      expect(menuButton).toHaveAttribute('aria-expanded', 'true')

      await user.click(screen.getByRole('link', { name: 'My Leave' }))

      expect(menuButton).toHaveAttribute('aria-expanded', 'false')
    })
  })

  describe('logout', () => {
    it('clears auth, returns to login, and removes the sidebar and menu toggle', async () => {
      sessionStorage.setItem('hr_payroll_access_token', fakeToken('EMPLOYEE', 7))
      const user = userEvent.setup()
      renderLayout('/employee')
      expect(await screen.findByRole('heading', { name: 'Employee Dashboard' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Logout' }))

      expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
      expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /menu/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Logout' })).not.toBeInTheDocument()
    })
  })
})
