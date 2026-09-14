import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as leaveApi from '../../api/leave'
import { LeaveDashboardPage } from './LeaveDashboardPage'

vi.mock('../../api/leave')

const LEAVE_TYPES: leaveApi.LeaveType[] = [
  { id: 1, name: 'Annual Leave', defaultAnnualEntitlementDays: 14 },
  { id: 2, name: 'Sick Leave', defaultAnnualEntitlementDays: 14 },
]

function emptyHistoryPage(): leaveApi.PagedResponse<leaveApi.LeaveRequest> {
  return { content: [], page: { size: 10, totalElements: 0, totalPages: 0, number: 0 } }
}

function historyPage(
  content: leaveApi.LeaveRequest[],
  page = { size: 10, totalElements: content.length, totalPages: 1, number: 0 },
): leaveApi.PagedResponse<leaveApi.LeaveRequest> {
  return { content, page }
}

function request(overrides: Partial<leaveApi.LeaveRequest> = {}): leaveApi.LeaveRequest {
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
    reason: null,
    reviewedByUserId: null,
    reviewedAt: null,
    rejectionReason: null,
    ...overrides,
  }
}

function setupDefaultMocks() {
  vi.mocked(leaveApi.listLeaveTypes).mockResolvedValue(LEAVE_TYPES)
  vi.mocked(leaveApi.getOwnLeaveBalances).mockResolvedValue([])
  vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(emptyHistoryPage())
}

describe('LeaveDashboardPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setupDefaultMocks()
  })

  // ---- Leave types / balances ----

  it('loads leave types into the select', async () => {
    render(<LeaveDashboardPage />)

    await screen.findByRole('option', { name: 'Annual Leave' })
    expect(screen.getByRole('option', { name: 'Sick Leave' })).toBeInTheDocument()
  })

  it('renders balances with year, entitlement, used, remaining, and preserves backend ordering', async () => {
    vi.mocked(leaveApi.getOwnLeaveBalances).mockResolvedValue([
      { id: 1, employeeId: 7, leaveTypeId: 1, leaveTypeName: 'Annual Leave', year: 2026, entitledDays: 14, usedDays: 4, remainingDays: 10 },
      { id: 2, employeeId: 7, leaveTypeId: 2, leaveTypeName: 'Sick Leave', year: 2026, entitledDays: 14, usedDays: 1, remainingDays: 13 },
      { id: 3, employeeId: 7, leaveTypeId: 2, leaveTypeName: 'Sick Leave', year: 2025, entitledDays: 14, usedDays: 14, remainingDays: 0 },
    ])

    render(<LeaveDashboardPage />)

    const rows = await screen.findAllByRole('row')
    // row[0] is the header row.
    expect(within(rows[1]).getByText('2026')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Annual Leave')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Sick Leave')).toBeInTheDocument()
    expect(within(rows[2]).getByText('2026')).toBeInTheDocument()
    expect(within(rows[3]).getByText('2025')).toBeInTheDocument()
  })

  it('renders half-day decimal balance values correctly', async () => {
    vi.mocked(leaveApi.getOwnLeaveBalances).mockResolvedValue([
      { id: 1, employeeId: 7, leaveTypeId: 1, leaveTypeName: 'Annual Leave', year: 2026, entitledDays: 14, usedDays: 3.5, remainingDays: 10.5 },
    ])

    render(<LeaveDashboardPage />)

    expect(await screen.findByText('3.5')).toBeInTheDocument()
    expect(screen.getByText('10.5')).toBeInTheDocument()
  })

  it('shows an empty state when there are no provisioned balances', async () => {
    render(<LeaveDashboardPage />)

    expect(await screen.findByText(/no leave balances/i)).toBeInTheDocument()
  })

  // ---- Request history ----

  it('renders PENDING, APPROVED, and REJECTED status badges', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(
      historyPage(
        [
          request({ id: 1, status: 'PENDING' }),
          request({ id: 2, status: 'APPROVED' }),
          request({ id: 3, status: 'REJECTED' }),
        ],
        { size: 10, totalElements: 3, totalPages: 1, number: 0 },
      ),
    )

    render(<LeaveDashboardPage />)

    expect(await screen.findByText('PENDING')).toBeInTheDocument()
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
    expect(screen.getByText('REJECTED')).toBeInTheDocument()
  })

  it('shows a rejection reason only for a rejected request that has one', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(
      historyPage([
        request({ id: 1, status: 'REJECTED', rejectionReason: 'Short staffed that week' }),
        request({ id: 2, status: 'PENDING', rejectionReason: null }),
      ]),
    )

    render(<LeaveDashboardPage />)

    expect(await screen.findByText('Short staffed that week')).toBeInTheDocument()
  })

  it('supports history pagination and requests the correct page', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(
      historyPage([request({ id: 1 })], { size: 10, totalElements: 25, totalPages: 3, number: 0 }),
    )
    const user = userEvent.setup()

    render(<LeaveDashboardPage />)
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      expect(leaveApi.listOwnLeaveRequests).toHaveBeenLastCalledWith(1, expect.any(Number))
    })
  })

  it('does not render any HR_ADMIN approve or reject controls', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(historyPage([request()]))

    render(<LeaveDashboardPage />)

    await screen.findByText('PENDING')
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument()
  })

  // ---- Application form validation ----

  async function fillForm(
    user: ReturnType<typeof userEvent.setup>,
    { leaveType, start, end }: { leaveType?: string; start?: string; end?: string },
  ) {
    if (leaveType) await user.selectOptions(screen.getByLabelText('Leave type'), leaveType)
    if (start) await user.type(screen.getByLabelText('Start date'), start)
    if (end) await user.type(screen.getByLabelText('End date'), end)
  }

  it('prevents submission when required fields are missing', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
  })

  it('prevents submission when the end date precedes the start date', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-11', end: '2026-09-07' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/end date/i)
    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
  })

  it('prevents submission when dates span different calendar years', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-12-30', end: '2027-01-02' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/calendar year/i)
    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
  })

  it('prevents submission for a weekend-only date range', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    // 2026-09-12 is a Saturday, 2026-09-13 the following Sunday.
    await fillForm(user, { leaveType: '1', start: '2026-09-12', end: '2026-09-13' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/working day/i)
    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
  })

  // ---- Successful submission ----

  it('calls the API with only leaveTypeId, startDate, endDate, durationType, and reason for a valid request', async () => {
    vi.mocked(leaveApi.createLeaveRequest).mockResolvedValue(request())
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07', end: '2026-09-11' })
    await user.type(screen.getByLabelText(/Reason/), 'Family trip')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    await waitFor(() => expect(leaveApi.createLeaveRequest).toHaveBeenCalled())
    const [payload] = vi.mocked(leaveApi.createLeaveRequest).mock.calls[0]
    expect(Object.keys(payload).sort()).toEqual(['durationType', 'endDate', 'leaveTypeId', 'reason', 'startDate'])
    expect(payload).toMatchObject({
      leaveTypeId: 1,
      startDate: '2026-09-07',
      endDate: '2026-09-11',
      durationType: 'FULL_DAY',
      reason: 'Family trip',
    })
  })

  it('resets the form and refreshes history after a successful submission', async () => {
    vi.mocked(leaveApi.createLeaveRequest).mockResolvedValue(request())
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07', end: '2026-09-11' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    await waitFor(() => expect(leaveApi.listOwnLeaveRequests).toHaveBeenCalledTimes(2))
    expect(screen.getByLabelText('Start date')).toHaveValue('')
    expect(screen.getByLabelText('End date')).toHaveValue('')
  })

  // ---- Half-day duration ----

  it('defaults leave duration to Full Day', async () => {
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    expect(screen.getByLabelText('Leave duration')).toHaveValue('FULL_DAY')
  })

  it('mirrors the start date into the end date when Half Day is selected', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.type(screen.getByLabelText('Start date'), '2026-09-07')
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')

    expect(screen.getByLabelText('End date')).toHaveValue('2026-09-07')
  })

  it('disables the end date input while Half Day is selected', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')

    expect(screen.getByLabelText('End date')).toBeDisabled()
  })

  it('updates the end date when the start date changes while Half Day is selected', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')
    await user.type(screen.getByLabelText('Start date'), '2026-09-08')

    expect(screen.getByLabelText('End date')).toHaveValue('2026-09-08')
  })

  it('keeps both dates empty when Half Day is selected before any date is chosen', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')

    expect(screen.getByLabelText('Start date')).toHaveValue('')
    expect(screen.getByLabelText('End date')).toHaveValue('')
  })

  it('re-enables the end date and retains its value when switching back to Full Day', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await user.type(screen.getByLabelText('Start date'), '2026-09-07')
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'FULL_DAY')

    const endDateInput = screen.getByLabelText('End date')
    expect(endDateInput).not.toBeDisabled()
    expect(endDateInput).toHaveValue('2026-09-07')
  })

  it('rejects a Half Day request on a weekend', async () => {
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    // 2026-09-12 is a Saturday.
    await fillForm(user, { leaveType: '1', start: '2026-09-12' })
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/working day/i)
    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
  })

  it('submits a Half Day request with identical dates, durationType, and no daysRequested', async () => {
    vi.mocked(leaveApi.createLeaveRequest).mockResolvedValue(request({ durationType: 'HALF_DAY', daysRequested: 0.5 }))
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07' })
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    await waitFor(() => expect(leaveApi.createLeaveRequest).toHaveBeenCalled())
    const [payload] = vi.mocked(leaveApi.createLeaveRequest).mock.calls[0]
    expect(payload).toMatchObject({ durationType: 'HALF_DAY', startDate: '2026-09-07', endDate: '2026-09-07' })
    expect(payload).not.toHaveProperty('daysRequested')
  })

  it('resets leave duration to Full Day after a successful submission', async () => {
    vi.mocked(leaveApi.createLeaveRequest).mockResolvedValue(request({ durationType: 'HALF_DAY', daysRequested: 0.5 }))
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07' })
    await user.selectOptions(screen.getByLabelText('Leave duration'), 'HALF_DAY')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    await waitFor(() => expect(leaveApi.listOwnLeaveRequests).toHaveBeenCalledTimes(2))
    expect(screen.getByLabelText('Leave duration')).toHaveValue('FULL_DAY')
  })

  it('renders mixed Full Day and Half Day entries in the history table with the Duration column', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(
      historyPage([
        request({ id: 1, durationType: 'FULL_DAY', daysRequested: 5 }),
        request({ id: 2, durationType: 'HALF_DAY', daysRequested: 0.5 }),
      ]),
    )

    render(<LeaveDashboardPage />)

    const rows = await screen.findAllByRole('row')
    expect(within(rows[1]).getByText('Full Day')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Half Day')).toBeInTheDocument()
    expect(within(rows[2]).getByText('0.5')).toBeInTheDocument()
  })

  it('disables the submit button while a request is in progress', async () => {
    let resolveCreate: (value: leaveApi.LeaveRequest) => void = () => {}
    vi.mocked(leaveApi.createLeaveRequest).mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07', end: '2026-09-11' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(screen.getByRole('button', { name: /submitting/i })).toBeDisabled()
    resolveCreate(request())
  })

  it('shows a backend error via extractErrorMessage', async () => {
    vi.mocked(leaveApi.createLeaveRequest).mockRejectedValue({
      response: { data: { detail: 'The proposed leave period overlaps an existing request.' } },
    })
    const user = userEvent.setup()
    render(<LeaveDashboardPage />)
    await screen.findByRole('option', { name: 'Annual Leave' })

    await fillForm(user, { leaveType: '1', start: '2026-09-07', end: '2026-09-11' })
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('The proposed leave period overlaps an existing request.')).toBeInTheDocument()
  })
})
