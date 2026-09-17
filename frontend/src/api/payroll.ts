import { apiClient } from './client'

export type PayrollRunStatus = 'PROCESSING' | 'CALCULATED' | 'COMPLETED' | 'FAILED'

export type PdfStatus = 'PENDING' | 'GENERATED' | 'FAILED'

// Mirrors the backend's PayrollFailureCode enum (PayrollRunTransactionService), which is never
// itself serialized on the wire -- PayrollRun.failureReason below is always a free-text safe
// message describing one of these underlying causes (e.g. "No eligible employees for this
// period."), never the bare code, so failureReason is typed as `string | null`, not this type.
export type PayrollFailureCode =
  | 'NO_ELIGIBLE_EMPLOYEES'
  | 'SALARY_COVERAGE_MISSING'
  | 'SALARY_COVERAGE_AMBIGUOUS'
  | 'PAYROLL_CALCULATION_FAILED'

export type CreatePayrollRunRequest = {
  month: number
  year: number
}

export type PayrollRun = {
  id: number
  periodMonth: number
  periodYear: number
  periodStart: string
  periodEnd: string
  status: PayrollRunStatus
  failureReason: string | null
  epfRateSnapshot: number
  socsoRateSnapshot: number
  eisRateSnapshot: number
  incomeTaxRateSnapshot: number
  version: number
  createdAt: string
  updatedAt: string
}

// periodMonth/periodYear come from the payslip's associated PayrollRun (Phase 8D-2A1), never from
// createdAt. pdfPath is deliberately absent -- the backend never serializes it either.
export type Payslip = {
  id: number
  payrollRunId: number
  employeeId: number
  employeeCodeSnapshot: string
  employeeNameSnapshot: string
  departmentNameSnapshot: string
  jobTitleSnapshot: string
  basePay: number
  housingAllowance: number
  transportAllowance: number
  grossPay: number
  epfDeduction: number
  socsoDeduction: number
  eisDeduction: number
  incomeTax: number
  totalDeductions: number
  netPay: number
  pdfStatus: PdfStatus
  createdAt: string
  periodMonth: number
  periodYear: number
}

export type PayrollPdfGenerationResult = {
  payrollRunId: number
  status: PayrollRunStatus
  generatedCount: number
  failedCount: number
  skippedCount: number
}

// Same PagedResponse shape used by every Spring PagedModel endpoint in this project (see
// leave.ts) -- duplicated locally rather than imported cross-module, matching this codebase's
// established per-module convention (no shared paging type is exported from any module).
export type PagedResponse<T> = {
  content: T[]
  page: {
    size: number
    totalElements: number
    totalPages: number
    number: number
  }
}

// Removes undefined/null/'' entries entirely (rather than sending them as empty query params),
// while preserving falsy-but-valid values like page 0 -- a naive `if (value)` check would wrongly
// drop those.
function cleanParams<T extends Record<string, unknown>>(params: T): Partial<T> {
  const result: Partial<T> = {}
  for (const key of Object.keys(params) as (keyof T)[]) {
    const value = params[key]
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}

export async function createPayrollRun(request: CreatePayrollRunRequest): Promise<PayrollRun> {
  const response = await apiClient.post<PayrollRun>('/api/payroll-runs', request)
  return response.data
}

export type ListPayrollRunsOptions = {
  status?: PayrollRunStatus
  year?: number
  page?: number
  size?: number
}

export async function listPayrollRuns(options: ListPayrollRunsOptions = {}): Promise<PagedResponse<PayrollRun>> {
  const response = await apiClient.get<PagedResponse<PayrollRun>>('/api/payroll-runs', {
    params: cleanParams(options),
  })
  return response.data
}

export async function getPayrollRun(id: number): Promise<PayrollRun> {
  const response = await apiClient.get<PayrollRun>(`/api/payroll-runs/${id}`)
  return response.data
}

export async function listPayrollRunPayslips(
  runId: number,
  page?: number,
  size?: number,
): Promise<PagedResponse<Payslip>> {
  const response = await apiClient.get<PagedResponse<Payslip>>(`/api/payroll-runs/${runId}/payslips`, {
    params: cleanParams({ page, size }),
  })
  return response.data
}

// No request body: the only input is the path id, exactly matching the backend contract (the
// actor id comes only from the JWT). Called with a single argument, same convention as
// leave.ts's approveLeaveRequest for a body-less POST/PUT.
export async function retryPayrollRun(id: number): Promise<PayrollRun> {
  const response = await apiClient.post<PayrollRun>(`/api/payroll-runs/${id}/retry`)
  return response.data
}

export async function generatePayrollPdfs(id: number): Promise<PayrollPdfGenerationResult> {
  const response = await apiClient.post<PayrollPdfGenerationResult>(`/api/payroll-runs/${id}/generate-pdfs`)
  return response.data
}

export async function getPayslip(id: number): Promise<Payslip> {
  const response = await apiClient.get<Payslip>(`/api/payslips/${id}`)
  return response.data
}

// Calls the EMPLOYEE-only self-service endpoint -- identity comes from the caller's JWT on the
// backend, never from a client-supplied employee id (there is no such parameter on this
// function's signature or on the request it sends).
export async function listOwnPayslips(page?: number, size?: number): Promise<PagedResponse<Payslip>> {
  const response = await apiClient.get<PagedResponse<Payslip>>('/api/employees/me/payslips', {
    params: cleanParams({ page, size }),
  })
  return response.data
}

// Only bare, safe filename characters survive sanitization -- no slash/backslash, quote, CR/LF,
// or semicolon can ever reach the DOM `download` attribute this feeds.
const SAFE_FILENAME_CHARS = /[^A-Za-z0-9._-]/g
const CONTENT_DISPOSITION_FILENAME_PATTERN = /filename\s*=\s*(?:"([^"]*)"|([^;]+))/i

function fallbackPayslipFilename(payslipId: number): string {
  return `payslip-${payslipId}.pdf`
}

// Extracts and sanitizes the filename from a Content-Disposition header value. Defense in depth
// only -- the backend already sanitizes its own filename (Phase 8C-2B2) -- so any parsing or
// sanitization failure here falls back to a safe, deterministic name rather than ever attempting
// to interpret an arbitrary path. A raw value containing any path separator is rejected outright
// (never "cleaned up") so a traversal-shaped value like "../../etc/passwd.pdf" cannot survive by
// having its separators silently stripped into "......etcpasswd.pdf".
export function parsePayslipPdfFilename(contentDisposition: string | undefined | null, payslipId: number): string {
  const fallback = fallbackPayslipFilename(payslipId)
  if (!contentDisposition) return fallback

  const match = CONTENT_DISPOSITION_FILENAME_PATTERN.exec(contentDisposition)
  if (!match) return fallback

  const raw = (match[1] ?? match[2] ?? '').trim()
  if (!raw) return fallback
  if (raw.includes('/') || raw.includes('\\')) return fallback

  const sanitized = raw.replace(SAFE_FILENAME_CHARS, '')
  if (!sanitized || sanitized === '.' || sanitized === '..') return fallback
  if (!sanitized.toLowerCase().endsWith('.pdf')) return fallback

  return sanitized
}

export type PayslipPdfDownload = {
  blob: Blob
  filename: string
}

// Reads the PDF as an authenticated Blob via the existing apiClient -- the request interceptor
// attaches the Bearer token automatically, so this module never touches sessionStorage or sets a
// second Authorization header. Kept separate from saveBlobAsFile below so each half can be
// unit-tested independently: this one exercises the network/filename-parsing behavior, the other
// exercises the browser download mechanics. pdfPath is never requested or exposed anywhere here.
export async function fetchPayslipPdf(id: number): Promise<PayslipPdfDownload> {
  const response = await apiClient.get<Blob>(`/api/payslips/${id}/pdf`, { responseType: 'blob' })
  const filename = parsePayslipPdfFilename(response.headers['content-disposition'], id)
  return { blob: response.data, filename }
}

// Triggers the browser's native save flow for an already-fetched Blob. The object URL is revoked
// in an outer finally that wraps everything after its creation -- including `document.
// createElement('a')` itself and the anchor-removal step -- so a failure in *any* of those (not
// just click()) can never leak it. Anchor removal is attempted in its own nested try/finally so a
// failure there (e.g. `anchor.remove()` throwing) still lets the outer finally run and revoke the
// URL; that removal failure is deliberately allowed to propagate rather than being swallowed.
export function saveBlobAsFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    try {
      anchor.href = url
      anchor.download = filename
      document.body.appendChild(anchor)
      anchor.click()
    } finally {
      anchor.remove()
    }
  } finally {
    URL.revokeObjectURL(url)
  }
}

// Convenience composition for pages: fetch then save, in one call. Both HR Admin and Employee
// pages call this identically -- the backend alone enforces ownership, so no separate
// "self-service" download variant is needed here. Takes only an id: there is no way for a caller
// to supply a filename or path.
export async function downloadPayslipPdf(id: number): Promise<void> {
  const { blob, filename } = await fetchPayslipPdf(id)
  saveBlobAsFile(blob, filename)
}
