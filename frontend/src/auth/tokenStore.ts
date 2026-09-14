// Isolated from AuthContext and the Axios client so neither has to import
// the other: both import this instead. sessionStorage is the approved
// demo-scope tradeoff for Phase 4 (clears on tab close, no refresh token to
// protect anyway).
const STORAGE_KEY = 'hr_payroll_access_token'

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, token)
  } catch {
    // Storage unavailable (e.g. private browsing) -- fail silently, the
    // user simply won't stay logged in across reloads.
  }
}

export function clearToken(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}
