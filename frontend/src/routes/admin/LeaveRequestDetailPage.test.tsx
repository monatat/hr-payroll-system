import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as leaveApi from '../../api/leave'
import { LeaveRequestDetailPage } from './LeaveRequestDetailPage'

vi.mock('../../api/leave')

function renderPage(id = '1') {
  return render(
    <MemoryRouter initialEntries={[`/admin/leave/${id}`]}>
      <Routes>
        <Route path="/admin/leave/:id" element={<LeaveRequestDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function leaveRequest(overrides: Partial<leaveApi.LeaveRequest> = {}): leaveApi.LeaveRequest {
  return {
    id: 1,
    employeeId: 7,
    employeeCode: 'E7',
    employeeFullName: 'Ada Lovelace',
    leaveTypeId: 1,
    leaveTypeName: 'Annual Leave',
    startDate: '2026-09-07',
    endDate: '2026-09-11',
    daysRequested: 5,
    durationType: 'FULL_DAY',
    status: 'PENDING',
    reason: 'Family trip',
    reviewedByUserId: null,
    reviewedAt: null,
    rejectionReason: null,
    ...overrides,
  }
}

function balance(overrides: Partial<leaveApi.LeaveBalance> = {}): leaveApi.LeaveBalance {
  return {
    id: 1,
    employeeId: 7,
    leaveTypeId: 1,
    leaveTypeName: 'Annual Leave',
    year: 2026,
    entitledDays: 14,
    usedDays: 4,
    remainingDays: 10,
    ...overrides,
  }
}

function alreadyProcessedError() {
  return {
    response: {
      data: {
        code: 'LEAVE_REQUEST_ALREADY_PROCESSED',
        detail: 'This leave request has already been processed and can no longer be approved or rejected.',
      },
    },
  }
}

describe('LeaveRequestDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(leaveApi.getLeaveRequest).mockResolvedValue(leaveRequest())
    vi.mocked(leaveApi.getEmployeeLeaveBalances).mockResolvedValue([balance()])
  })

  it('displays complete request information', async () => {
    renderPage()

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument()
    expect(screen.getByText('E7')).toBeInTheDocument()
    expect(screen.getByText('Annual Leave')).toBeInTheDocument()
    expect(screen.getByText('2026-09-07')).toBeInTheDocument()
    expect(screen.getByText('2026-09-11')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('PENDING')).toBeInTheDocument()
    expect(screen.getByText('Family trip')).toBeInTheDocument()
  })

  it('displays "Duration: Full Day" for a FULL_DAY request', async () => {
    renderPage()

    expect(await screen.findByText('Duration: Full Day')).toBeInTheDocument()
  })

  it('displays "Duration: Half Day" for a HALF_DAY request', async () => {
    vi.mocked(leaveApi.getLeaveRequest).mockResolvedValue(leaveRequest({ durationType: 'HALF_DAY', daysRequested: 0.5 }))

    renderPage()

    expect(await screen.findByText('Duration: Half Day')).toBeInTheDocument()
  })

  it('shows reviewed-by, reviewed-at, and rejection reason when present', async () => {
    vi.mocked(leaveApi.getLeaveRequest).mockResolvedValue(
      leaveRequest({
        status: 'REJECTED',
        reviewedByUserId: 3,
        reviewedAt: '2026-09-08T10:00:00',
        rejectionReason: 'Short staffed that week',
      }),
    )

    renderPage()

    expect(await screen.findByText(/3/)).toBeInTheDocument()
    expect(screen.getByText(/2026-09-08/)).toBeInTheDocument()
    expect(screen.getByText('Short staffed that week')).toBeInTheDocument()
  })

  it('displays matching balance context by leaveTypeId and year', async () => {
    renderPage()

    expect(await screen.findByText('14')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('2026')).toBeInTheDocument()
  })

  it('shows a warning when no matching balance exists', async () => {
    vi.mocked(leaveApi.getEmployeeLeaveBalances).mockResolvedValue([
      balance({ leaveTypeId: 99, year: 2025 }),
    ])

    renderPage()

    expect(await screen.findByText(/no leave balance has been provisioned/i)).toBeInTheDocument()
  })

  it('shows Approve and Reject only for PENDING requests', async () => {
    renderPage()

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('shows no active review controls for APPROVED requests', async () => {
    vi.mocked(leaveApi.getLeaveRequest).mockResolvedValue(leaveRequest({ status: 'APPROVED' }))

    renderPage()

    await screen.findByText('Ada Lovelace')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('shows no active review controls for REJECTED requests', async () => {
    vi.mocked(leaveApi.getLeaveRequest).mockResolvedValue(leaveRequest({ status: 'REJECTED' }))

    renderPage()

    await screen.findByText('Ada Lovelace')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('requires confirmation before approving, then updates status and refreshes balances', async () => {
    vi.mocked(leaveApi.approveLeaveRequest).mockResolvedValue(leaveRequest({ status: 'APPROVED' }))
    vi.mocked(leaveApi.getEmployeeLeaveBalances).mockResolvedValueOnce([balance()]).mockResolvedValueOnce([
      balance({ usedDays: 9, remainingDays: 5 }),
    ])
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Approve' })

    await user.click(screen.getByRole('button', { name: 'Approve' }))
    expect(leaveApi.approveLeaveRequest).not.toHaveBeenCalled()
    const confirmButton = await screen.findByRole('button', { name: /confirm approve/i })

    await user.click(confirmButton)

    await waitFor(() => expect(leaveApi.approveLeaveRequest).toHaveBeenCalledWith(1))
    expect(await screen.findByText('Leave request approved.')).toBeInTheDocument()
    await waitFor(() => expect(leaveApi.getEmployeeLeaveBalances).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('disables the approve confirm button while processing and prevents duplicate calls', async () => {
    let resolveApprove: (value: leaveApi.LeaveRequest) => void = () => {}
    vi.mocked(leaveApi.approveLeaveRequest).mockReturnValue(
      new Promise((resolve) => {
        resolveApprove = resolve
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    const confirmButton = await screen.findByRole('button', { name: /confirm approve/i })

    await user.click(confirmButton)
    expect(confirmButton).toBeDisabled()
    await user.click(confirmButton)

    expect(leaveApi.approveLeaveRequest).toHaveBeenCalledTimes(1)
    resolveApprove(leaveRequest({ status: 'APPROVED' }))
  })

  it('can open and cancel the rejection form without submitting', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Reject' }))

    expect(screen.getByLabelText(/reason/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByLabelText(/reason/i)).not.toBeInTheDocument()
    expect(leaveApi.rejectLeaveRequest).not.toHaveBeenCalled()
  })

  it('submits only the optional reason on rejection and updates status', async () => {
    vi.mocked(leaveApi.rejectLeaveRequest).mockResolvedValue(
      leaveRequest({ status: 'REJECTED', rejectionReason: 'Short staffed' }),
    )
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    await user.type(screen.getByLabelText(/reason/i), 'Short staffed')
    await user.click(screen.getByRole('button', { name: /confirm reject/i }))

    await waitFor(() => expect(leaveApi.rejectLeaveRequest).toHaveBeenCalledWith(1, 'Short staffed'))
    expect(await screen.findByText('Leave request rejected.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('limits the rejection reason to 500 characters', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Reject' }))

    expect(screen.getByLabelText(/reason/i)).toHaveAttribute('maxLength', '500')
  })

  it('displays review errors inline via extractErrorMessage', async () => {
    vi.mocked(leaveApi.approveLeaveRequest).mockRejectedValue({
      response: { data: { code: 'INSUFFICIENT_LEAVE_BALANCE', detail: 'Not enough remaining leave balance.' } },
    })
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    await user.click(await screen.findByRole('button', { name: /confirm approve/i }))

    expect(await screen.findByText('Not enough remaining leave balance.')).toBeInTheDocument()
  })

  it('refetches the request and its balances when approval fails as already processed', async () => {
    vi.mocked(leaveApi.approveLeaveRequest).mockRejectedValue(alreadyProcessedError())
    vi.mocked(leaveApi.getLeaveRequest)
      .mockResolvedValueOnce(leaveRequest({ status: 'PENDING' }))
      .mockResolvedValueOnce(leaveRequest({ status: 'APPROVED' }))
    vi.mocked(leaveApi.getEmployeeLeaveBalances)
      .mockResolvedValueOnce([balance()])
      .mockResolvedValueOnce([balance({ usedDays: 8, remainingDays: 6 })])
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    await user.click(await screen.findByRole('button', { name: /confirm approve/i }))

    await waitFor(() => expect(leaveApi.getLeaveRequest).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(leaveApi.getEmployeeLeaveBalances).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/already been processed/i)).toBeInTheDocument()
    expect(await screen.findByText('8')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('refetches the request and its balances when rejection fails as already processed', async () => {
    vi.mocked(leaveApi.rejectLeaveRequest).mockRejectedValue(alreadyProcessedError())
    vi.mocked(leaveApi.getLeaveRequest)
      .mockResolvedValueOnce(leaveRequest({ status: 'PENDING' }))
      .mockResolvedValueOnce(
        leaveRequest({ status: 'REJECTED', rejectionReason: 'Rejected by another admin' }),
      )
    vi.mocked(leaveApi.getEmployeeLeaveBalances)
      .mockResolvedValueOnce([balance()])
      .mockResolvedValueOnce([balance()])
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    await user.click(screen.getByRole('button', { name: /confirm reject/i }))

    await waitFor(() => expect(leaveApi.getLeaveRequest).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(leaveApi.getEmployeeLeaveBalances).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/already been processed/i)).toBeInTheDocument()
    expect(screen.getByText('Rejected by another admin')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('keeps the refreshed request visible when the post-conflict balance refetch fails', async () => {
    vi.mocked(leaveApi.approveLeaveRequest).mockRejectedValue(alreadyProcessedError())
    vi.mocked(leaveApi.getLeaveRequest)
      .mockResolvedValueOnce(leaveRequest({ status: 'PENDING' }))
      .mockResolvedValueOnce(leaveRequest({ status: 'APPROVED' }))
    vi.mocked(leaveApi.getEmployeeLeaveBalances)
      .mockResolvedValueOnce([balance()])
      .mockRejectedValueOnce({ response: { data: { detail: 'Could not load leave balances.' } } })
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    await user.click(await screen.findByRole('button', { name: /confirm approve/i }))

    expect(await screen.findByText(/already been processed/i)).toBeInTheDocument()
    expect(await screen.findByText('Could not load leave balances.')).toBeInTheDocument()
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('shows a loading state before the request loads', () => {
    vi.mocked(leaveApi.getLeaveRequest).mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('shows an inline error when the request fails to load', async () => {
    vi.mocked(leaveApi.getLeaveRequest).mockRejectedValue({
      response: { data: { detail: 'Leave request not found.' } },
    })

    renderPage()

    expect(await screen.findByText('Leave request not found.')).toBeInTheDocument()
  })

  it('shows an empty balance state when the employee has no balances at all', async () => {
    vi.mocked(leaveApi.getEmployeeLeaveBalances).mockResolvedValue([])

    renderPage()

    expect(await screen.findByText(/no leave balance has been provisioned/i)).toBeInTheDocument()
  })
})
