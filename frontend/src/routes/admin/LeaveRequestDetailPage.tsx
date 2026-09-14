import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { extractErrorCode, extractErrorMessage } from '../../api/errors'
import {
  approveLeaveRequest,
  getEmployeeLeaveBalances,
  getLeaveRequest,
  rejectLeaveRequest,
  type LeaveBalance,
  type LeaveDurationType,
  type LeaveRequest,
  type LeaveRequestStatus,
} from '../../api/leave'

const ALREADY_PROCESSED_CODE = 'LEAVE_REQUEST_ALREADY_PROCESSED'

type ReviewMode = 'idle' | 'approve-confirm' | 'reject-form'

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

export function LeaveRequestDetailPage() {
  const { id } = useParams<{ id: string }>()
  const requestId = Number(id)

  const [request, setRequest] = useState<LeaveRequest | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [balances, setBalances] = useState<LeaveBalance[] | null>(null)
  const [balanceError, setBalanceError] = useState<string | null>(null)

  const [mode, setMode] = useState<ReviewMode>('idle')
  const [approving, setApproving] = useState(false)
  const [approveError, setApproveError] = useState<string | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [rejecting, setRejecting] = useState(false)
  const [rejectError, setRejectError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  async function loadRequest(): Promise<LeaveRequest | null> {
    try {
      const data = await getLeaveRequest(requestId)
      setRequest(data)
      return data
    } catch (err) {
      setLoadError(extractErrorMessage(err, 'Could not load leave request.'))
      return null
    }
  }

  useEffect(() => {
    loadRequest()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestId])

  async function loadBalances(employeeId: number) {
    setBalanceError(null)
    try {
      setBalances(await getEmployeeLeaveBalances(employeeId))
    } catch (err) {
      setBalanceError(extractErrorMessage(err, 'Could not load leave balances.'))
    }
  }

  useEffect(() => {
    if (request) loadBalances(request.employeeId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request?.employeeId])

  async function handleApproveConfirm() {
    if (!request || approving) return
    setApproveError(null)
    setApproving(true)
    try {
      const updated = await approveLeaveRequest(requestId)
      setRequest(updated)
      setSuccessMessage('Leave request approved.')
      setMode('idle')
      await loadBalances(updated.employeeId)
    } catch (err) {
      setApproveError(extractErrorMessage(err, 'Could not approve leave request.'))
      if (extractErrorCode(err) === ALREADY_PROCESSED_CODE) {
        setMode('idle')
        // The employeeId is normally unchanged by this refetch, so the
        // balances effect (keyed on request?.employeeId) will not re-run --
        // the winning admin's decision must be reflected explicitly here.
        const latest = await loadRequest()
        if (latest) await loadBalances(latest.employeeId)
      }
    } finally {
      setApproving(false)
    }
  }

  async function handleRejectConfirm() {
    if (!request || rejecting) return
    setRejectError(null)
    setRejecting(true)
    try {
      const updated = await rejectLeaveRequest(requestId, rejectReason.trim() === '' ? undefined : rejectReason)
      setRequest(updated)
      setSuccessMessage('Leave request rejected.')
      setMode('idle')
      setRejectReason('')
    } catch (err) {
      setRejectError(extractErrorMessage(err, 'Could not reject leave request.'))
      if (extractErrorCode(err) === ALREADY_PROCESSED_CODE) {
        setMode('idle')
        // See the matching comment in handleApproveConfirm -- the balances
        // effect will not re-run on its own here, so refetch explicitly.
        const latest = await loadRequest()
        if (latest) await loadBalances(latest.employeeId)
      }
    } finally {
      setRejecting(false)
    }
  }

  if (loadError) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError}
      </p>
    )
  }

  if (!request) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  const requestYear = Number(request.startDate.slice(0, 4))
  const matchingBalance =
    balances?.find((balance) => balance.leaveTypeId === request.leaveTypeId && balance.year === requestYear) ?? null

  return (
    <section className="mx-auto max-w-xl space-y-6">
      <div>
        <Link to="/admin/leave" className="text-sm text-slate-500 hover:underline">
          &larr; Back to leave requests
        </Link>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">{request.employeeFullName}</h1>
            <p className="text-sm text-slate-500">{request.employeeCode}</p>
          </div>
          <StatusBadge status={request.status} />
        </div>

        <p className="mt-2 text-sm text-slate-700">Duration: {DURATION_LABELS[request.durationType]}</p>

        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">Leave type</dt>
          <dd className="text-slate-900">{request.leaveTypeName}</dd>
          <dt className="text-slate-500">Start date</dt>
          <dd className="text-slate-900">{request.startDate}</dd>
          <dt className="text-slate-500">End date</dt>
          <dd className="text-slate-900">{request.endDate}</dd>
          <dt className="text-slate-500">Days requested</dt>
          <dd className="text-slate-900">{request.daysRequested}</dd>
          {request.reason && (
            <>
              <dt className="text-slate-500">Reason</dt>
              <dd className="text-slate-900">{request.reason}</dd>
            </>
          )}
          {request.reviewedByUserId != null && (
            <>
              <dt className="text-slate-500">Reviewed by (user ID)</dt>
              <dd className="text-slate-900">{request.reviewedByUserId}</dd>
            </>
          )}
          {request.reviewedAt && (
            <>
              <dt className="text-slate-500">Reviewed at</dt>
              <dd className="text-slate-900">{request.reviewedAt}</dd>
            </>
          )}
          {request.rejectionReason && (
            <>
              <dt className="text-slate-500">Rejection reason</dt>
              <dd className="text-red-600">{request.rejectionReason}</dd>
            </>
          )}
        </dl>

        {request.status === 'PENDING' && mode === 'idle' && (
          <div className="mt-6 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => {
                setSuccessMessage(null)
                setMode('approve-confirm')
              }}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Approve
            </button>
            <button
              type="button"
              onClick={() => {
                setSuccessMessage(null)
                setMode('reject-form')
              }}
              className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Reject
            </button>
          </div>
        )}

        {mode === 'approve-confirm' && (
          <div className="mt-6 space-y-3 rounded-md border border-slate-200 p-4">
            <p className="text-sm text-slate-700">Approve this leave request?</p>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={handleApproveConfirm}
                disabled={approving}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {approving ? 'Approving...' : 'Confirm approve'}
              </button>
              <button
                type="button"
                onClick={() => setMode('idle')}
                disabled={approving}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {mode === 'reject-form' && (
          <div className="mt-6 space-y-3 rounded-md border border-slate-200 p-4">
            <label htmlFor="rejectionReason" className="block text-sm font-medium text-slate-700">
              Reason (optional)
            </label>
            <textarea
              id="rejectionReason"
              value={rejectReason}
              maxLength={500}
              onChange={(event) => setRejectReason(event.target.value)}
              rows={3}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
            />
            <div className="flex gap-3">
              <button
                type="button"
                onClick={handleRejectConfirm}
                disabled={rejecting}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                {rejecting ? 'Rejecting...' : 'Confirm reject'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setMode('idle')
                  setRejectReason('')
                  setRejectError(null)
                }}
                disabled={rejecting}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {approveError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {approveError}
          </p>
        )}
        {rejectError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {rejectError}
          </p>
        )}
        {successMessage && !approveError && !rejectError && (
          <p className="mt-3 text-sm text-emerald-600">{successMessage}</p>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Leave balance context</h2>

        {balanceError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {balanceError}
          </p>
        )}
        {!balanceError && balances === null && <p className="mt-3 text-sm text-slate-500">Loading...</p>}
        {!balanceError && balances !== null && matchingBalance && (
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-slate-500">Year</dt>
            <dd className="text-slate-900">{matchingBalance.year}</dd>
            <dt className="text-slate-500">Entitled days</dt>
            <dd className="text-slate-900">{matchingBalance.entitledDays}</dd>
            <dt className="text-slate-500">Used days</dt>
            <dd className="text-slate-900">{matchingBalance.usedDays}</dd>
            <dt className="text-slate-500">Remaining days</dt>
            <dd className="text-slate-900">{matchingBalance.remainingDays}</dd>
          </dl>
        )}
        {!balanceError && balances !== null && !matchingBalance && (
          <p className="mt-3 text-sm text-amber-600">
            No leave balance has been provisioned for this employee's {request.leaveTypeName} entitlement in{' '}
            {requestYear} -- approving this request will fail until HR sets one up.
          </p>
        )}
      </div>
    </section>
  )
}
