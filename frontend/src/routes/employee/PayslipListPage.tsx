import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listOwnPayslips, type Payslip } from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const PAGE_SIZE = 10
const LOAD_ERROR_FALLBACK = 'Could not load your payslips. Please try again.'

/**
 * EMPLOYEE-only, strictly read-only self-service list. Identity comes only from the JWT via
 * `listOwnPayslips` (GET /api/employees/me/payslips) -- there is no employee-id parameter
 * anywhere on this page, no route/query param, and no client-side ownership filtering. The
 * backend is the only authorization boundary. This is a concise history view: no PDF download,
 * no deduction breakdown, no employee/department/job-title snapshot, and no payrollRunId are
 * rendered -- the detailed breakdown and download arrive in Phase 8D-2D2's detail page.
 */
export function PayslipListPage() {
  const [payslips, setPayslips] = useState<Payslip[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)

    listOwnPayslips(page, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return
        const total = result.page.totalPages
        if (total > 0 && page >= total) {
          setPage(total - 1)
          return
        }
        setPayslips(result.content)
        setTotalPages(total)
      })
      .catch(() => {
        if (cancelled) return
        setPayslips([])
        // Always the fixed fallback, never the backend's own `detail` -- unlike some other
        // self-service pages, this endpoint's error text is never trusted here, so a load failure
        // can never reveal anything about the underlying data (e.g. whether some other record
        // exists). totalPages is deliberately left untouched -- collapsing it to 0 would disable
        // both Previous and Next, leaving no way to retry via pagination.
        setLoadError(LOAD_ERROR_FALLBACK)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [page])

  function goToPage(next: number) {
    if (loading) return
    setPage(next)
  }

  return (
    <section className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">My Payslips</h1>

      {loadError && (
        <p role="alert" className="text-sm text-red-600">
          {loadError}
        </p>
      )}

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <caption className="sr-only">My payslips</caption>
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-4 py-2">Payroll Period</th>
              <th className="px-4 py-2">Gross Pay</th>
              <th className="px-4 py-2">Total Deductions</th>
              <th className="px-4 py-2">Net Pay</th>
              <th className="px-4 py-2">PDF Status</th>
              <th className="px-4 py-2">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {payslips.map((row) => (
              <tr key={row.id}>
                <td className="px-4 py-2">{formatPayrollPeriod(row.periodMonth, row.periodYear)}</td>
                <td className="px-4 py-2">{formatMYR(row.grossPay)}</td>
                <td className="px-4 py-2">{formatMYR(row.totalDeductions)}</td>
                <td className="px-4 py-2">{formatMYR(row.netPay)}</td>
                <td className="px-4 py-2">{row.pdfStatus}</td>
                <td className="px-4 py-2">
                  <Link to={`/employee/payslips/${row.id}`} className="text-blue-600 hover:underline">
                    View
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
        {!loading && !loadError && payslips.length === 0 && (
          <p className="p-4 text-sm text-slate-500">No payslips found.</p>
        )}
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <button
          type="button"
          disabled={loading || page <= 0}
          onClick={() => goToPage(page - 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Previous
        </button>
        <span>
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={loading || page + 1 >= totalPages}
          onClick={() => goToPage(page + 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </section>
  )
}
