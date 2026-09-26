import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { extractBlobProblemDetails, extractErrorCode, extractErrorMessage } from '../../api/errors'
import {
  downloadPayslipPdf,
  generatePayrollPdfs,
  getPayrollRun,
  listPayrollRunPayslips,
  retryPayrollRun,
  type Payslip,
  type PayrollPdfGenerationResult,
  type PayrollRun,
  type PayrollRunStatus,
} from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const PAGE_SIZE = 10

const STATUS_BADGE_STYLES: Record<PayrollRunStatus, string> = {
  PROCESSING: 'bg-amber-100 text-amber-700',
  CALCULATED: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  FAILED: 'bg-red-100 text-red-700',
}

function StatusBadge({ status }: { status: PayrollRunStatus }) {
  return <span className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_BADGE_STYLES[status]}`}>{status}</span>
}

type MutationMode = 'idle' | 'retry-confirm' | 'generate-confirm'

const RECOGNIZED_RETRY_CODES = new Set(['PAYROLL_RUN_NOT_FOUND', 'PAYROLL_RUN_NOT_FAILED', 'PAYROLL_RUN_FAILED'])
const RECOGNIZED_GENERATE_CODES = new Set([
  'PAYROLL_RUN_NOT_FOUND',
  'PAYROLL_RUN_NOT_CALCULATED',
  'PAYROLL_RUN_HAS_NO_PAYSLIPS',
  'PAYROLL_PDF_STORAGE_UNAVAILABLE',
])

const DOWNLOAD_FALLBACKS: Record<string, string> = {
  PAYSLIP_NOT_FOUND: 'Payslip not found.',
  PAYSLIP_PDF_NOT_AVAILABLE: 'This payslip PDF is not available yet.',
  PAYSLIP_PDF_FILE_MISSING: 'The payslip PDF file is missing.',
  PAYROLL_PDF_STORAGE_UNAVAILABLE: 'PDF storage is currently unavailable.',
}
const GENERIC_DOWNLOAD_ERROR = 'Could not download payslip PDF. Please try again.'

export function PayrollRunDetailPage() {
  const { id } = useParams<{ id: string }>()
  const runId = Number(id)
  const isValidId = Number.isInteger(runId) && runId > 0

  const [run, setRun] = useState<PayrollRun | null>(null)
  const [runLoading, setRunLoading] = useState(true)
  const [runError, setRunError] = useState<string | null>(null)

  const [payslips, setPayslips] = useState<Payslip[]>([])
  const [payslipPage, setPayslipPage] = useState(0)
  const [payslipTotalPages, setPayslipTotalPages] = useState(0)
  const [payslipLoading, setPayslipLoading] = useState(true)
  const [payslipError, setPayslipError] = useState<string | null>(null)

  const [refreshToken, setRefreshToken] = useState(0)

  const [mutationMode, setMutationMode] = useState<MutationMode>('idle')
  const [retrying, setRetrying] = useState(false)
  const [retryError, setRetryError] = useState<string | null>(null)
  const [retrySuccess, setRetrySuccess] = useState<string | null>(null)

  const [generating, setGenerating] = useState(false)
  const [generateError, setGenerateError] = useState<string | null>(null)
  const [generateResult, setGenerateResult] = useState<PayrollPdfGenerationResult | null>(null)

  // A Set, not a single id -- each row's download is tracked independently so starting a second
  // row's download can never silently clear the in-flight indicator of a still-pending row (see
  // the Phase 8D-2C correction: a lone `downloadingId: number | null` gets overwritten the moment
  // a second row starts, which both mis-renders the first row as idle and reopens its early-return
  // guard to a duplicate request).
  const [downloadingIds, setDownloadingIds] = useState<Set<number>>(new Set())
  const [downloadErrors, setDownloadErrors] = useState<Record<number, string>>({})

  useEffect(() => {
    if (!isValidId) {
      setRunLoading(false)
      setRunError('Payroll run not found.')
      return
    }
    let cancelled = false
    setRunLoading(true)
    setRunError(null)
    getPayrollRun(runId)
      .then((data) => {
        if (!cancelled) setRun(data)
      })
      .catch((err) => {
        if (cancelled) return
        const code = extractErrorCode(err)
        setRunError(code === 'PAYROLL_RUN_NOT_FOUND' ? 'Payroll run not found.' : extractErrorMessage(err, 'Could not load payroll run.'))
      })
      .finally(() => {
        if (!cancelled) setRunLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, isValidId, refreshToken])

  useEffect(() => {
    if (!isValidId) {
      setPayslipLoading(false)
      return
    }
    let cancelled = false
    setPayslipLoading(true)
    setPayslipError(null)
    listPayrollRunPayslips(runId, payslipPage, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return
        const totalPages = result.page.totalPages
        if (totalPages > 0 && payslipPage >= totalPages) {
          setPayslipPage(totalPages - 1)
          return
        }
        setPayslips(result.content)
        setPayslipTotalPages(totalPages)
      })
      .catch((err) => {
        if (cancelled) return
        setPayslips([])
        setPayslipTotalPages(0)
        setPayslipError(extractErrorMessage(err, 'Could not load payslips.'))
      })
      .finally(() => {
        if (!cancelled) setPayslipLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, isValidId, payslipPage, refreshToken])

  function goToPayslipPage(next: number) {
    if (payslipLoading) return
    setPayslipPage(next)
  }

  async function handleRetryConfirm() {
    if (retrying) return
    setRetryError(null)
    setRetrySuccess(null)
    setRetrying(true)
    try {
      await retryPayrollRun(runId)
      setRetrySuccess('Payroll run retried.')
      setMutationMode('idle')
      setRefreshToken((token) => token + 1)
    } catch (err) {
      const code = extractErrorCode(err)
      if (code && RECOGNIZED_RETRY_CODES.has(code)) {
        setRetryError(extractErrorMessage(err, 'Could not retry payroll run.'))
      } else {
        setRetryError('Could not retry payroll run. Please try again.')
      }
    } finally {
      setRetrying(false)
    }
  }

  async function handleGenerateConfirm() {
    if (generating) return
    setGenerateError(null)
    setGenerateResult(null)
    setGenerating(true)
    try {
      const result = await generatePayrollPdfs(runId)
      setGenerateResult(result)
      setMutationMode('idle')
      setRefreshToken((token) => token + 1)
    } catch (err) {
      const code = extractErrorCode(err)
      if (code && RECOGNIZED_GENERATE_CODES.has(code)) {
        setGenerateError(extractErrorMessage(err, 'Could not generate payslip PDFs.'))
      } else {
        setGenerateError('Could not generate payslip PDFs. Please try again.')
      }
    } finally {
      setGenerating(false)
    }
  }

  async function handleDownload(payslipId: number) {
    if (downloadingIds.has(payslipId)) return
    setDownloadErrors((prev) => {
      if (!(payslipId in prev)) return prev
      const next = { ...prev }
      delete next[payslipId]
      return next
    })
    // Never mutate the previous Set in place -- always build a new one, both so React reliably
    // detects the state change and so a concurrent update for a different id can never be lost.
    setDownloadingIds((prev) => new Set(prev).add(payslipId))
    try {
      await downloadPayslipPdf(payslipId)
    } catch (err) {
      const { code, detail } = await extractBlobProblemDetails(err)
      const message = code && code in DOWNLOAD_FALLBACKS ? (detail ?? DOWNLOAD_FALLBACKS[code]) : GENERIC_DOWNLOAD_ERROR
      setDownloadErrors((prev) => ({ ...prev, [payslipId]: message }))
    } finally {
      setDownloadingIds((prev) => {
        if (!prev.has(payslipId)) return prev
        const next = new Set(prev)
        next.delete(payslipId)
        return next
      })
    }
  }

  if (runLoading) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  if (runError || !run) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {runError ?? 'Payroll run not found.'}
      </p>
    )
  }

  return (
    <section className="mx-auto max-w-4xl space-y-6">
      <div>
        <Link to="/admin/payroll" className="text-sm text-slate-500 hover:underline">
          &larr; Back to Payroll Runs
        </Link>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <h1 className="text-xl font-semibold text-slate-900">{formatPayrollPeriod(run.periodMonth, run.periodYear)}</h1>
          <StatusBadge status={run.status} />
        </div>

        {run.failureReason && <p className="mt-2 text-sm text-red-600">{run.failureReason}</p>}

        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">Created</dt>
          <dd className="text-slate-900">{run.createdAt}</dd>
          <dt className="text-slate-500">Updated</dt>
          <dd className="text-slate-900">{run.updatedAt}</dd>
          <dt className="text-slate-500">Version</dt>
          <dd className="text-slate-900">{run.version}</dd>
        </dl>

        <p className="mt-4 text-xs uppercase tracking-wide text-slate-400">
          Rate snapshots below are fixed demonstration values, not statutory advice.
        </p>
        <dl className="mt-1 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">EPF rate</dt>
          <dd className="text-slate-900">{run.epfRateSnapshot}</dd>
          <dt className="text-slate-500">SOCSO rate</dt>
          <dd className="text-slate-900">{run.socsoRateSnapshot}</dd>
          <dt className="text-slate-500">EIS rate</dt>
          <dd className="text-slate-900">{run.eisRateSnapshot}</dd>
          <dt className="text-slate-500">Income tax rate</dt>
          <dd className="text-slate-900">{run.incomeTaxRateSnapshot}</dd>
        </dl>

        {run.status === 'FAILED' && mutationMode === 'idle' && (
          <div className="mt-6 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => {
                setRetryError(null)
                setRetrySuccess(null)
                setMutationMode('retry-confirm')
              }}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Retry Payroll
            </button>
          </div>
        )}
        {run.status === 'CALCULATED' && mutationMode === 'idle' && (
          <div className="mt-6 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => {
                setGenerateError(null)
                setGenerateResult(null)
                setMutationMode('generate-confirm')
              }}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Generate PDFs
            </button>
          </div>
        )}

        {mutationMode === 'retry-confirm' && (
          <div className="mt-6 space-y-3 rounded-md border border-slate-200 p-4">
            <p className="text-sm text-slate-700">Retry this payroll run?</p>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={handleRetryConfirm}
                disabled={retrying}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {retrying ? 'Retrying...' : 'Confirm retry'}
              </button>
              <button
                type="button"
                onClick={() => setMutationMode('idle')}
                disabled={retrying}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {mutationMode === 'generate-confirm' && (
          <div className="mt-6 space-y-3 rounded-md border border-slate-200 p-4">
            <p className="text-sm text-slate-700">Generate PDFs for this payroll run?</p>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={handleGenerateConfirm}
                disabled={generating}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {generating ? 'Generating...' : 'Confirm generate'}
              </button>
              <button
                type="button"
                onClick={() => setMutationMode('idle')}
                disabled={generating}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {retryError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {retryError}
          </p>
        )}
        {retrySuccess && (
          <p aria-live="polite" className="mt-3 text-sm text-emerald-600">
            {retrySuccess}
          </p>
        )}

        {generateError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {generateError}
          </p>
        )}
        {generateResult &&
          (generateResult.failedCount === 0 ? (
            <div aria-live="polite" className="mt-3 rounded-md bg-emerald-50 p-3 text-sm text-emerald-700">
              PDFs generated successfully. Generated: {generateResult.generatedCount}, Skipped:{' '}
              {generateResult.skippedCount}.
            </div>
          ) : (
            <div aria-live="polite" className="mt-3 rounded-md bg-amber-50 p-3 text-sm text-amber-800">
              Some payslip PDFs could not be generated. Generated: {generateResult.generatedCount}, Failed:{' '}
              {generateResult.failedCount}, Skipped: {generateResult.skippedCount}. You can use "Generate PDFs" again
              while this run remains CALCULATED.
            </div>
          ))}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white shadow-sm">
        <h2 className="px-6 pt-6 text-sm font-semibold text-slate-900">Payslips</h2>
        {payslipError && (
          <p role="alert" className="px-6 pt-3 text-sm text-red-600">
            {payslipError}
          </p>
        )}
        <div className="relative mt-4 overflow-x-auto">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <caption className="sr-only">Payslips for this payroll run</caption>
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-4 py-2">Employee code</th>
                <th className="px-4 py-2">Employee name</th>
                <th className="px-4 py-2">Department</th>
                <th className="px-4 py-2">Job title</th>
                <th className="px-4 py-2">Base pay</th>
                <th className="px-4 py-2">Housing</th>
                <th className="px-4 py-2">Transport</th>
                <th className="px-4 py-2">Gross pay</th>
                <th className="px-4 py-2">EPF</th>
                <th className="px-4 py-2">SOCSO</th>
                <th className="px-4 py-2">EIS</th>
                <th className="px-4 py-2">Income tax</th>
                <th className="px-4 py-2">Total deductions</th>
                <th className="px-4 py-2">Net pay</th>
                <th className="px-4 py-2">PDF status</th>
                <th className="px-4 py-2">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {payslips.map((row) => (
                <tr key={row.id}>
                  <td className="px-4 py-2">{row.employeeCodeSnapshot}</td>
                  <td className="px-4 py-2">{row.employeeNameSnapshot}</td>
                  <td className="px-4 py-2">{row.departmentNameSnapshot}</td>
                  <td className="px-4 py-2">{row.jobTitleSnapshot}</td>
                  <td className="px-4 py-2">{formatMYR(row.basePay)}</td>
                  <td className="px-4 py-2">{formatMYR(row.housingAllowance)}</td>
                  <td className="px-4 py-2">{formatMYR(row.transportAllowance)}</td>
                  <td className="px-4 py-2">{formatMYR(row.grossPay)}</td>
                  <td className="px-4 py-2">{formatMYR(row.epfDeduction)}</td>
                  <td className="px-4 py-2">{formatMYR(row.socsoDeduction)}</td>
                  <td className="px-4 py-2">{formatMYR(row.eisDeduction)}</td>
                  <td className="px-4 py-2">{formatMYR(row.incomeTax)}</td>
                  <td className="px-4 py-2">{formatMYR(row.totalDeductions)}</td>
                  <td className="px-4 py-2">{formatMYR(row.netPay)}</td>
                  <td className="px-4 py-2">{row.pdfStatus}</td>
                  <td className="px-4 py-2">
                    {row.pdfStatus === 'GENERATED' && (
                      <button
                        type="button"
                        disabled={downloadingIds.has(row.id)}
                        onClick={() => handleDownload(row.id)}
                        className="rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 disabled:opacity-50"
                      >
                        {downloadingIds.has(row.id) ? 'Downloading...' : 'Download PDF'}
                      </button>
                    )}
                    {row.pdfStatus === 'PENDING' && <span className="text-xs text-slate-500">PDF not ready</span>}
                    {row.pdfStatus === 'FAILED' && (
                      <span className="text-xs text-red-600">PDF generation failed</span>
                    )}
                    {downloadErrors[row.id] && (
                      <p role="alert" className="mt-1 text-xs text-red-600">
                        {downloadErrors[row.id]}
                      </p>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {payslipLoading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
          {!payslipLoading && !payslipError && payslips.length === 0 && (
            <p className="p-4 text-sm text-slate-500">No payslips found for this run.</p>
          )}
        </div>

        <div className="flex items-center justify-between px-6 py-4 text-sm text-slate-600">
          <button
            type="button"
            disabled={payslipLoading || payslipPage <= 0}
            onClick={() => goToPayslipPage(payslipPage - 1)}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Previous
          </button>
          <span>
            Page {payslipPage + 1} of {Math.max(payslipTotalPages, 1)}
          </span>
          <button
            type="button"
            disabled={payslipLoading || payslipPage + 1 >= payslipTotalPages}
            onClick={() => goToPayslipPage(payslipPage + 1)}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      </div>
    </section>
  )
}
