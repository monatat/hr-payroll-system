import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listEmployees, type Employee } from '../../api/employees'
import { extractErrorMessage } from '../../api/errors'
import { listLeaveRequests, type LeaveDurationType, type LeaveRequest, type LeaveRequestStatus } from '../../api/leave'

const PAGE_SIZE = 10

// No dedicated employee-search endpoint exists for this filter (out of
// scope per the approved plan) -- reuses the existing paginated employees
// list with a page large enough to cover a typical org in one request,
// same as DepartmentsPage's full-list filter dropdown.
const EMPLOYEE_OPTIONS_PAGE_SIZE = 200

const STATUS_BADGE_STYLES: Record<LeaveRequestStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  APPROVED: 'bg-emerald-100 text-emerald-700',
  REJECTED: 'bg-red-100 text-red-700',
}

function StatusBadge({ status }: { status: LeaveRequestStatus }) {
  return (
    <span className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_BADGE_STYLES[status]}`}>{status}</span>
  )
}

const DURATION_LABELS: Record<LeaveDurationType, string> = {
  FULL_DAY: 'Full Day',
  HALF_DAY: 'Half Day',
}

export function LeaveRequestListPage() {
  const [employees, setEmployees] = useState<Employee[]>([])
  const [status, setStatus] = useState<LeaveRequestStatus | 'ALL'>('PENDING')
  const [employeeId, setEmployeeId] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const [requests, setRequests] = useState<LeaveRequest[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    listEmployees({ size: EMPLOYEE_OPTIONS_PAGE_SIZE })
      .then((result) => setEmployees(result.content))
      .catch(() => setEmployees([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    listLeaveRequests({
      status: status === 'ALL' ? undefined : status,
      employeeId: employeeId ? Number(employeeId) : undefined,
      page,
      size: PAGE_SIZE,
    })
      .then((result) => {
        if (cancelled) return
        setRequests(result.content)
        setTotalPages(result.page.totalPages)
      })
      .catch((err) => {
        if (cancelled) return
        setRequests([])
        setTotalPages(0)
        setLoadError(extractErrorMessage(err, 'Could not load leave requests.'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [status, employeeId, page])

  return (
    <section className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Leave Requests</h1>

      <div className="flex flex-wrap gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(event) => {
            setPage(0)
            setStatus(event.target.value as LeaveRequestStatus | 'ALL')
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        >
          <option value="PENDING">Pending</option>
          <option value="ALL">All</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
        <select
          aria-label="Filter by employee"
          value={employeeId}
          onChange={(event) => {
            setPage(0)
            setEmployeeId(event.target.value)
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        >
          <option value="">All employees</option>
          {employees.map((employee) => (
            <option key={employee.id} value={employee.id}>
              {employee.employeeCode} — {employee.fullName}
            </option>
          ))}
        </select>
      </div>

      {loadError && (
        <p role="alert" className="text-sm text-red-600">
          {loadError}
        </p>
      )}

      <div className="relative overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-4 py-2">Employee code</th>
              <th className="px-4 py-2">Employee name</th>
              <th className="px-4 py-2">Leave type</th>
              <th className="px-4 py-2">Start date</th>
              <th className="px-4 py-2">End date</th>
              <th className="px-4 py-2">Duration</th>
              <th className="px-4 py-2">Days</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {requests.map((request) => (
              <tr key={request.id}>
                <td className="px-4 py-2">{request.employeeCode}</td>
                <td className="px-4 py-2">{request.employeeFullName}</td>
                <td className="px-4 py-2">{request.leaveTypeName}</td>
                <td className="px-4 py-2">{request.startDate}</td>
                <td className="px-4 py-2">{request.endDate}</td>
                <td className="px-4 py-2">{DURATION_LABELS[request.durationType]}</td>
                <td className="px-4 py-2">{request.daysRequested}</td>
                <td className="px-4 py-2">
                  <StatusBadge status={request.status} />
                </td>
                <td className="px-4 py-2">
                  <Link to={`/admin/leave/${request.id}`} className="text-blue-600 hover:underline">
                    {request.status === 'PENDING' ? 'Review' : 'View'}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
        {!loading && !loadError && requests.length === 0 && (
          <p className="p-4 text-sm text-slate-500">
            {status === 'PENDING' ? 'No pending requests.' : 'No leave requests found.'}
          </p>
        )}
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <button
          type="button"
          disabled={page <= 0}
          onClick={() => setPage((current) => current - 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Previous
        </button>
        <span>
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={page + 1 >= totalPages}
          onClick={() => setPage((current) => current + 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </section>
  )
}
