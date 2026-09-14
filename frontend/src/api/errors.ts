// Extracts the backend's ProblemDetail "detail" message from an Axios
// error, falling back to a generic message. Shared by every form that
// calls the API, so error handling stays consistent across pages.
export function extractErrorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: { detail?: string } } }).response
    if (response?.data?.detail) {
      return response.data.detail
    }
  }
  return fallback
}

// Extracts the backend's ProblemDetail "code" property (e.g.
// LEAVE_REQUEST_ALREADY_PROCESSED) so a caller can branch on the specific
// failure reason without string-matching the human-readable detail text.
export function extractErrorCode(error: unknown): string | null {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: { code?: string } } }).response
    if (response?.data?.code) {
      return response.data.code
    }
  }
  return null
}
