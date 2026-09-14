import { NavLink } from 'react-router-dom'
import type { Role } from '../auth/AuthContext'

type SidebarItem = {
  label: string
  to: string
  end?: boolean
}

// Only routes with a real, finished page belong here -- /admin/payroll,
// /admin/reports, /employee/attendance, and /employee/payslips are still
// unfinished placeholders (out of Phase 7D-3 scope). Entity-specific routes
// (employee detail/edit/salary, leave-request detail) stay reachable only
// through their parent list pages, not as their own sidebar entries.
const HR_ADMIN_ITEMS: SidebarItem[] = [
  { label: 'Dashboard', to: '/admin', end: true },
  { label: 'Manage Departments', to: '/admin/departments' },
  { label: 'Manage Employees', to: '/admin/employees' },
  { label: 'Leave Requests', to: '/admin/leave' },
]

const EMPLOYEE_ITEMS: SidebarItem[] = [
  { label: 'Dashboard', to: '/employee', end: true },
  { label: 'My Profile', to: '/employee/profile' },
  { label: 'Salary History', to: '/employee/salary' },
  { label: 'My Leave', to: '/employee/leave' },
]

type SidebarProps = {
  role: Role
  open: boolean
  onNavigate: () => void
}

// One navigation structure serves both desktop and mobile: `hidden md:flex`
// keeps it off-screen below the md breakpoint until `open` swaps in `flex`,
// so there is never a second, independently maintained link list.
export function Sidebar({ role, open, onNavigate }: SidebarProps) {
  const items = role === 'HR_ADMIN' ? HR_ADMIN_ITEMS : EMPLOYEE_ITEMS
  const visibilityClass = open ? 'flex' : 'hidden'

  return (
    <nav
      id="app-sidebar"
      aria-label="Sidebar navigation"
      className={`${visibilityClass} w-full shrink-0 flex-col gap-1 border-b border-slate-200 bg-white px-4 py-4 md:flex md:w-56 md:border-b-0 md:border-r md:py-8`}
    >
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          onClick={onNavigate}
          className={({ isActive }) =>
            `rounded-md px-3 py-2 text-sm font-medium ${
              isActive ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`
          }
        >
          {item.label}
        </NavLink>
      ))}
    </nav>
  )
}
