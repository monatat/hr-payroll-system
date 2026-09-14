import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { apiClient, UNAUTHORIZED_EVENT } from '../api/client'
import { clearToken, getToken, setToken } from './tokenStore'

export type Role = 'HR_ADMIN' | 'EMPLOYEE'

type AuthState = {
  token: string | null
  role: Role | null
  employeeId: number | null
}

type AuthContextValue = AuthState & {
  isAuthenticated: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

type JwtClaims = {
  role?: string
  employeeId?: number
}

// Decoded locally (no jwt-decode dependency needed for one small helper).
// This is UX convenience only -- the backend independently re-validates
// and enforces the token on every request.
function decodeClaims(token: string): JwtClaims | null {
  try {
    const payload = token.split('.')[1]
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    )
    return JSON.parse(json) as JwtClaims
  } catch {
    return null
  }
}

function buildState(token: string | null): AuthState {
  if (!token) {
    return { token: null, role: null, employeeId: null }
  }
  const claims = decodeClaims(token)
  const role = claims?.role === 'HR_ADMIN' || claims?.role === 'EMPLOYEE' ? claims.role : null
  return {
    token,
    role,
    employeeId: claims?.employeeId ?? null,
  }
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() => buildState(getToken()))

  useEffect(() => {
    function handleUnauthorized() {
      setState(buildState(null))
    }
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      isAuthenticated: state.token !== null,
      async login(email: string, password: string) {
        const response = await apiClient.post<{ accessToken: string }>('/api/auth/login', { email, password })
        setToken(response.data.accessToken)
        setState(buildState(response.data.accessToken))
      },
      logout() {
        clearToken()
        setState(buildState(null))
      },
    }),
    [state],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
