import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as attendanceApi from '../../api/attendance'
import { AttendancePage } from './AttendancePage'

vi.mock('../../api/attendance')
vi.mock('../../api/client')

function renderPage() {
  return render(
    <MemoryRouter>
      <AttendancePage />
    </MemoryRouter>,
  )
}

function record(overrides: Partial<attendanceApi.AttendanceRecord> = {}): attendanceApi.AttendanceRecord {
  return {
    id: 1,
    workDate: '2026-03-10',
    clockInAt: '2026-03-10T09:00:00',
    clockOutAt: null,
    ...overrides,
  }
}

const emptyHistory: attendanceApi.PagedResponse<attendanceApi.AttendanceRecord> = {
  content: [],
  page: { size: 20, totalElements: 0, totalPages: 0, number: 0 },
}

function onePageHistory(items: attendanceApi.AttendanceRecord[]): attendanceApi.PagedResponse<attendanceApi.AttendanceRecord> {
  return { content: items, page: { size: 20, totalElements: items.length, totalPages: 1, number: 0 } }
}

const NOT_CLOCKED_IN: attendanceApi.AttendanceTodayResponse = { state: 'NOT_CLOCKED_IN', attendance: null }

describe('AttendancePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue(NOT_CLOCKED_IN)
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(emptyHistory)
  })

  // ---------------------------------------------------------------- initial load

  it('displays the My Attendance heading', async () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'My Attendance' })).toBeInTheDocument()
    await waitFor(() => expect(attendanceApi.getTodayAttendance).toHaveBeenCalled())
  })

  it('calls getTodayAttendance on initial load', async () => {
    renderPage()

    await waitFor(() => expect(attendanceApi.getTodayAttendance).toHaveBeenCalledWith())
  })

  it('calls listOwnAttendance(0, 20) on initial load', async () => {
    renderPage()

    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenCalledWith(0, 20))
  })

  it('shows a loading state while requests are pending', () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockReturnValue(new Promise(() => {}))
    vi.mocked(attendanceApi.listOwnAttendance).mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getAllByText(/loading/i).length).toBeGreaterThan(0)
  })

  it('a today-load failure does not prevent history from loading', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockRejectedValue(new Error('boom'))
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(onePageHistory([record()]))

    renderPage()

    expect(await screen.findByText('Could not load today’s attendance. Please try again.')).toBeInTheDocument()
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getByText('10 March 2026')).toBeInTheDocument()
  })

  it('a history-load failure does not prevent today status from loading', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue(NOT_CLOCKED_IN)
    vi.mocked(attendanceApi.listOwnAttendance).mockRejectedValue(new Error('boom'))

    renderPage()

    expect(await screen.findByText('Could not load attendance history. Please try again.')).toBeInTheDocument()
    expect(screen.getByText('Not clocked in today')).toBeInTheDocument()
  })

  it('shows the safe today-load fallback, never unknown backend detail', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockRejectedValue({
      response: { data: { detail: 'leaked backend detail' } },
    })

    renderPage()

    expect(await screen.findByText('Could not load today’s attendance. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('leaked backend detail')).not.toBeInTheDocument()
  })

  it('shows the safe history-load fallback, never unknown backend detail', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockRejectedValue({
      response: { data: { detail: 'leaked backend detail' } },
    })

    renderPage()

    expect(await screen.findByText('Could not load attendance history. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('leaked backend detail')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- today states

  it('NOT_CLOCKED_IN shows the not-clocked-in text and only a Clock In button', async () => {
    renderPage()

    expect(await screen.findByText('Not clocked in today')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clock In' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clock Out' })).not.toBeInTheDocument()
  })

  it('CLOCKED_IN shows formatted date/time and only a Clock Out button', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })

    renderPage()

    expect(await screen.findByText('Clocked in')).toBeInTheDocument()
    expect(screen.getByText('10 March 2026')).toBeInTheDocument()
    expect(screen.getByText('9:00 AM')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clock Out' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clock In' })).not.toBeInTheDocument()
  })

  it('CLOCKED_OUT shows both formatted times and no active mutation button', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({
      state: 'CLOCKED_OUT',
      attendance: record({ clockOutAt: '2026-03-10T18:00:00' }),
    })

    renderPage()

    expect(await screen.findByText('Clocked out')).toBeInTheDocument()
    expect(screen.getByText('9:00 AM')).toBeInTheDocument()
    expect(screen.getByText('6:00 PM')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clock In' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clock Out' })).not.toBeInTheDocument()
  })

  it('never displays a raw ISO timestamp string', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({
      state: 'CLOCKED_OUT',
      attendance: record({ clockOutAt: '2026-03-10T18:00:00' }),
    })

    renderPage()
    await screen.findByText('Clocked out')

    expect(screen.queryByText('2026-03-10T09:00:00')).not.toBeInTheDocument()
    expect(screen.queryByText('2026-03-10T18:00:00')).not.toBeInTheDocument()
  })

  it('never displays attendance/employee/user/actor id', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record({ id: 999 }) })

    renderPage()
    await screen.findByText('Clocked in')

    expect(screen.queryByText('999')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- clock-in

  it('clicking Clock In opens an inline confirmation', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })

    await user.click(screen.getByRole('button', { name: 'Clock In' }))

    expect(screen.getByRole('button', { name: 'Confirm clock in' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('Cancel closes the clock-in confirmation with zero API calls', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('button', { name: 'Confirm clock in' })).not.toBeInTheDocument()
    expect(attendanceApi.clockIn).not.toHaveBeenCalled()
  })

  it('Confirm calls clockIn() with no argument', async () => {
    vi.mocked(attendanceApi.clockIn).mockResolvedValue(record())
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    await waitFor(() => expect(attendanceApi.clockIn).toHaveBeenCalledWith())
  })

  it('shows Clocking in... while pending and disables the confirmation controls', async () => {
    let resolveClockIn!: (value: attendanceApi.AttendanceRecord) => void
    vi.mocked(attendanceApi.clockIn).mockReturnValue(
      new Promise((resolve) => {
        resolveClockIn = resolve
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    expect(await screen.findByRole('button', { name: 'Clocking in...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()

    resolveClockIn(record())
  })

  it('duplicate confirm clicks while pending produce only one clockIn request', async () => {
    let resolveClockIn!: (value: attendanceApi.AttendanceRecord) => void
    vi.mocked(attendanceApi.clockIn).mockReturnValue(
      new Promise((resolve) => {
        resolveClockIn = resolve
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))
    const confirmButton = screen.getByRole('button', { name: 'Confirm clock in' })

    await user.click(confirmButton)
    await user.click(confirmButton)

    expect(attendanceApi.clockIn).toHaveBeenCalledTimes(1)
    resolveClockIn(record())
  })

  it('successful clock-in shows feedback and refetches today and history', async () => {
    vi.mocked(attendanceApi.clockIn).mockResolvedValue(record())
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    vi.mocked(attendanceApi.getTodayAttendance).mockClear()
    vi.mocked(attendanceApi.listOwnAttendance).mockClear()
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })

    await user.click(screen.getByRole('button', { name: 'Clock In' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    expect(await screen.findByText('Clocked in successfully.')).toBeInTheDocument()
    await waitFor(() => expect(attendanceApi.getTodayAttendance).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('button', { name: 'Confirm clock in' })).not.toBeInTheDocument()
  })

  it('a failed clock-in restores controls and permits retry', async () => {
    vi.mocked(attendanceApi.clockIn).mockRejectedValueOnce(new Error('fail')).mockResolvedValueOnce(record())
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))
    await screen.findByRole('alert')

    expect(screen.getByRole('button', { name: 'Confirm clock in' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    await waitFor(() => expect(attendanceApi.clockIn).toHaveBeenCalledTimes(2))
  })

  it('a recognized ATTENDANCE_ALREADY_CLOCKED_IN error uses the safe detail/fallback', async () => {
    vi.mocked(attendanceApi.clockIn).mockRejectedValue({
      response: { data: { code: 'ATTENDANCE_ALREADY_CLOCKED_IN', detail: 'You have already clocked in today.' } },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('You have already clocked in today.')
  })

  it('an unknown/malformed clock-in error uses the fixed generic message and leaks nothing', async () => {
    vi.mocked(attendanceApi.clockIn).mockRejectedValue({
      response: { data: { code: 'SOMETHING_ELSE', detail: 'raw internal detail' } },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock In' })
    await user.click(screen.getByRole('button', { name: 'Clock In' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not update attendance. Please try again.')
    expect(screen.queryByText('raw internal detail')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- clock-out

  it('clicking Clock Out opens an inline confirmation', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })

    await user.click(screen.getByRole('button', { name: 'Clock Out' }))

    expect(screen.getByRole('button', { name: 'Confirm clock out' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('Cancel closes the clock-out confirmation with zero API calls', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('button', { name: 'Confirm clock out' })).not.toBeInTheDocument()
    expect(attendanceApi.clockOut).not.toHaveBeenCalled()
  })

  it('Confirm calls clockOut() with no argument', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.clockOut).mockResolvedValue(record({ clockOutAt: '2026-03-10T18:00:00' }))
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    await waitFor(() => expect(attendanceApi.clockOut).toHaveBeenCalledWith())
  })

  it('shows Clocking out... while pending and disables the confirmation controls', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    let resolveClockOut!: (value: attendanceApi.AttendanceRecord) => void
    vi.mocked(attendanceApi.clockOut).mockReturnValue(
      new Promise((resolve) => {
        resolveClockOut = resolve
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    expect(await screen.findByRole('button', { name: 'Clocking out...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()

    resolveClockOut(record({ clockOutAt: '2026-03-10T18:00:00' }))
  })

  it('duplicate confirm clicks while pending produce only one clockOut request', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    let resolveClockOut!: (value: attendanceApi.AttendanceRecord) => void
    vi.mocked(attendanceApi.clockOut).mockReturnValue(
      new Promise((resolve) => {
        resolveClockOut = resolve
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))
    const confirmButton = screen.getByRole('button', { name: 'Confirm clock out' })

    await user.click(confirmButton)
    await user.click(confirmButton)

    expect(attendanceApi.clockOut).toHaveBeenCalledTimes(1)
    resolveClockOut(record({ clockOutAt: '2026-03-10T18:00:00' }))
  })

  it('successful clock-out shows feedback and refetches today and history', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.clockOut).mockResolvedValue(record({ clockOutAt: '2026-03-10T18:00:00' }))
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    vi.mocked(attendanceApi.getTodayAttendance).mockClear()
    vi.mocked(attendanceApi.listOwnAttendance).mockClear()
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({
      state: 'CLOCKED_OUT',
      attendance: record({ clockOutAt: '2026-03-10T18:00:00' }),
    })

    await user.click(screen.getByRole('button', { name: 'Clock Out' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    expect(await screen.findByText('Clocked out successfully.')).toBeInTheDocument()
    await waitFor(() => expect(attendanceApi.getTodayAttendance).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenCalledTimes(1))
  })

  it('a failed clock-out restores controls and permits retry', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.clockOut)
      .mockRejectedValueOnce(new Error('fail'))
      .mockResolvedValueOnce(record({ clockOutAt: '2026-03-10T18:00:00' }))
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))
    await screen.findByRole('alert')

    expect(screen.getByRole('button', { name: 'Confirm clock out' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    await waitFor(() => expect(attendanceApi.clockOut).toHaveBeenCalledTimes(2))
  })

  it.each([
    ['ATTENDANCE_NO_OPEN_RECORD', 'You have not clocked in today.'],
    ['ATTENDANCE_ALREADY_CLOCKED_OUT', 'You have already clocked out today.'],
  ] as const)('recognized clock-out code %s uses the safe mapping', async (code, detail) => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.clockOut).mockRejectedValue({ response: { data: { code, detail } } })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(detail)
  })

  it('an unknown clock-out error redacts backend detail behind the fixed generic message', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.clockOut).mockRejectedValue({
      response: { data: { code: 'UNKNOWN_CODE', detail: 'raw internal detail' } },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Clock Out' })
    await user.click(screen.getByRole('button', { name: 'Clock Out' }))

    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not update attendance. Please try again.')
    expect(screen.queryByText('raw internal detail')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- history

  it('renders the history table semantically with the required columns', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(onePageHistory([record()]))

    renderPage()

    const table = await screen.findByRole('table')
    expect(within(table).getByText('Date')).toBeInTheDocument()
    expect(within(table).getByText('Clock In')).toBeInTheDocument()
    expect(within(table).getByText('Clock Out')).toBeInTheDocument()
    expect(within(table).getByText('Status')).toBeInTheDocument()
  })

  it('uses the attendance formatters for history rows', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(onePageHistory([record()]))

    renderPage()

    const table = await screen.findByRole('table')
    expect(within(table).getByText('10 March 2026')).toBeInTheDocument()
    expect(within(table).getByText('9:00 AM')).toBeInTheDocument()
  })

  it('renders rows in backend-provided order without client-side sorting', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(
      onePageHistory([record({ id: 2, workDate: '2026-03-01' }), record({ id: 1, workDate: '2026-03-10' })]),
    )

    renderPage()
    await screen.findByRole('table')

    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('1 March 2026')).toBeInTheDocument()
    expect(within(rows[2]).getByText('10 March 2026')).toBeInTheDocument()
  })

  it('displays Open for a null clockOutAt and Completed for a populated one', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(
      onePageHistory([record({ id: 1, clockOutAt: null }), record({ id: 2, clockOutAt: '2026-03-10T18:00:00' })]),
    )

    renderPage()
    await screen.findByRole('table')

    expect(screen.getByText('Open')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
  })

  it('displays an empty-history state', async () => {
    renderPage()

    expect(await screen.findByText('No attendance history yet.')).toBeInTheDocument()
  })

  it('never displays attendance id or identity/internal fields in history', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(onePageHistory([record({ id: 12345 })]))

    renderPage()
    await screen.findByRole('table')

    expect(screen.queryByText('12345')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- pagination

  it('disables Previous on the first page and Next on the last page', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue(onePageHistory([record()]))

    renderPage()
    await screen.findByText('Page 1 of 1')

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('Next requests the correct next page at size 20', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue({
      content: [record()],
      page: { size: 20, totalElements: 45, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(1, 20))
  })

  it('Previous requests the correct previous page', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue({
      content: [record()],
      page: { size: 20, totalElements: 45, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(1, 20))

    await user.click(screen.getByRole('button', { name: 'Previous' }))

    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(0, 20))
  })

  it('disables pagination controls while history is loading and blocks duplicate requests', async () => {
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValueOnce({
      content: [record()],
      page: { size: 20, totalElements: 45, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    let resolveNext!: (value: attendanceApi.PagedResponse<attendanceApi.AttendanceRecord>) => void
    vi.mocked(attendanceApi.listOwnAttendance).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveNext = resolve
        }),
    )
    vi.mocked(attendanceApi.listOwnAttendance).mockClear()

    const nextButton = screen.getByRole('button', { name: 'Next' })
    await user.click(nextButton)
    await user.click(nextButton)

    expect(attendanceApi.listOwnAttendance).toHaveBeenCalledTimes(1)
    resolveNext({ content: [record()], page: { size: 20, totalElements: 45, totalPages: 3, number: 1 } })
  })

  it('preserves the current page after a successful clock-out refresh when it remains valid', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue({ state: 'CLOCKED_IN', attendance: record() })
    vi.mocked(attendanceApi.listOwnAttendance).mockResolvedValue({
      content: [record()],
      page: { size: 20, totalElements: 45, totalPages: 3, number: 1 },
    })
    vi.mocked(attendanceApi.clockOut).mockResolvedValue(record({ clockOutAt: '2026-03-10T18:00:00' }))
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(1, 20))
    vi.mocked(attendanceApi.listOwnAttendance).mockClear()

    await user.click(screen.getByRole('button', { name: 'Clock Out' }))
    await user.click(screen.getByRole('button', { name: 'Confirm clock out' }))

    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(1, 20))
  })

  it('clamps to the last valid page when refreshed metadata reports the current page is out of range', async () => {
    vi.mocked(attendanceApi.listOwnAttendance)
      .mockResolvedValueOnce({
        content: [record()],
        page: { size: 20, totalElements: 45, totalPages: 3, number: 0 },
      })
      .mockResolvedValueOnce({ content: [], page: { size: 20, totalElements: 1, totalPages: 1, number: 1 } })
      .mockResolvedValueOnce({
        content: [record({ workDate: '2026-04-01' })],
        page: { size: 20, totalElements: 1, totalPages: 1, number: 0 },
      })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('1 April 2026')).toBeInTheDocument()
    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenLastCalledWith(0, 20))
  })

  it('never sends a sort parameter or employee id from the page', async () => {
    renderPage()

    await waitFor(() => expect(attendanceApi.listOwnAttendance).toHaveBeenCalled())
    expect(vi.mocked(attendanceApi.listOwnAttendance).mock.calls[0]).toEqual([0, 20])
  })

  // ---------------------------------------------------------------- accessibility / security

  it('renders load errors with role="alert"', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockRejectedValue(new Error('fail'))

    renderPage()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('never calls apiClient or axios directly from this page module', () => {
    const source = AttendancePage.toString()
    expect(source).not.toMatch(/apiClient/)
    expect(source).not.toMatch(/axios/)
  })
})
