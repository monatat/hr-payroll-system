import { useEffect, useState } from 'react'
import {
  clockIn,
  clockOut,
  getTodayAttendance,
  listOwnAttendance,
  type AttendanceRecord,
  type AttendanceTodayResponse,
} from '../../api/attendance'
import { extractErrorCode } from '../../api/errors'
import { formatAttendanceDate, formatAttendanceTime } from '../../utils/attendanceDateTime'

const PAGE_SIZE = 20
const TODAY_LOAD_ERROR = 'Could not load today’s attendance. Please try again.'
const HISTORY_LOAD_ERROR = 'Could not load attendance history. Please try again.'
const GENERIC_ACTION_ERROR = 'Could not update attendance. Please try again.'

// Every code this page can recognize maps to the exact safe text the backend documents for it --
// used only as the fallback when the backend's own `detail` is absent or blank, matching
// PayrollRunDetailPage/PayslipDetailPage's established "present and non-empty" convention.
const ACTION_FALLBACKS: Record<string, string> = {
  ATTENDANCE_ALREADY_CLOCKED_IN: 'You have already clocked in today.',
  ATTENDANCE_NO_OPEN_RECORD: 'You have not clocked in today.',
  ATTENDANCE_ALREADY_CLOCKED_OUT: 'You have already clocked out today.',
}

type MutationMode = 'idle' | 'clockin-confirm' | 'clockout-confirm'

function safeActionMessage(err: unknown): string {
  const code = extractErrorCode(err)
  if (!code || !(code in ACTION_FALLBACKS)) return GENERIC_ACTION_ERROR
  const detail =
    typeof err === 'object' && err !== null && 'response' in err
      ? (err as { response?: { data?: { detail?: string } } }).response?.data?.detail
      : undefined
  return detail && detail.trim() !== '' ? detail : ACTION_FALLBACKS[code]
}

/**
 * EMPLOYEE-only self-service attendance: current-day clock-in/clock-out and personal history.
 * Identity comes only from the JWT via the Phase 9C `attendance` API module (never an
 * employee-id parameter, never apiClient/axios directly, never a client-side ownership check).
 * Today's state is always read from `getTodayAttendance()` -- this page never derives "today"
 * from history, since a stale prior-day open row would make that ambiguous.
 */
export function AttendancePage() {
  const [today, setToday] = useState<AttendanceTodayResponse | null>(null)
  const [todayLoading, setTodayLoading] = useState(true)
  const [todayError, setTodayError] = useState<string | null>(null)

  const [history, setHistory] = useState<AttendanceRecord[]>([])
  const [historyPage, setHistoryPage] = useState(0)
  const [historyTotalPages, setHistoryTotalPages] = useState(0)
  const [historyLoading, setHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState<string | null>(null)

  const [refreshToken, setRefreshToken] = useState(0)

  const [mutationMode, setMutationMode] = useState<MutationMode>('idle')
  const [clockingIn, setClockingIn] = useState(false)
  const [clockInError, setClockInError] = useState<string | null>(null)
  const [clockingOut, setClockingOut] = useState(false)
  const [clockOutError, setClockOutError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setTodayLoading(true)
    setTodayError(null)
    getTodayAttendance()
      .then((data) => {
        if (!cancelled) setToday(data)
      })
      .catch(() => {
        if (!cancelled) setTodayError(TODAY_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setTodayLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [refreshToken])

  useEffect(() => {
    let cancelled = false
    setHistoryLoading(true)
    setHistoryError(null)
    listOwnAttendance(historyPage, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return
        const total = result.page.totalPages
        if (total > 0 && historyPage >= total) {
          setHistoryPage(total - 1)
          return
        }
        setHistory(result.content)
        setHistoryTotalPages(total)
      })
      .catch(() => {
        if (cancelled) return
        setHistory([])
        setHistoryError(HISTORY_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [historyPage, refreshToken])

  function goToHistoryPage(next: number) {
    if (historyLoading) return
    setHistoryPage(next)
  }

  function openClockInConfirm() {
    setClockInError(null)
    setSuccessMessage(null)
    setMutationMode('clockin-confirm')
  }

  function openClockOutConfirm() {
    setClockOutError(null)
    setSuccessMessage(null)
    setMutationMode('clockout-confirm')
  }

  function cancelConfirm() {
    setMutationMode('idle')
  }

  async function confirmClockIn() {
    if (clockingIn) return
    setClockInError(null)
    setClockingIn(true)
    try {
      await clockIn()
      setSuccessMessage('Clocked in successfully.')
      setMutationMode('idle')
      setRefreshToken((token) => token + 1)
    } catch (err) {
      setClockInError(safeActionMessage(err))
    } finally {
      setClockingIn(false)
    }
  }

  async function confirmClockOut() {
    if (clockingOut) return
    setClockOutError(null)
    setClockingOut(true)
    try {
      await clockOut()
      setSuccessMessage('Clocked out successfully.')
      setMutationMode('idle')
      setRefreshToken((token) => token + 1)
    } catch (err) {
      setClockOutError(safeActionMessage(err))
    } finally {
      setClockingOut(false)
    }
  }

  const state = today?.state ?? null
  const record = today?.attendance ?? null

  return (
    <section className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-xl font-semibold text-slate-900">My Attendance</h1>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Today</h2>

        {todayLoading && <p className="mt-2 text-sm text-slate-500">Loading...</p>}
        {todayError && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {todayError}
          </p>
        )}

        {!todayLoading && state === 'NOT_CLOCKED_IN' && <p className="mt-2 text-sm text-slate-700">Not clocked in today</p>}
        {!todayLoading && state === 'CLOCKED_IN' && record && (
          <div className="mt-2 space-y-1 text-sm text-slate-700">
            <p>Clocked in</p>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
              <dt className="text-slate-500">Date</dt>
              <dd>{formatAttendanceDate(record.workDate)}</dd>
              <dt className="text-slate-500">Clock In</dt>
              <dd>{formatAttendanceTime(record.clockInAt)}</dd>
              <dt className="text-slate-500">Clock Out</dt>
              <dd>{formatAttendanceTime(record.clockOutAt)}</dd>
            </dl>
          </div>
        )}
        {!todayLoading && state === 'CLOCKED_OUT' && record && (
          <div className="mt-2 space-y-1 text-sm text-slate-700">
            <p>Clocked out</p>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
              <dt className="text-slate-500">Date</dt>
              <dd>{formatAttendanceDate(record.workDate)}</dd>
              <dt className="text-slate-500">Clock In</dt>
              <dd>{formatAttendanceTime(record.clockInAt)}</dd>
              <dt className="text-slate-500">Clock Out</dt>
              <dd>{formatAttendanceTime(record.clockOutAt)}</dd>
            </dl>
          </div>
        )}

        {successMessage && <p className="mt-3 text-sm text-emerald-600">{successMessage}</p>}

        {mutationMode === 'idle' && state === 'NOT_CLOCKED_IN' && (
          <div className="mt-4">
            <button
              type="button"
              onClick={openClockInConfirm}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Clock In
            </button>
          </div>
        )}
        {mutationMode === 'idle' && state === 'CLOCKED_IN' && (
          <div className="mt-4">
            <button
              type="button"
              onClick={openClockOutConfirm}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Clock Out
            </button>
          </div>
        )}

        {mutationMode === 'clockin-confirm' && (
          <div className="mt-4 space-y-3 rounded-md border border-slate-200 p-4">
            <p className="text-sm text-slate-700">Clock in now?</p>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={confirmClockIn}
                disabled={clockingIn}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {clockingIn ? 'Clocking in...' : 'Confirm clock in'}
              </button>
              <button
                type="button"
                onClick={cancelConfirm}
                disabled={clockingIn}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
            {clockInError && (
              <p role="alert" className="text-sm text-red-600">
                {clockInError}
              </p>
            )}
          </div>
        )}

        {mutationMode === 'clockout-confirm' && (
          <div className="mt-4 space-y-3 rounded-md border border-slate-200 p-4">
            <p className="text-sm text-slate-700">Clock out now?</p>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={confirmClockOut}
                disabled={clockingOut}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {clockingOut ? 'Clocking out...' : 'Confirm clock out'}
              </button>
              <button
                type="button"
                onClick={cancelConfirm}
                disabled={clockingOut}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
            {clockOutError && (
              <p role="alert" className="text-sm text-red-600">
                {clockOutError}
              </p>
            )}
          </div>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">History</h2>

        {historyError && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {historyError}
          </p>
        )}

        <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <caption className="sr-only">My attendance history</caption>
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-4 py-2">Date</th>
                <th className="px-4 py-2">Clock In</th>
                <th className="px-4 py-2">Clock Out</th>
                <th className="px-4 py-2">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {history.map((row) => (
                <tr key={row.id}>
                  <td className="px-4 py-2">{formatAttendanceDate(row.workDate)}</td>
                  <td className="px-4 py-2">{formatAttendanceTime(row.clockInAt)}</td>
                  <td className="px-4 py-2">{formatAttendanceTime(row.clockOutAt)}</td>
                  <td className="px-4 py-2">{row.clockOutAt === null ? 'Open' : 'Completed'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {historyLoading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <p className="p-4 text-sm text-slate-500">No attendance history yet.</p>
          )}
        </div>

        <div className="mt-4 flex items-center justify-between text-sm text-slate-600">
          <button
            type="button"
            disabled={historyLoading || historyPage <= 0}
            onClick={() => goToHistoryPage(historyPage - 1)}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Previous
          </button>
          <span>
            Page {historyPage + 1} of {Math.max(historyTotalPages, 1)}
          </span>
          <button
            type="button"
            disabled={historyLoading || historyPage + 1 >= historyTotalPages}
            onClick={() => goToHistoryPage(historyPage + 1)}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      </div>
    </section>
  )
}
