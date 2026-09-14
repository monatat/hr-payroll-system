import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth, type Role } from './AuthContext'

type ProtectedRouteProps = {
  children: ReactNode
  allowedRoles?: Role[]
}

/**
 * UX-only route guard. The backend independently enforces role-based
 * authorization on every request -- this component only decides what the
 * frontend renders; it is never the actual security boundary.
 */
export function ProtectedRoute({ children, allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, role } = useAuth()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  if (allowedRoles && (!role || !allowedRoles.includes(role))) {
    return (
      <section className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
        <h1 className="text-xl font-semibold text-red-800">Not authorized</h1>
        <p className="mt-2 text-sm text-red-600">You do not have permission to view this page.</p>
      </section>
    )
  }

  return <>{children}</>
}
