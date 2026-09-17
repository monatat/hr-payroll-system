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

export type BlobProblemDetails = {
  code: string | null
  detail: string | null
}

const EMPTY_BLOB_PROBLEM_DETAILS: BlobProblemDetails = { code: null, detail: null }

const JSON_CONTENT_TYPE_PATTERN = /^application\/(problem\+)?json/i

function headerContentType(headers: unknown): string {
  if (headers && typeof headers === 'object') {
    const contentType = (headers as Record<string, unknown>)['content-type']
    if (typeof contentType === 'string') return contentType
  }
  return ''
}

// Checked independently rather than picking one source and falling back to the other -- a
// generic value from one source (e.g. a proxy/adapter defaulting Blob.type to
// "application/octet-stream") must never override an explicit JSON content type asserted by the
// other. Either source alone is enough to accept the body as JSON.
function blobIndicatesJson(blob: Blob, headers: unknown): boolean {
  return JSON_CONTENT_TYPE_PATTERN.test(blob.type) || JSON_CONTENT_TYPE_PATTERN.test(headerContentType(headers))
}

// A request made with `responseType: 'blob'` (e.g. the payslip PDF download) never gets a parsed
// JSON error body -- axios hands back a Blob even on failure, since it can't know in advance
// whether an error response will be JSON or binary. This recovers the backend's ProblemDetail
// code/detail from that Blob for a nicer error message, without ever risking a second thrown
// error masking the original request failure (every failure path below resolves to nulls).
export async function extractBlobProblemDetails(error: unknown): Promise<BlobProblemDetails> {
  try {
    if (typeof error !== 'object' || error === null || !('response' in error)) {
      return EMPTY_BLOB_PROBLEM_DETAILS
    }
    const response = (error as { response?: { data?: unknown; headers?: unknown } }).response
    const data = response?.data
    if (!(data instanceof Blob)) {
      return EMPTY_BLOB_PROBLEM_DETAILS
    }

    if (!blobIndicatesJson(data, response?.headers)) {
      return EMPTY_BLOB_PROBLEM_DETAILS
    }

    const text = await data.text()
    if (!text) {
      return EMPTY_BLOB_PROBLEM_DETAILS
    }

    const parsed: unknown = JSON.parse(text)
    if (typeof parsed !== 'object' || parsed === null) {
      return EMPTY_BLOB_PROBLEM_DETAILS
    }

    const record = parsed as Record<string, unknown>
    return {
      code: typeof record.code === 'string' ? record.code : null,
      detail: typeof record.detail === 'string' ? record.detail : null,
    }
  } catch {
    return EMPTY_BLOB_PROBLEM_DETAILS
  }
}
