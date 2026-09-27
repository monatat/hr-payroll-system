import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getTodayAttendance, type AttendanceTodayResponse } from '../../api/attendance'
import { getOwnProfile, type Employee } from '../../api/employees'
import {
  getOwnLeaveBalances,
  listOwnLeaveRequests,
  type LeaveBalance,
  type LeaveRequest,
  type LeaveRequestStatus,
} from '../../api/leave'
import { listOwnPayslips, type Payslip } from '../../api/payroll'
import { formatAttendanceDate, formatAttendanceTime } from '../../utils/attendanceDateTime'
import { formatMYR } from '../../utils/currency'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const RECENT_LEAVE_PAGE_SIZE = 5

// Fixed, safe messages -- the real error (which may carry backend/internal detail) is never shown.
const ATTENDANCE_LOAD_ERROR = "Could not load today's attendance. Please try again."
const BALANCES_LOAD_ERROR = 'Could not load leave balances. Please try again.'
const RECENT_LEAVE_LOAD_ERROR = 'Could not load recent leave requests. Please try again.'
const PAYSLIP_LOAD_ERROR = 'Could not load latest payslip. Please try again.'
const PROFILE_LOAD_ERROR = 'Could not load profile. Please try again.'

// Same leave-status badge convention as LeaveDashboardPage: the status is always visible text,
// with color only as a secondary cue.
const LEAVE_STATUS_BADGE_STYLES: Record<LeaveRequestStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  APPROVED: 'bg-emerald-100 text-emerald-700',
  REJECTED: 'bg-red-100 text-red-700',
}

function LeaveStatusBadge({ status }: { status: LeaveRequestStatus }) {
  return (
    <span className={`rounded-full px-3 py-1 text-xs font-medium ${LEAVE_STATUS_BADGE_STYLES[status]}`}>{status}</span>
  )
}

// Same one-decimal display as LeaveDashboardPage's formatDays -- formatting only, never arithmetic.
function formatDays(value: number): string {
  return value.toFixed(1)
}

const QUICK_ACTIONS: { label: string; to: string }[] = [
  { label: 'My Profile', to: '/employee/profile' },
  { label: 'Attendance', to: '/employee/attendance' },
  { label: 'My Leave', to: '/employee/leave' },
  { label: 'My Payslips', to: '/employee/payslips' },
  { label: 'Salary History', to: '/employee/salary' },
]

const CARD_CLASS = 'rounded-lg border border-slate-200 bg-white p-6 shadow-sm'
const LINK_CLASS = 'mt-3 inline-block text-sm font-medium text-slate-700 hover:underline'

/**
 * EMPLOYEE dashboard (Phase 11C): five fully independent read-only sections, each built on an
 * existing self-service endpoint whose identity the backend derives from the caller's JWT -- no
 * function here accepts or sends an employee/user id. Each section has its own request, effect, and
 * loading/error/data state, so one failed request never hides another. Every value is rendered as
 * the backend returned it; nothing is summed, counted, sorted, filtered, or recomputed here.
 */
export function EmployeeDashboardPage() {
  const [attendance, setAttendance] = useState<AttendanceTodayResponse | null>(null)
  const [attendanceLoading, setAttendanceLoading] = useState(true)
  const [attendanceError, setAttendanceError] = useState<string | null>(null)

  const [balances, setBalances] = useState<LeaveBalance[] | null>(null)
  const [balancesLoading, setBalancesLoading] = useState(true)
  const [balancesError, setBalancesError] = useState<string | null>(null)

  const [recentLeave, setRecentLeave] = useState<LeaveRequest[] | null>(null)
  const [recentLeaveLoading, setRecentLeaveLoading] = useState(true)
  const [recentLeaveError, setRecentLeaveError] = useState<string | null>(null)

  // `undefined` = not loaded yet; `null` = loaded, no payslip exists.
  const [latestPayslip, setLatestPayslip] = useState<Payslip | null | undefined>(undefined)
  const [payslipLoading, setPayslipLoading] = useState(true)
  const [payslipError, setPayslipError] = useState<string | null>(null)

  const [profile, setProfile] = useState<Employee | null>(null)
  const [profileLoading, setProfileLoading] = useState(true)
  const [profileError, setProfileError] = useState<string | null>(null)

  // Each effect uses the project's closure-scoped `cancelled` flag so a response arriving after
  // unmount never updates state.
  useEffect(() => {
    let cancelled = false
    getTodayAttendance()
      .then((data) => {
        if (!cancelled) setAttendance(data)
      })
      .catch(() => {
        if (!cancelled) setAttendanceError(ATTENDANCE_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setAttendanceLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    getOwnLeaveBalances()
      .then((data) => {
        if (!cancelled) setBalances(data)
      })
      .catch(() => {
        if (!cancelled) setBalancesError(BALANCES_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setBalancesLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    listOwnLeaveRequests(0, RECENT_LEAVE_PAGE_SIZE)
      .then((data) => {
        if (!cancelled) setRecentLeave(data.content)
      })
      .catch(() => {
        if (!cancelled) setRecentLeaveError(RECENT_LEAVE_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setRecentLeaveLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // The endpoint's established newest-first ordering (the same order My Payslips shows), so the
  // first row is the most recent payslip.
  useEffect(() => {
    let cancelled = false
    listOwnPayslips(0, 1)
      .then((data) => {
        if (!cancelled) setLatestPayslip(data.content[0] ?? null)
      })
      .catch(() => {
        if (!cancelled) setPayslipError(PAYSLIP_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setPayslipLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    getOwnProfile()
      .then((data) => {
        if (!cancelled) setProfile(data)
      })
      .catch(() => {
        if (!cancelled) setProfileError(PROFILE_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setProfileLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const attendanceRecord = attendance?.attendance ?? null

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Employee Dashboard</h1>
        <p className="mt-1 text-sm text-slate-500">Your attendance, leave, pay, and profile at a glance.</p>
      </div>

      <section aria-labelledby="dashboard-attendance" className={CARD_CLASS}>
        <h2 id="dashboard-attendance" className="text-sm font-semibold text-slate-900">
          Today&apos;s Attendance
        </h2>

        {attendanceLoading && <p className="mt-3 text-sm text-slate-500">Loading today&apos;s attendance...</p>}
        {!attendanceLoading && attendanceError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {attendanceError}
          </p>
        )}
        {!attendanceLoading && !attendanceError && attendance && (
          <>
            {attendance.state === 'NOT_CLOCKED_IN' && <p className="mt-3 text-sm text-slate-700">Not clocked in today</p>}
            {attendance.state !== 'NOT_CLOCKED_IN' && attendanceRecord && (
              <div className="mt-3 space-y-1 text-sm text-slate-700">
                <p>{attendance.state === 'CLOCKED_IN' ? 'Clocked in' : 'Clocked out'}</p>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
                  <dt className="text-slate-500">Date</dt>
                  <dd>{formatAttendanceDate(attendanceRecord.workDate)}</dd>
                  <dt className="text-slate-500">Clock In</dt>
                  <dd>{formatAttendanceTime(attendanceRecord.clockInAt)}</dd>
                  <dt className="text-slate-500">Clock Out</dt>
                  <dd>{formatAttendanceTime(attendanceRecord.clockOutAt)}</dd>
                </dl>
              </div>
            )}
            <Link to="/employee/attendance" className={LINK_CLASS}>
              Go to attendance
            </Link>
          </>
        )}
      </section>

      <section aria-labelledby="dashboard-leave-balances" className={CARD_CLASS}>
        <h2 id="dashboard-leave-balances" className="text-sm font-semibold text-slate-900">
          Leave Balances
        </h2>

        {balancesLoading && <p className="mt-3 text-sm text-slate-500">Loading leave balances...</p>}
        {!balancesLoading && balancesError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {balancesError}
          </p>
        )}
        {!balancesLoading && !balancesError && balances && (
          <>
            {balances.length === 0 ? (
              <p className="mt-3 text-sm text-slate-500">No leave balances found.</p>
            ) : (
              <div className="relative mt-3 overflow-x-auto rounded-lg border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <caption className="sr-only">My leave balances</caption>
                  <thead>
                    <tr className="text-left text-slate-500">
                      <th className="px-4 py-2">Year</th>
                      <th className="px-4 py-2">Leave Type</th>
                      <th className="px-4 py-2">Entitled</th>
                      <th className="px-4 py-2">Used</th>
                      <th className="px-4 py-2">Remaining</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {balances.map((balance) => (
                      <tr key={balance.id}>
                        <td className="px-4 py-2">{balance.year}</td>
                        <td className="px-4 py-2">{balance.leaveTypeName}</td>
                        <td className="px-4 py-2">{formatDays(balance.entitledDays)}</td>
                        <td className="px-4 py-2">{formatDays(balance.usedDays)}</td>
                        <td className="px-4 py-2">{formatDays(balance.remainingDays)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <Link to="/employee/leave" className={LINK_CLASS}>
              Manage leave
            </Link>
          </>
        )}
      </section>

      <section aria-labelledby="dashboard-recent-leave" className={CARD_CLASS}>
        <h2 id="dashboard-recent-leave" className="text-sm font-semibold text-slate-900">
          Recent Leave Requests
        </h2>

        {recentLeaveLoading && <p className="mt-3 text-sm text-slate-500">Loading recent leave requests...</p>}
        {!recentLeaveLoading && recentLeaveError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {recentLeaveError}
          </p>
        )}
        {!recentLeaveLoading && !recentLeaveError && recentLeave && (
          <>
            {recentLeave.length === 0 ? (
              <p className="mt-3 text-sm text-slate-500">No leave requests yet.</p>
            ) : (
              <div className="relative mt-3 overflow-x-auto rounded-lg border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <caption className="sr-only">My recent leave requests</caption>
                  <thead>
                    <tr className="text-left text-slate-500">
                      <th className="px-4 py-2">Leave Type</th>
                      <th className="px-4 py-2">Dates</th>
                      <th className="px-4 py-2">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {recentLeave.map((request) => (
                      <tr key={request.id}>
                        <td className="px-4 py-2">{request.leaveTypeName}</td>
                        <td className="px-4 py-2">
                          {formatAttendanceDate(request.startDate)} – {formatAttendanceDate(request.endDate)}
                        </td>
                        <td className="px-4 py-2">
                          <LeaveStatusBadge status={request.status} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <Link to="/employee/leave" className={LINK_CLASS}>
              View all leave requests
            </Link>
          </>
        )}
      </section>

      <section aria-labelledby="dashboard-latest-payslip" className={CARD_CLASS}>
        <h2 id="dashboard-latest-payslip" className="text-sm font-semibold text-slate-900">
          Latest Payslip
        </h2>

        {payslipLoading && <p className="mt-3 text-sm text-slate-500">Loading latest payslip...</p>}
        {!payslipLoading && payslipError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {payslipError}
          </p>
        )}
        {!payslipLoading && !payslipError && latestPayslip === null && (
          <p className="mt-3 text-sm text-slate-500">No payslips yet.</p>
        )}
        {!payslipLoading && !payslipError && latestPayslip && (
          <>
            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="text-slate-500">Period</dt>
              <dd className="text-slate-900">{formatPayrollPeriod(latestPayslip.periodMonth, latestPayslip.periodYear)}</dd>
              <dt className="text-slate-500">Net pay</dt>
              <dd className="text-slate-900">{formatMYR(latestPayslip.netPay)}</dd>
            </dl>
            <Link to={`/employee/payslips/${latestPayslip.id}`} className={LINK_CLASS}>
              View payslip
            </Link>
          </>
        )}
      </section>

      <section aria-labelledby="dashboard-profile" className={CARD_CLASS}>
        <h2 id="dashboard-profile" className="text-sm font-semibold text-slate-900">
          My Profile
        </h2>

        {profileLoading && <p className="mt-3 text-sm text-slate-500">Loading profile...</p>}
        {!profileLoading && profileError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {profileError}
          </p>
        )}
        {!profileLoading && !profileError && profile && (
          <>
            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="text-slate-500">Name</dt>
              <dd className="text-slate-900">{profile.fullName}</dd>
              <dt className="text-slate-500">Employee code</dt>
              <dd className="text-slate-900">{profile.employeeCode}</dd>
              <dt className="text-slate-500">Department</dt>
              <dd className="text-slate-900">{profile.departmentName}</dd>
              <dt className="text-slate-500">Job title</dt>
              <dd className="text-slate-900">{profile.jobTitle}</dd>
              <dt className="text-slate-500">Hire date</dt>
              <dd className="text-slate-900">{formatAttendanceDate(profile.hireDate)}</dd>
            </dl>
            <Link to="/employee/profile" className={LINK_CLASS}>
              View full profile
            </Link>
          </>
        )}
      </section>

      <section aria-labelledby="dashboard-quick-actions" className={CARD_CLASS}>
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
