import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AttendanceTodayResponse } from '../../api/attendance'
import * as attendanceApi from '../../api/attendance'
import type { Employee } from '../../api/employees'
import * as employeesApi from '../../api/employees'
import type { LeaveBalance, LeaveRequest, PagedResponse as LeavePage } from '../../api/leave'
import * as leaveApi from '../../api/leave'
import type { PagedResponse as PayslipPage, Payslip } from '../../api/payroll'
import * as payrollApi from '../../api/payroll'
import { EmployeeDashboardPage } from './EmployeeDashboardPage'

vi.mock('../../api/attendance', () => ({
  getTodayAttendance: vi.fn(),
  listOwnAttendance: vi.fn(),
  clockIn: vi.fn(),
  clockOut: vi.fn(),
}))
vi.mock('../../api/leave', () => ({
  getOwnLeaveBalances: vi.fn(),
  listOwnLeaveRequests: vi.fn(),
  createLeaveRequest: vi.fn(),
}))
vi.mock('../../api/payroll', () => ({
  listOwnPayslips: vi.fn(),
}))
vi.mock('../../api/employees', () => ({
  getOwnProfile: vi.fn(),
  getEmployeeSensitive: vi.fn(),
  updateOwnProfile: vi.fn(),
}))

const NOT_CLOCKED_IN: AttendanceTodayResponse = { state: 'NOT_CLOCKED_IN', attendance: null }
const CLOCKED_IN: AttendanceTodayResponse = {
  state: 'CLOCKED_IN',
  attendance: { id: 31337, workDate: '2026-09-27', clockInAt: '2026-09-27T09:05:00', clockOutAt: null },
}
const CLOCKED_OUT: AttendanceTodayResponse = {
  state: 'CLOCKED_OUT',
  attendance: { id: 31337, workDate: '2026-09-27', clockInAt: '2026-09-27T09:05:00', clockOutAt: '2026-09-27T17:45:30' },
}

// Deliberately inconsistent remainingDays (entitled - used would be 10.5 and 12.0): the page must
// render the backend's 99.0 and 42.0, proving it never computes remaining days itself.
const BALANCES: LeaveBalance[] = [
  { id: 901, employeeId: 7, leaveTypeId: 2, leaveTypeName: 'Sick Leave', year: 2026, entitledDays: 14, usedDays: 3.5, remainingDays: 99 },
  { id: 902, employeeId: 7, leaveTypeId: 1, leaveTypeName: 'Annual Leave', year: 2025, entitledDays: 12, usedDays: 0, remainingDays: 42 },
]

function leaveRequest(overrides: Partial<LeaveRequest> = {}): LeaveRequest {
  return {
    id: 555,
    employeeId: 7,
    employeeCode: 'EMP007',
    employeeFullName: 'Siti Aminah',
    leaveTypeId: 1,
    leaveTypeName: 'Annual Leave',
    startDate: '2026-10-05',
    endDate: '2026-10-06',
    daysRequested: 2,
    durationType: 'FULL_DAY',
    status: 'PENDING',
    reason: 'Family trip private detail',
    reviewedByUserId: 4242,
    reviewedAt: null,
    rejectionReason: 'Internal rejection note',
    ...overrides,
  }
}

function leavePage(items: LeaveRequest[], totalElements = items.length): LeavePage<LeaveRequest> {
  return { content: items, page: { size: 5, totalElements, totalPages: 1, number: 0 } }
}

const RECENT_REQUESTS = leavePage(
  [
    leaveRequest({ id: 556, leaveTypeName: 'Sick Leave', startDate: '2026-11-02', endDate: '2026-11-02', status: 'APPROVED' }),
    leaveRequest({ id: 555, leaveTypeName: 'Annual Leave', startDate: '2026-10-05', endDate: '2026-10-06', status: 'PENDING' }),
  ],
  23,
)

function payslip(overrides: Partial<Payslip> = {}): Payslip {
  return {
    id: 8123,
    payrollRunId: 2746,
    employeeId: 7,
    employeeCodeSnapshot: 'SNAPCODE99',
    employeeNameSnapshot: 'Snapshot Other Name',
    departmentNameSnapshot: 'Snapshot Dept',
    jobTitleSnapshot: 'Snapshot Title',
    basePay: 5000,
    housingAllowance: 500,
    transportAllowance: 200,
    grossPay: 5700,
    epfDeduction: 627,
    socsoDeduction: 28.5,
    eisDeduction: 11.4,
    incomeTax: 285,
    totalDeductions: 951.9,
    netPay: 4321.09,
    pdfStatus: 'GENERATED',
    createdAt: '2026-09-01T10:00:00',
    periodMonth: 8,
    periodYear: 2026,
    ...overrides,
  }
}

function payslipPage(items: Payslip[]): PayslipPage<Payslip> {
  return { content: items, page: { size: 1, totalElements: items.length, totalPages: items.length, number: 0 } }
}

const PROFILE: Employee = {
  id: 7,
  employeeCode: 'EMP007',
  fullName: 'Siti Aminah',
  departmentId: 332,
  departmentName: 'Finance',
  jobTitle: 'Accountant',
  employmentStatus: 'ACTIVE',
  hireDate: '2021-03-15',
  phoneNumber: '012-3456789',
}

const RAW_ERROR = {
  response: { data: { detail: 'org.postgresql.util.PSQLException: SELECT * FROM payslips at com.hrpayroll.Internal' } },
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

// formatMYR renders a NO-BREAK SPACE after "RM"; Testing Library's default normalizer collapses it to
// a plain space before comparing (see ReportsPage.test.tsx).
function rm(digits: string): string {
  return `RM ${digits}`
}

function renderPage() {
  return render(
    <MemoryRouter>
      <EmployeeDashboardPage />
    </MemoryRouter>,
  )
}

function section(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

async function findSection(name: string): Promise<HTMLElement> {
  return screen.findByRole('region', { name })
}

function rowsText(table: HTMLElement): string[][] {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent ?? ''))
}

describe('EmployeeDashboardPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue(CLOCKED_IN)
    vi.mocked(leaveApi.getOwnLeaveBalances).mockResolvedValue(BALANCES)
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(RECENT_REQUESTS)
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(payslipPage([payslip()]))
    vi.mocked(employeesApi.getOwnProfile).mockResolvedValue(PROFILE)
  })

  // ---------------------------------------------------------------- requests

  it('calls all five self-service functions exactly once', () => {
    renderPage()

    expect(attendanceApi.getTodayAttendance).toHaveBeenCalledTimes(1)
    expect(leaveApi.getOwnLeaveBalances).toHaveBeenCalledTimes(1)
    expect(leaveApi.listOwnLeaveRequests).toHaveBeenCalledTimes(1)
    expect(payrollApi.listOwnPayslips).toHaveBeenCalledTimes(1)
    expect(employeesApi.getOwnProfile).toHaveBeenCalledTimes(1)
  })

  it('calls today attendance, leave balances, and profile with no arguments at all', () => {
    renderPage()

    expect(vi.mocked(attendanceApi.getTodayAttendance).mock.calls[0]).toEqual([])
    expect(vi.mocked(leaveApi.getOwnLeaveBalances).mock.calls[0]).toEqual([])
    expect(vi.mocked(employeesApi.getOwnProfile).mock.calls[0]).toEqual([])
  })

  it('requests recent leave with exactly page 0 and size 5, and the latest payslip with exactly page 0 and size 1', () => {
    renderPage()

    expect(vi.mocked(leaveApi.listOwnLeaveRequests).mock.calls[0]).toEqual([0, 5])
    expect(vi.mocked(payrollApi.listOwnPayslips).mock.calls[0]).toEqual([0, 1])
  })

  it('never sends an employeeId, userId, actorId, or other identity argument', () => {
    renderPage()

    const allArgs = [
      ...vi.mocked(attendanceApi.getTodayAttendance).mock.calls,
      ...vi.mocked(leaveApi.getOwnLeaveBalances).mock.calls,
      ...vi.mocked(leaveApi.listOwnLeaveRequests).mock.calls,
      ...vi.mocked(payrollApi.listOwnPayslips).mock.calls,
      ...vi.mocked(employeesApi.getOwnProfile).mock.calls,
    ].flat()
    expect(allArgs.every((arg) => typeof arg === 'number')).toBe(true)
    expect(allArgs).not.toContain(7)
  })

  // ---------------------------------------------------------------- today's attendance

  it('shows NOT_CLOCKED_IN as visible text', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue(NOT_CLOCKED_IN)
    renderPage()

    expect(await within(await findSection("Today's Attendance")).findByText('Not clocked in today')).toBeInTheDocument()
  })

  it('shows CLOCKED_IN with the backend date and clock-in time and a safe placeholder for clock-out', async () => {
    renderPage()

    const attendance = await findSection("Today's Attendance")
    expect(await within(attendance).findByText('Clocked in')).toBeInTheDocument()
    expect(within(attendance).getByText('27 September 2026')).toBeInTheDocument()
    expect(within(attendance).getByText('9:05 AM')).toBeInTheDocument()
    expect(within(attendance).getByText('—')).toBeInTheDocument()
  })

  it('shows CLOCKED_OUT with both backend times', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockResolvedValue(CLOCKED_OUT)
    renderPage()

    const attendance = await findSection("Today's Attendance")
    expect(await within(attendance).findByText('Clocked out')).toBeInTheDocument()
    expect(within(attendance).getByText('9:05 AM')).toBeInTheDocument()
    expect(within(attendance).getByText('5:45 PM')).toBeInTheDocument()
  })

  it('links to /employee/attendance, has no clock-in/out control, and never reads attendance history', async () => {
    renderPage()

    const attendance = await findSection("Today's Attendance")
    await within(attendance).findByText('Clocked in')
    expect(within(attendance).getByRole('link', { name: /go to attendance/i })).toHaveAttribute('href', '/employee/attendance')
    expect(screen.queryByRole('button', { name: /clock/i })).not.toBeInTheDocument()
    expect(attendanceApi.listOwnAttendance).not.toHaveBeenCalled()
    expect(attendanceApi.clockIn).not.toHaveBeenCalled()
    expect(attendanceApi.clockOut).not.toHaveBeenCalled()
    expect(screen.queryByText(/31337/)).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- leave balances

  it('renders balances exactly in backend order with year, type, entitled, used, and the backend remaining value', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: 'My leave balances' })
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Year',
      'Leave Type',
      'Entitled',
      'Used',
      'Remaining',
    ])
    expect(rowsText(table)).toEqual([
      ['2026', 'Sick Leave', '14.0', '3.5', '99.0'],
      ['2025', 'Annual Leave', '12.0', '0.0', '42.0'],
    ])
    expect(within(table).queryByText('10.5')).not.toBeInTheDocument()
  })

  it('shows a visible empty state for no leave balances and links to /employee/leave', async () => {
    vi.mocked(leaveApi.getOwnLeaveBalances).mockResolvedValue([])
    renderPage()

    const balances = await findSection('Leave Balances')
    expect(await within(balances).findByText('No leave balances found.')).toBeInTheDocument()
    expect(within(balances).queryByRole('table')).not.toBeInTheDocument()
    expect(within(balances).getByRole('link', { name: /manage leave/i })).toHaveAttribute('href', '/employee/leave')
  })

  // ---------------------------------------------------------------- recent leave requests

  it('renders the returned recent requests in backend order with type, date range, and visible status', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: 'My recent leave requests' })
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Leave Type',
      'Dates',
      'Status',
    ])
    expect(rowsText(table)).toEqual([
      ['Sick Leave', '2 November 2026 – 2 November 2026', 'APPROVED'],
      ['Annual Leave', '5 October 2026 – 6 October 2026', 'PENDING'],
    ])
  })

  it('never shows a pending count, the total, internal ids, reasons, or reviewer data', async () => {
    renderPage()

    const requests = await findSection('Recent Leave Requests')
    await within(requests).findByText('Sick Leave')
    expect(within(requests).queryByText('23')).not.toBeInTheDocument()
    expect(within(requests).queryByText(/pending requests|awaiting/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/555|556|4242/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Family trip private detail|Internal rejection note/)).not.toBeInTheDocument()
  })

  it('shows a visible empty state for no leave requests and links to /employee/leave', async () => {
    vi.mocked(leaveApi.listOwnLeaveRequests).mockResolvedValue(leavePage([]))
    renderPage()

    const requests = await findSection('Recent Leave Requests')
    expect(await within(requests).findByText('No leave requests yet.')).toBeInTheDocument()
    expect(within(requests).getByRole('link', { name: /view all leave requests/i })).toHaveAttribute('href', '/employee/leave')
  })

  // ---------------------------------------------------------------- latest payslip

  it('renders the first returned payslip period and backend net pay, linking by id without showing the id', async () => {
    renderPage()

    const latest = await findSection('Latest Payslip')
    expect(await within(latest).findByText('August 2026')).toBeInTheDocument()
    expect(within(latest).getByText(rm('4,321.09'))).toBeInTheDocument()
    expect(within(latest).getByRole('link', { name: /view payslip/i })).toHaveAttribute('href', '/employee/payslips/8123')
    expect(screen.queryByText(/8123|2746/)).not.toBeInTheDocument()
  })

  it('never computes or shows gross/deduction figures or the snapshot identity on the dashboard', async () => {
    // netPay deliberately inconsistent with grossPay - totalDeductions (would be 4,748.10).
    renderPage()

    const latest = await findSection('Latest Payslip')
    await within(latest).findByText(rm('4,321.09'))
    expect(screen.queryByText(rm('4,748.10'))).not.toBeInTheDocument()
    expect(screen.queryByText(rm('5,700.00'))).not.toBeInTheDocument()
    expect(screen.queryByText(rm('951.90'))).not.toBeInTheDocument()
    expect(screen.queryByText(/Snapshot Other Name|SNAPCODE99|Snapshot Dept|Snapshot Title/)).not.toBeInTheDocument()
  })

  it('shows a visible empty state when there is no payslip', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(payslipPage([]))
    renderPage()

    const latest = await findSection('Latest Payslip')
    expect(await within(latest).findByText('No payslips yet.')).toBeInTheDocument()
    expect(within(latest).queryByRole('link', { name: /view payslip/i })).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- profile

  it('shows only name, employee code, department, job title, and formatted hire date, linking to /employee/profile', async () => {
    renderPage()

    const profile = await findSection('My Profile')
    expect(await within(profile).findByText('Siti Aminah')).toBeInTheDocument()
    expect(within(profile).getByText('EMP007')).toBeInTheDocument()
    expect(within(profile).getByText('Finance')).toBeInTheDocument()
    expect(within(profile).getByText('Accountant')).toBeInTheDocument()
    expect(within(profile).getByText('15 March 2021')).toBeInTheDocument()
    expect(within(profile).getByRole('link', { name: /view full profile/i })).toHaveAttribute('href', '/employee/profile')
  })

  it('never shows phone, status, internal ids, or requests sensitive profile data', async () => {
    renderPage()

    const profile = await findSection('My Profile')
    await within(profile).findByText('Siti Aminah')
    expect(screen.queryByText('012-3456789')).not.toBeInTheDocument()
    expect(within(profile).queryByText('ACTIVE')).not.toBeInTheDocument()
    expect(within(profile).queryByText('332')).not.toBeInTheDocument()
    expect(within(profile).queryByText(/IC|passport|bank|salary|password/i)).not.toBeInTheDocument()
    expect(employeesApi.getEmployeeSensitive).not.toHaveBeenCalled()
  })

  // ---------------------------------------------------------------- loading independence

  it('shows an independent loading message for each of the five sections', () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockReturnValue(new Promise(() => {}))
    vi.mocked(leaveApi.getOwnLeaveBalances).mockReturnValue(new Promise(() => {}))
    vi.mocked(leaveApi.listOwnLeaveRequests).mockReturnValue(new Promise(() => {}))
    vi.mocked(payrollApi.listOwnPayslips).mockReturnValue(new Promise(() => {}))
    vi.mocked(employeesApi.getOwnProfile).mockReturnValue(new Promise(() => {}))
    renderPage()

    expect(screen.getByText("Loading today's attendance...")).toBeInTheDocument()
    expect(screen.getByText('Loading leave balances...')).toBeInTheDocument()
    expect(screen.getByText('Loading recent leave requests...')).toBeInTheDocument()
    expect(screen.getByText('Loading latest payslip...')).toBeInTheDocument()
    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('resolving one section does not clear the other sections loading states', async () => {
    const attendance = deferred<AttendanceTodayResponse>()
    const balances = deferred<LeaveBalance[]>()
    const requests = deferred<LeavePage<LeaveRequest>>()
    const payslips = deferred<PayslipPage<Payslip>>()
    const profile = deferred<Employee>()
    vi.mocked(attendanceApi.getTodayAttendance).mockReturnValue(attendance.promise)
    vi.mocked(leaveApi.getOwnLeaveBalances).mockReturnValue(balances.promise)
    vi.mocked(leaveApi.listOwnLeaveRequests).mockReturnValue(requests.promise)
    vi.mocked(payrollApi.listOwnPayslips).mockReturnValue(payslips.promise)
    vi.mocked(employeesApi.getOwnProfile).mockReturnValue(profile.promise)
    renderPage()

    payslips.resolve(payslipPage([payslip()]))
    await waitFor(() => expect(screen.queryByText('Loading latest payslip...')).not.toBeInTheDocument())
    expect(screen.getByText("Loading today's attendance...")).toBeInTheDocument()
    expect(screen.getByText('Loading leave balances...')).toBeInTheDocument()
    expect(screen.getByText('Loading recent leave requests...')).toBeInTheDocument()
    expect(screen.getByText('Loading profile...')).toBeInTheDocument()

    profile.resolve(PROFILE)
    await waitFor(() => expect(screen.queryByText('Loading profile...')).not.toBeInTheDocument())
    expect(screen.getByText("Loading today's attendance...")).toBeInTheDocument()

    attendance.resolve(CLOCKED_IN)
    balances.resolve(BALANCES)
    requests.resolve(RECENT_REQUESTS)
    await waitFor(() => expect(screen.queryByText(/^Loading /)).not.toBeInTheDocument())
  })

  // ---------------------------------------------------------------- error independence

  const FAILURE_CASES = [
    {
      name: "Today's Attendance",
      message: "Could not load today's attendance. Please try again.",
      fail: () => vi.mocked(attendanceApi.getTodayAttendance).mockRejectedValue(RAW_ERROR),
    },
    {
      name: 'Leave Balances',
      message: 'Could not load leave balances. Please try again.',
      fail: () => vi.mocked(leaveApi.getOwnLeaveBalances).mockRejectedValue(RAW_ERROR),
    },
    {
      name: 'Recent Leave Requests',
      message: 'Could not load recent leave requests. Please try again.',
      fail: () => vi.mocked(leaveApi.listOwnLeaveRequests).mockRejectedValue(RAW_ERROR),
    },
    {
      name: 'Latest Payslip',
      message: 'Could not load latest payslip. Please try again.',
      fail: () => vi.mocked(payrollApi.listOwnPayslips).mockRejectedValue(RAW_ERROR),
    },
    {
      name: 'My Profile',
      message: 'Could not load profile. Please try again.',
      fail: () => vi.mocked(employeesApi.getOwnProfile).mockRejectedValue(RAW_ERROR),
    },
  ]

  const SUCCESS_MARKERS: Record<string, string> = {
    "Today's Attendance": 'Clocked in',
    'Leave Balances': 'Sick Leave',
    'Recent Leave Requests': 'Annual Leave',
    'Latest Payslip': 'August 2026',
    'My Profile': 'Accountant',
  }

  for (const failure of FAILURE_CASES) {
    it(`a ${failure.name} failure shows only its own fixed safe alert and leaves every other section visible`, async () => {
      failure.fail()
      renderPage()

      const failed = await findSection(failure.name)
      expect(await within(failed).findByRole('alert')).toHaveTextContent(failure.message)
      for (const [name, marker] of Object.entries(SUCCESS_MARKERS)) {
        if (name === failure.name) continue
        expect(await within(section(name)).findAllByText(marker)).not.toHaveLength(0)
      }
      expect(screen.getAllByRole('alert')).toHaveLength(1)
    })
  }

  it('never renders raw backend, SQL, stack-trace, Java, or Axios error text', async () => {
    vi.mocked(attendanceApi.getTodayAttendance).mockRejectedValue(RAW_ERROR)
    vi.mocked(leaveApi.getOwnLeaveBalances).mockRejectedValue(new Error('java.lang.NullPointerException at Service.java:42'))
    vi.mocked(leaveApi.listOwnLeaveRequests).mockRejectedValue(new Error('AxiosError: Request failed with status code 500'))
    vi.mocked(payrollApi.listOwnPayslips).mockRejectedValue(RAW_ERROR)
    vi.mocked(employeesApi.getOwnProfile).mockRejectedValue(RAW_ERROR)
    renderPage()

    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(5))
    expect(
      screen.queryByText(/PSQLException|SELECT|com\.hrpayroll|NullPointerException|java\.lang|Service\.java|AxiosError|status code/),
    ).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- quick actions

  it('provides quick links to My Profile, Attendance, My Leave, My Payslips, and Salary History', () => {
    renderPage()

    const quick = section('Quick Actions')
    expect(within(quick).getByRole('link', { name: 'My Profile' })).toHaveAttribute('href', '/employee/profile')
    expect(within(quick).getByRole('link', { name: 'Attendance' })).toHaveAttribute('href', '/employee/attendance')
    expect(within(quick).getByRole('link', { name: 'My Leave' })).toHaveAttribute('href', '/employee/leave')
    expect(within(quick).getByRole('link', { name: 'My Payslips' })).toHaveAttribute('href', '/employee/payslips')
    expect(within(quick).getByRole('link', { name: 'Salary History' })).toHaveAttribute('href', '/employee/salary')
  })

  // ---------------------------------------------------------------- accessibility / scope

  it('has exactly one h1 "Employee Dashboard" and an h2 per dashboard section', () => {
    renderPage()

    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent('Employee Dashboard')
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)).toEqual([
      "Today's Attendance",
      'Leave Balances',
      'Recent Leave Requests',
      'Latest Payslip',
      'My Profile',
      'Quick Actions',
    ])
  })

  it('puts every table in a positioned overflow-x-auto wrapper with an sr-only caption', async () => {
    renderPage()
    await screen.findByRole('table', { name: 'My leave balances' })
    await screen.findByRole('table', { name: 'My recent leave requests' })

    const tables = screen.getAllByRole('table')
    expect(tables).toHaveLength(2)
    for (const table of tables) {
      expect(table.querySelector('caption')).toHaveClass('sr-only')
      expect(table.parentElement).toHaveClass('relative')
      expect(table.parentElement).toHaveClass('overflow-x-auto')
    }
  })

  it('has no chart, canvas, SVG, export/download, button, form, or text input', async () => {
    renderPage()
    await within(await findSection('My Profile')).findByText('Accountant')

    expect(document.querySelector('canvas')).not.toBeInTheDocument()
    expect(document.querySelector('svg')).not.toBeInTheDocument()
    expect(document.querySelector('form')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByText(/export|download/i)).not.toBeInTheDocument()
    expect(leaveApi.createLeaveRequest).not.toHaveBeenCalled()
    expect(employeesApi.updateOwnProfile).not.toHaveBeenCalled()
  })
})
