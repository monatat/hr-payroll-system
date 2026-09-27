import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { extractErrorCode, extractErrorMessage } from '../../api/errors'
import {
  createPayrollRun,
  listPayrollRuns,
  type ListPayrollRunsOptions,
  type PayrollRun,
  type PayrollRunStatus,
} from '../../api/payroll'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const PAGE_SIZE = 10
const MIN_YEAR = 2000
const MAX_YEAR = 2100

const STATUS_BADGE_STYLES: Record<PayrollRunStatus, string> = {
  PROCESSING: 'bg-amber-100 text-amber-700',
  CALCULATED: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  FAILED: 'bg-red-100 text-red-700',
}

function StatusBadge({ status }: { status: PayrollRunStatus }) {
  return <span className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_BADGE_STYLES[status]}`}>{status}</span>
}

const MONTH_OPTIONS: { value: number; label: string }[] = [
  { value: 1, label: 'January' },
  { value: 2, label: 'February' },
  { value: 3, label: 'March' },
  { value: 4, label: 'April' },
  { value: 5, label: 'May' },
  { value: 6, label: 'June' },
  { value: 7, label: 'July' },
  { value: 8, label: 'August' },
  { value: 9, label: 'September' },
  { value: 10, label: 'October' },
  { value: 11, label: 'November' },
  { value: 12, label: 'December' },
]

// Deliberately string manipulation, not `new Date(...)` -- consistent with payrollPeriod.ts's
// timezone-safety rule, and createdAt is already a fixed server timestamp with no timezone
// ambiguity to resolve, so a display Date object would add risk for no benefit.
function formatCreatedAt(value: string): string {
  return value.replace('T', ' ').slice(0, 16)
}

export function PayrollRunListPage() {
  const [status, setStatus] = useState<PayrollRunStatus | 'ALL'>('ALL')
  const [yearFilter, setYearFilter] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [refreshToken, setRefreshToken] = useState(0)

  const [runs, setRuns] = useState<PayrollRun[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [showCreateForm, setShowCreateForm] = useState(false)
  const [month, setMonth] = useState('1')
  const [year, setYear] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [createSuccess, setCreateSuccess] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)

    const options: ListPayrollRunsOptions = { page, size: PAGE_SIZE }
    if (status !== 'ALL') options.status = status
    if (yearFilter) options.year = Number(yearFilter)

    listPayrollRuns(options)
      .then((result) => {
        if (cancelled) return
        setRuns(result.content)
        setTotalPages(result.page.totalPages)
      })
      .catch((err) => {
        if (cancelled) return
        setRuns([])
        setTotalPages(0)
        setLoadError(extractErrorMessage(err, 'Could not load payroll runs.'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [status, yearFilter, page, refreshToken])

  function goToPage(next: number) {
    if (loading) return
    setPage(next)
  }

  function resetCreateForm() {
    setShowCreateForm(false)
    setMonth('1')
    setYear('')
    setCreateError(null)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (creating) return

    const monthNum = Number(month)
    const yearNum = Number(year)
    if (!Number.isInteger(monthNum) || monthNum < 1 || monthNum > 12) {
      setCreateError('Select a valid month.')
      return
    }
    if (year === '' || !Number.isInteger(yearNum) || yearNum < MIN_YEAR || yearNum > MAX_YEAR) {
      setCreateError(`Enter a year between ${MIN_YEAR} and ${MAX_YEAR}.`)
      return
    }

    setCreateError(null)
    setCreateSuccess(null)
    setCreating(true)
    try {
      await createPayrollRun({ month: monthNum, year: yearNum })
      setCreateSuccess('Payroll run created.')
      resetCreateForm()
      setPage(0)
      setRefreshToken((token) => token + 1)
    } catch (err) {
      const code = extractErrorCode(err)
      if (code === 'PAYROLL_RUN_PERIOD_ALREADY_EXISTS') {
        setCreateError(extractErrorMessage(err, 'A payroll run for this period already exists.'))
      } else if (code === 'PAYROLL_RUN_FAILED') {
        setCreateError(extractErrorMessage(err, 'Payroll calculation failed.'))
      } else {
        setCreateError('Could not create payroll run. Please try again.')
      }
    } finally {
      setCreating(false)
    }
  }

  return (
    <section className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Payroll Runs</h1>

      <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        {!showCreateForm ? (
          <button
            type="button"
            onClick={() => {
              setShowCreateForm(true)
              setCreateSuccess(null)
            }}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
          >
            Create Payroll Run
          </button>
        ) : (
          <form onSubmit={handleSubmit} noValidate className="space-y-4">
            <div className="flex flex-wrap items-end gap-4">
              <div>
                <label htmlFor="payroll-run-month" className="block text-sm font-medium text-slate-700">
                  Month
                </label>
                <select
                  id="payroll-run-month"
                  value={month}
                  disabled={creating}
                  onChange={(event) => setMonth(event.target.value)}
                  className="mt-1 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
                >
                  {MONTH_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="payroll-run-year" className="block text-sm font-medium text-slate-700">
                  Year
                </label>
                <input
                  id="payroll-run-year"
                  type="number"
                  min={MIN_YEAR}
                  max={MAX_YEAR}
                  value={year}
                  disabled={creating}
                  onChange={(event) => setYear(event.target.value)}
                  className="mt-1 w-28 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
                />
              </div>
              <button
                type="submit"
                disabled={creating}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {creating ? 'Creating...' : 'Create'}
              </button>
              <button
                type="button"
                disabled={creating}
                onClick={resetCreateForm}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
            {createError && (
              <p role="alert" className="text-sm text-red-600">
                {createError}
              </p>
            )}
          </form>
        )}
        {createSuccess && (
          <p aria-live="polite" className="mt-2 text-sm text-emerald-600">
            {createSuccess}
          </p>
        )}
      </div>

      <div className="flex flex-wrap gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(event) => {
            setPage(0)
            setStatus(event.target.value as PayrollRunStatus | 'ALL')
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        >
          <option value="ALL">All statuses</option>
          <option value="PROCESSING">Processing</option>
          <option value="CALCULATED">Calculated</option>
          <option value="COMPLETED">Completed</option>
          <option value="FAILED">Failed</option>
        </select>
        <input
          aria-label="Filter by year"
          type="number"
          placeholder="Year"
          value={yearFilter}
          onChange={(event) => {
            setPage(0)
            setYearFilter(event.target.value)
          }}
          className="w-28 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        />
      </div>

      {loadError && (
        <p role="alert" className="text-sm text-red-600">
          {loadError}
        </p>
      )}

      <div className="relative overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <caption className="sr-only">Payroll runs</caption>
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-4 py-2">Period</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2">Created</th>
              <th className="px-4 py-2">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {runs.map((run) => (
              <tr key={run.id}>
                <td className="px-4 py-2">{formatPayrollPeriod(run.periodMonth, run.periodYear)}</td>
                <td className="px-4 py-2">
                  <StatusBadge status={run.status} />
                </td>
                <td className="px-4 py-2">{formatCreatedAt(run.createdAt)}</td>
                <td className="px-4 py-2">
                  <Link to={`/admin/payroll/${run.id}`} className="text-blue-600 hover:underline">
                    View
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
        {!loading && !loadError && runs.length === 0 && (
          <p className="p-4 text-sm text-slate-500">No payroll runs found.</p>
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
