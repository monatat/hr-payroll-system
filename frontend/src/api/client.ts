import axios from 'axios'
import { clearToken, getToken } from '../auth/tokenStore'

export const UNAUTHORIZED_EVENT = 'auth:unauthorized'

// Centralized Axios instance. Attaches the Bearer token when present and
// broadcasts an event on 401 so AuthContext can clear its state -- no
// import of AuthContext here, to avoid a circular dependency.
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const isLoginRequest = typeof error?.config?.url === 'string' && error.config.url.includes('/api/auth/login')
    if (error?.response?.status === 401 && !isLoginRequest) {
      clearToken()
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    return Promise.reject(error)
  },
)
