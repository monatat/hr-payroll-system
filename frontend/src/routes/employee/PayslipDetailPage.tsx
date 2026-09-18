import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { extractBlobProblemDetails, extractErrorCode } from '../../api/errors'
import { downloadPayslipPdf, getPayslip, type Payslip } from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const NOT_FOUND_MESSAGE = 'Payslip not found.'
const LOAD_ERROR_FALLBACK = 'Could not load your payslip. Please try again.'
const PENDING_MESSAGE = 'Your payslip PDF is not ready yet.'
const FAILED_MESSAGE = 'Your payslip PDF could not be generated. Please contact HR.'
const GENERIC_DOWNLOAD_ERROR = 'Could not download payslip PDF. Please try again.'

const DOWNLOAD_FALLBACKS: Record<string, string> = {
  PAYSLIP_NOT_FOUND: 'Payslip not found.',
  PAYSLIP_PDF_NOT_AVAILABLE: 'Your payslip PDF is not available.',
  PAYSLIP_PDF_FILE_MISSING: 'The payslip PDF file is unavailable. Please contact HR.',
  PAYROLL_PDF_STORAGE_UNAVAILABLE: 'The payslip PDF service is unavailable. Please try again later.',
}

/**
 * EMPLOYEE-only, strictly read-only payslip detail. Identity/ownership is enforced entirely by
 * the backend via `getPayslip`/`downloadPayslipPdf` (both derive the caller from the JWT) -- this
 * page never reads AuthContext, decodes the JWT, or performs any client-side ownership check. A
 * non-owned payslip and an unknown id both surface from the backend as the identical
 * PAYSLIP_NOT_FOUND code, and this page collapses both to the same fixed "Payslip not found."
 * text, never the backend's own detail, so an error can never reveal which case occurred.
 */
export function PayslipDetailPage() {
  const { id } = useParams<{ id: string }>()
  const payslipId = Number(id)
  const isValidId = Number.isInteger(payslipId) && payslipId > 0

  const [payslip, setPayslip] = useState<Payslip | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)

  useEffect(() => {
    if (!isValidId) {
      setLoading(false)
      setLoadError(NOT_FOUND_MESSAGE)
      return
    }
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    getPayslip(payslipId)
      .then((data) => {
        if (!cancelled) setPayslip(data)
      })
      .catch((err) => {
        if (cancelled) return
        const code = extractErrorCode(err)
        setLoadError(code === 'PAYSLIP_NOT_FOUND' ? NOT_FOUND_MESSAGE : LOAD_ERROR_FALLBACK)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payslipId, isValidId])

  async function handleDownload() {
    if (downloading) return
    setDownloadError(null)
    setDownloading(true)
    try {
      await downloadPayslipPdf(payslipId)
    } catch (err) {
      const { code, detail } = await extractBlobProblemDetails(err)
      const message =
        code && code in DOWNLOAD_FALLBACKS ? (detail && detail.trim() !== '' ? detail : DOWNLOAD_FALLBACKS[code]) : GENERIC_DOWNLOAD_ERROR
      setDownloadError(message)
    } finally {
      setDownloading(false)
    }
  }

  if (loading) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  if (loadError || !payslip) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError ?? NOT_FOUND_MESSAGE}
      </p>
    )
  }

  return (
    <section className="mx-auto max-w-2xl space-y-6">
      <div>
        <Link to="/employee/payslips" className="text-sm text-slate-500 hover:underline">
          &larr; Back to My Payslips
        </Link>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">
          {formatPayrollPeriod(payslip.periodMonth, payslip.periodYear)}
        </h1>

        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">PDF Status</dt>
          <dd className="text-slate-900">{payslip.pdfStatus}</dd>
          <dt className="text-slate-500">Created At</dt>
          <dd className="text-slate-900">{payslip.createdAt}</dd>
        </dl>

        <h2 className="mt-6 text-sm font-semibold text-slate-900">Employee</h2>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">Employee Code</dt>
          <dd className="text-slate-900">{payslip.employeeCodeSnapshot}</dd>
          <dt className="text-slate-500">Employee Name</dt>
          <dd className="text-slate-900">{payslip.employeeNameSnapshot}</dd>
          <dt className="text-slate-500">Department</dt>
          <dd className="text-slate-900">{payslip.departmentNameSnapshot}</dd>
          <dt className="text-slate-500">Job Title</dt>
          <dd className="text-slate-900">{payslip.jobTitleSnapshot}</dd>
        </dl>

        <h2 className="mt-6 text-sm font-semibold text-slate-900">Earnings</h2>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">Base Pay</dt>
          <dd className="text-slate-900">{formatMYR(payslip.basePay)}</dd>
          <dt className="text-slate-500">Housing Allowance</dt>
          <dd className="text-slate-900">{formatMYR(payslip.housingAllowance)}</dd>
          <dt className="text-slate-500">Transport Allowance</dt>
          <dd className="text-slate-900">{formatMYR(payslip.transportAllowance)}</dd>
          <dt className="text-slate-500">Gross Pay</dt>
          <dd className="text-slate-900">{formatMYR(payslip.grossPay)}</dd>
        </dl>

        <h2 className="mt-6 text-sm font-semibold text-slate-900">Deductions</h2>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">EPF Deduction</dt>
          <dd className="text-slate-900">{formatMYR(payslip.epfDeduction)}</dd>
          <dt className="text-slate-500">SOCSO Deduction</dt>
          <dd className="text-slate-900">{formatMYR(payslip.socsoDeduction)}</dd>
          <dt className="text-slate-500">EIS Deduction</dt>
          <dd className="text-slate-900">{formatMYR(payslip.eisDeduction)}</dd>
          <dt className="text-slate-500">Income Tax</dt>
          <dd className="text-slate-900">{formatMYR(payslip.incomeTax)}</dd>
          <dt className="text-slate-500">Total Deductions</dt>
          <dd className="text-slate-900">{formatMYR(payslip.totalDeductions)}</dd>
        </dl>

        <dl className="mt-6 border-t border-slate-200 pt-4">
          <dt className="text-sm text-slate-500">Net Pay</dt>
          <dd className="text-2xl font-bold text-slate-900">{formatMYR(payslip.netPay)}</dd>
        </dl>

        <div className="mt-6">
          {payslip.pdfStatus === 'GENERATED' && (
            <button
              type="button"
              disabled={downloading}
              onClick={handleDownload}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            >
              {downloading ? 'Downloading...' : 'Download PDF'}
            </button>
          )}
          {payslip.pdfStatus === 'PENDING' && <p className="text-sm text-slate-500">{PENDING_MESSAGE}</p>}
          {payslip.pdfStatus === 'FAILED' && <p className="text-sm text-red-600">{FAILED_MESSAGE}</p>}
          {downloadError && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {downloadError}
            </p>
          )}
        </div>
      </div>
    </section>
  )
}
