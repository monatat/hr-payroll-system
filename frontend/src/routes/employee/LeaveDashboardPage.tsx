import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { extractErrorMessage } from '../../api/errors'
import {
  createLeaveRequest,
  getOwnLeaveBalances,
  listLeaveTypes,
  listOwnLeaveRequests,
  type LeaveBalance,
  type LeaveDurationType,
  type LeaveRequest,
  type LeaveRequestStatus,
  type LeaveType,
} from '../../api/leave'

const HISTORY_PAGE_SIZE = 10

const STATUS_BADGE_STYLES: Record<LeaveRequestStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  APPROVED: 'bg-emerald-100 text-emerald-700',
  REJECTED: 'bg-red-100 text-red-700',
}

// Always renders one decimal place so half-day values (e.g. 3.5) and whole
// values (e.g. 14) look consistent side by side, without ever rounding.
function formatDays(value: number): string {
  return value.toFixed(1)
}

// Parses a "YYYY-MM-DD" string as a UTC calendar date -- never via
// `new Date(str)`, which JS parses as UTC midnight but then a browser in a
// timezone behind UTC can display as the previous local day, silently
// shifting the effective day-of-week.
function parseIsoDateAsUtc(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day))
}

// User-friendly, client-side-only check -- the backend remains the
// authoritative source of truth for working-day counting (public holidays
// are out of scope on both sides).
function hasNoWeekdays(startDate: string, endDate: string): boolean {
  const cursor = parseIsoDateAsUtc(startDate)
  const end = parseIsoDateAsUtc(endDate)
  while (cursor.getTime() <= end.getTime()) {
    const dayOfWeek = cursor.getUTCDay()
    if (dayOfWeek !== 0 && dayOfWeek !== 6) return false
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return true
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

export function LeaveDashboardPage() {
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[] | null>(null)
  const [leaveTypesError, setLeaveTypesError] = useState<string | null>(null)

  const [balances, setBalances] = useState<LeaveBalance[] | null>(null)
  const [balancesError, setBalancesError] = useState<string | null>(null)

  const [requests, setRequests] = useState<LeaveRequest[] | null>(null)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const [leaveTypeId, setLeaveTypeId] = useState('')
  const [durationType, setDurationType] = useState<LeaveDurationType>('FULL_DAY')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  useEffect(() => {
    listLeaveTypes()
      .then(setLeaveTypes)
      .catch((err) => setLeaveTypesError(extractErrorMessage(err, 'Could not load leave types.')))
  }, [])

  useEffect(() => {
    getOwnLeaveBalances()
      .then(setBalances)
      .catch((err) => setBalancesError(extractErrorMessage(err, 'Could not load your leave balances.')))
  }, [])

  const loadHistory = useCallback(() => {
    setHistoryError(null)
    return listOwnLeaveRequests(page, HISTORY_PAGE_SIZE)
      .then((result) => {
        setRequests(result.content)
        setTotalPages(result.page.totalPages)
      })
      .catch((err) => setHistoryError(extractErrorMessage(err, 'Could not load your leave request history.')))
  }, [page])

  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  function handleDurationChange(nextDuration: LeaveDurationType) {
    setDurationType(nextDuration)
    if (nextDuration === 'HALF_DAY') setEndDate(startDate)
  }

  function handleStartDateChange(value: string) {
    setStartDate(value)
    if (durationType === 'HALF_DAY') setEndDate(value)
  }

  function validate(): string | null {
    if (!leaveTypeId) return 'Please select a leave type.'
    if (!startDate) return 'Start date is required.'
    if (!endDate) return 'End date is required.'
    if (durationType === 'HALF_DAY' && startDate !== endDate) {
      return 'A half day request must have identical start and end dates.'
    }
    if (endDate < startDate) return 'End date cannot be before start date.'
    if (startDate.slice(0, 4) !== endDate.slice(0, 4)) {
      return 'Start and end date must be in the same calendar year.'
    }
    if (hasNoWeekdays(startDate, endDate)) {
      return 'The selected date range contains no working days (Monday-Friday).'
    }
    return null
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSuccessMessage(null)
    const validationError = validate()
    if (validationError) {
      setSubmitError(validationError)
      return
    }
    setSubmitError(null)
    setSubmitting(true)
    try {
      await createLeaveRequest({
        leaveTypeId: Number(leaveTypeId),
        startDate,
        endDate,
        durationType,
        reason: reason.trim() === '' ? undefined : reason,
      })
      setLeaveTypeId('')
      setDurationType('FULL_DAY')
      setStartDate('')
      setEndDate('')
      setReason('')
      setSuccessMessage('Leave request submitted. It is now pending HR review.')
      setPage(0)
      await loadHistory()
    } catch (err) {
      setSubmitError(extractErrorMessage(err, 'Could not submit leave request.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-xl font-semibold text-slate-900">My Leave</h1>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Leave balance</h2>
        <p className="mt-1 text-xs text-slate-400">Leave balances are set up and maintained by HR.</p>

        {balancesError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {balancesError}
          </p>
        )}
        {!balancesError && balances === null && <p className="mt-3 text-sm text-slate-500">Loading...</p>}
        {!balancesError && balances !== null && balances.length === 0 && (
          <p className="mt-3 text-sm text-slate-500">
            You have no leave balances yet. Contact HR to have your leave entitlements set up.
          </p>
        )}
        {!balancesError && balances !== null && balances.length > 0 && (
          <table className="mt-3 w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-slate-500">
                <th className="pb-2 pr-4 font-medium">Year</th>
                <th className="pb-2 pr-4 font-medium">Leave type</th>
                <th className="pb-2 pr-4 font-medium">Entitled</th>
                <th className="pb-2 pr-4 font-medium">Used</th>
                <th className="pb-2 font-medium">Remaining</th>
              </tr>
            </thead>
            <tbody>
              {balances.map((balance) => (
                <tr key={balance.id} className="border-b border-slate-100 text-slate-900">
                  <td className="py-2 pr-4">{balance.year}</td>
                  <td className="py-2 pr-4">{balance.leaveTypeName}</td>
                  <td className="py-2 pr-4">{formatDays(balance.entitledDays)}</td>
                  <td className="py-2 pr-4">{formatDays(balance.usedDays)}</td>
                  <td className="py-2">{formatDays(balance.remainingDays)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Apply for leave</h2>
        {leaveTypesError && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {leaveTypesError}
          </p>
        )}
        <form onSubmit={handleSubmit} className="mt-4 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <label htmlFor="leaveTypeId" className="block text-sm font-medium text-slate-700">
                Leave type
              </label>
              <select
                id="leaveTypeId"
                value={leaveTypeId}
                onChange={(event) => setLeaveTypeId(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              >
                <option value="">Select a leave type</option>
                {(leaveTypes ?? []).map((leaveType) => (
                  <option key={leaveType.id} value={leaveType.id}>
                    {leaveType.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="col-span-2">
              <label htmlFor="durationType" className="block text-sm font-medium text-slate-700">
                Leave duration
              </label>
              <select
                id="durationType"
                value={durationType}
                onChange={(event) => handleDurationChange(event.target.value as LeaveDurationType)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              >
                <option value="FULL_DAY">Full Day</option>
                <option value="HALF_DAY">Half Day</option>
              </select>
            </div>
            <div>
              <label htmlFor="startDate" className="block text-sm font-medium text-slate-700">
                Start date
              </label>
              <input
                id="startDate"
                type="date"
                value={startDate}
                onChange={(event) => handleStartDateChange(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
            <div>
              <label htmlFor="endDate" className="block text-sm font-medium text-slate-700">
                End date
              </label>
              <input
                id="endDate"
                type="date"
                value={endDate}
                disabled={durationType === 'HALF_DAY'}
                onChange={(event) => setEndDate(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 disabled:opacity-50"
              />
            </div>
            <div className="col-span-2">
              <label htmlFor="reason" className="block text-sm font-medium text-slate-700">
                Reason (optional)
              </label>
              <textarea
                id="reason"
                value={reason}
                maxLength={500}
                onChange={(event) => setReason(event.target.value)}
                rows={3}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
          </div>

          {submitError && (
            <p role="alert" className="text-sm text-red-600">
              {submitError}
            </p>
          )}
          {successMessage && !submitError && <p className="text-sm text-emerald-600">{successMessage}</p>}

          <button
            type="submit"
            disabled={submitting}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {submitting ? 'Submitting...' : 'Submit request'}
          </button>
        </form>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Request history</h2>

        {historyError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {historyError}
          </p>
        )}
        {!historyError && requests === null && <p className="mt-3 text-sm text-slate-500">Loading...</p>}
        {!historyError && requests !== null && requests.length === 0 && (
          <p className="mt-3 text-sm text-slate-500">No leave requests yet.</p>
        )}
        {!historyError && requests !== null && requests.length > 0 && (
          <table className="mt-3 w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-slate-500">
                <th className="pb-2 pr-4 font-medium">Leave type</th>
                <th className="pb-2 pr-4 font-medium">Start</th>
                <th className="pb-2 pr-4 font-medium">End</th>
                <th className="pb-2 pr-4 font-medium">Duration</th>
                <th className="pb-2 pr-4 font-medium">Days</th>
                <th className="pb-2 pr-4 font-medium">Status</th>
                <th className="pb-2 font-medium">Reason</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((request) => (
                <tr key={request.id} className="border-b border-slate-100 align-top text-slate-900">
                  <td className="py-2 pr-4">{request.leaveTypeName}</td>
                  <td className="py-2 pr-4">{request.startDate}</td>
                  <td className="py-2 pr-4">{request.endDate}</td>
                  <td className="py-2 pr-4">{DURATION_LABELS[request.durationType]}</td>
                  <td className="py-2 pr-4">{formatDays(request.daysRequested)}</td>
                  <td className="py-2 pr-4">
                    <StatusBadge status={request.status} />
                  </td>
                  <td className="py-2 text-slate-600">
                    {request.reason && <p>{request.reason}</p>}
                    {request.status === 'REJECTED' && request.rejectionReason && (
                      <p className="mt-1 text-red-600">
                        Rejection reason: <span>{request.rejectionReason}</span>
                      </p>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="mt-4 flex items-center justify-between text-sm text-slate-600">
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
      </div>
    </section>
  )
}
