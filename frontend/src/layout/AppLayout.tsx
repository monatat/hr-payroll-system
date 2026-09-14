import { useEffect, useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { apiClient } from '../api/client'
import { useAuth, type Role } from '../auth/AuthContext'
import { Sidebar } from './Sidebar'

type ApiStatus = 'checking' | 'up' | 'down'

const ROLE_LABELS: Record<Role, string> = {
  HR_ADMIN: 'HR Admin',
  EMPLOYEE: 'Employee',
}

export function AppLayout() {
  const [apiStatus, setApiStatus] = useState<ApiStatus>('checking')
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const { isAuthenticated, role, logout } = useAuth()
  const navigate = useNavigate()

  function handleLogout() {
    logout()
    setMobileNavOpen(false)
    navigate('/login', { replace: true })
  }

  function closeMobileNav() {
    setMobileNavOpen(false)
  }

  useEffect(() => {
    let cancelled = false

    apiClient
      .get('/api/health')
      .then(() => {
        if (!cancelled) setApiStatus('up')
      })
      .catch(() => {
        if (!cancelled) setApiStatus('down')
      })

    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-3">
            {isAuthenticated && (
              <button
                type="button"
                aria-label="Menu"
                aria-expanded={mobileNavOpen}
                aria-controls="app-sidebar"
                onClick={() => setMobileNavOpen((open) => !open)}
                className="rounded-md border border-slate-300 px-2 py-1 text-sm font-medium text-slate-600 md:hidden"
              >
                Menu
              </button>
            )}
            <span className="text-lg font-semibold">HappyMon</span>
          </div>
          <div className="flex items-center gap-4 text-sm font-medium text-slate-600">
            {!isAuthenticated && (
              <Link to="/login" className="hover:text-slate-900">
                Login
              </Link>
            )}
            {isAuthenticated && role && (
              <span className="rounded-full bg-slate-200 px-3 py-1 text-xs font-medium text-slate-700">
                {ROLE_LABELS[role]}
              </span>
            )}
            {isAuthenticated && (
              <button type="button" onClick={handleLogout} className="hover:text-slate-900">
                Logout
              </button>
            )}
          </div>
          <ApiStatusBadge status={apiStatus} />
        </div>
      </header>
      <div className="flex flex-1 flex-col md:flex-row">
        {isAuthenticated && role && <Sidebar role={role} open={mobileNavOpen} onNavigate={closeMobileNav} />}
        <main className="flex-1 px-6 py-8">
          <div className="mx-auto max-w-5xl">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

function ApiStatusBadge({ status }: { status: ApiStatus }) {
  const styles: Record<ApiStatus, string> = {
    checking: 'bg-slate-200 text-slate-600',
    up: 'bg-emerald-100 text-emerald-700',
    down: 'bg-red-100 text-red-700',
  }
  const labels: Record<ApiStatus, string> = {
    checking: 'API: checking...',
    up: 'API: UP',
    down: 'API: unreachable',
  }

  return (
    <span
      data-testid="api-status"
      className={`rounded-full px-3 py-1 text-xs font-medium ${styles[status]}`}
    >
      {labels[status]}
    </span>
  )
}
