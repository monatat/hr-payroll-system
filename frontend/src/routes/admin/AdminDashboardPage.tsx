import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listLeaveRequests } from '../../api/leave'
import { listPayrollRuns, type PayrollRun, type PayrollRunStatus } from '../../api/payroll'
import { getHeadcountReport, type HeadcountReport } from '../../api/reports'
import { formatAttendanceDate } from '../../utils/attendanceDateTime'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

// Fixed, safe messages -- the real error (which may carry backend/internal detail) is never shown.
const HEADCOUNT_LOAD_ERROR = 'Could not load active employees. Please try again.'
const PENDING_LEAVE_LOAD_ERROR = 'Could not load pending leave requests. Please try again.'
const PAYROLL_LOAD_ERROR = 'Could not load latest payroll run. Please try again.'

// Same status-badge convention as PayrollRunListPage/PayrollRunDetailPage: the status is always
// rendered as visible text, with color only as a secondary cue.
const STATUS_BADGE_STYLES: Record<PayrollRunStatus, string> = {
  PROCESSING: 'bg-amber-100 text-amber-700',
  CALCULATED: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  FAILED: 'bg-red-100 text-red-700',
}

function StatusBadge({ status }: { status: PayrollRunStatus }) {
  return <span className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_BADGE_STYLES[status]}`}>{status}</span>
}

const QUICK_ACTIONS: { label: string; to: string }[] = [
  { label: 'Employees', to: '/admin/employees' },
  { label: 'Leave Requests', to: '/admin/leave' },
  { label: 'Payroll', to: '/admin/payroll' },
  { label: 'Reports', to: '/admin/reports' },
]

/**
 * HR_ADMIN dashboard (Phase 11B): three fully independent sections, each built on an existing
 * secured domain endpoint with its own loading/error/data state and its own effect, so one failed
 * request never hides another. Every figure is the backend's authoritative value -- this page never
 * sums, counts, sorts, or filters rows itself.
 */
export function AdminDashboardPage() {
  const [headcount, setHeadcount] = useState<HeadcountReport | null>(null)
  const [headcountLoading, setHeadcountLoading] = useState(true)
  const [headcountError, setHeadcountError] = useState<string | null>(null)

  // Only the authoritative page.totalElements is kept -- the single over-fetched row (employee
  // name, reason, ids) is never stored or rendered.
  const [pendingLeaveTotal, setPendingLeaveTotal] = useState<number | null>(null)
  const [pendingLeaveLoading, setPendingLeaveLoading] = useState(true)
  const [pendingLeaveError, setPendingLeaveError] = useState<string | null>(null)

  const [latestRun, setLatestRun] = useState<PayrollRun | null>(null)
  const [payrollLoading, setPayrollLoading] = useState(true)
  const [payrollError, setPayrollError] = useState<string | null>(null)

  // Closure-scoped `cancelled` flags (the ReportsPage/PayrollRunListPage convention) stop a response
  // that arrives after unmount from updating state.
  useEffect(() => {
    let cancelled = false
    getHeadcountReport()
      .then((data) => {
        if (!cancelled) setHeadcount(data)
      })
      .catch(() => {
        if (!cancelled) setHeadcountError(HEADCOUNT_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setHeadcountLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    listLeaveRequests({ status: 'PENDING', page: 0, size: 1 })
      .then((data) => {
        if (!cancelled) setPendingLeaveTotal(data.page.totalElements)
      })
      .catch(() => {
        if (!cancelled) setPendingLeaveError(PENDING_LEAVE_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setPendingLeaveLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // The endpoint's established newest-first (createdAt DESC, id DESC) ordering -- the same order the
  // Payroll Runs list shows -- so the first row is the most recently created run.
  useEffect(() => {
    let cancelled = false
    listPayrollRuns({ page: 0, size: 1 })
      .then((data) => {
        if (!cancelled) setLatestRun(data.content[0] ?? null)
      })
      .catch(() => {
        if (!cancelled) setPayrollError(PAYROLL_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setPayrollLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Admin Dashboard</h1>
        <p className="mt-1 text-sm text-slate-500">
          Current workforce, leave, and payroll at a glance. Figures come directly from the system.
        </p>
      </div>

      <section
        aria-labelledby="dashboard-active-employees"
        className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <h2 id="dashboard-active-employees" className="text-sm font-semibold text-slate-900">
          Active Employees
        </h2>

        {headcountLoading && <p className="mt-3 text-sm text-slate-500">Loading active employees...</p>}
        {!headcountLoading && headcountError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {headcountError}
          </p>
        )}
        {!headcountLoading && !headcountError && headcount && (
          <>
            <dl className="mt-3">
              <dt className="text-sm text-slate-500">Total active employees</dt>
              <dd className="text-2xl font-semibold text-slate-900">{headcount.totalActiveEmployees}</dd>
            </dl>
            {headcount.departments.length === 0 ? (
              <p className="mt-3 text-sm text-slate-500">No active employees found.</p>
            ) : (
              <div className="relative mt-3 overflow-x-auto rounded-lg border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <caption className="sr-only">Active employees by department</caption>
                  <thead>
                    <tr className="text-left text-slate-500">
                      <th className="px-4 py-2">Department</th>
                      <th className="px-4 py-2">Active Employees</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {headcount.departments.map((row) => (
                      <tr key={row.departmentId}>
                        <td className="px-4 py-2">{row.departmentName}</td>
                        <td className="px-4 py-2">{row.activeEmployeeCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </section>

      <section
        aria-labelledby="dashboard-pending-leave"
        className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <h2 id="dashboard-pending-leave" className="text-sm font-semibold text-slate-900">
          Pending Leave Requests
        </h2>

        {pendingLeaveLoading && <p className="mt-3 text-sm text-slate-500">Loading pending leave requests...</p>}
        {!pendingLeaveLoading && pendingLeaveError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {pendingLeaveError}
          </p>
        )}
        {!pendingLeaveLoading && !pendingLeaveError && pendingLeaveTotal !== null && (
          <>
            <dl className="mt-3">
              <dt className="text-sm text-slate-500">Awaiting review</dt>
              <dd className="text-2xl font-semibold text-slate-900">{pendingLeaveTotal}</dd>
            </dl>
            {pendingLeaveTotal === 0 && (
              <p className="mt-3 text-sm text-slate-500">No leave requests are awaiting review.</p>
            )}
            <Link to="/admin/leave" className="mt-3 inline-block text-sm font-medium text-slate-700 hover:underline">
              Review leave requests
            </Link>
          </>
        )}
      </section>

      <section
        aria-labelledby="dashboard-latest-payroll"
        className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <h2 id="dashboard-latest-payroll" className="text-sm font-semibold text-slate-900">
          Latest Payroll Run
        </h2>

        {payrollLoading && <p className="mt-3 text-sm text-slate-500">Loading latest payroll run...</p>}
        {!payrollLoading && payrollError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {payrollError}
          </p>
        )}
        {!payrollLoading && !payrollError && !latestRun && (
          <p className="mt-3 text-sm text-slate-500">No payroll runs yet.</p>
        )}
        {!payrollLoading && !payrollError && latestRun && (
          <>
            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="text-slate-500">Period</dt>
              <dd className="text-slate-900">{formatPayrollPeriod(latestRun.periodMonth, latestRun.periodYear)}</dd>
              <dt className="text-slate-500">Status</dt>
              <dd>
                <StatusBadge status={latestRun.status} />
              </dd>
              <dt className="text-slate-500">Created</dt>
              <dd className="text-slate-900">{formatAttendanceDate(latestRun.createdAt.slice(0, 10))}</dd>
            </dl>
            <Link
              to={`/admin/payroll/${latestRun.id}`}
              className="mt-3 inline-block text-sm font-medium text-slate-700 hover:underline"
            >
              View payroll run
            </Link>
          </>
        )}
      </section>

      <section
        aria-labelledby="dashboard-quick-actions"
        className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <h2 id="dashboard-quick-actions" className="text-sm font-semibold text-slate-900">
          Quick Actions
        </h2>
        <ul className="mt-3 flex flex-wrap gap-3">
          {QUICK_ACTIONS.map((action) => (
            <li key={action.to}>
              <Link
                to={action.to}
                className="inline-block rounded-md border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                {action.label}
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
