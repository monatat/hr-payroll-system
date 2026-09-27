import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LeaveRequest, PagedResponse as LeavePage } from '../../api/leave'
import * as leaveApi from '../../api/leave'
import type { PagedResponse as PayrollPage, PayrollRun } from '../../api/payroll'
import * as payrollApi from '../../api/payroll'
import type { HeadcountReport } from '../../api/reports'
import * as reportsApi from '../../api/reports'
import { AdminDashboardPage } from './AdminDashboardPage'

vi.mock('../../api/reports', () => ({
  getHeadcountReport: vi.fn(),
  getPayrollCostReport: vi.fn(),
}))
vi.mock('../../api/leave', () => ({
  listLeaveRequests: vi.fn(),
}))
vi.mock('../../api/payroll', () => ({
  listPayrollRuns: vi.fn(),
}))

// Deliberately inconsistent: the department rows sum to 4, but the backend's authoritative total
// is 9 -- the page must render 9, proving it never re-sums the rows.
const HEADCOUNT: HeadcountReport = {
  totalActiveEmployees: 9,
  departments: [
    { departmentId: 501, departmentName: 'Zeta Ops', activeEmployeeCount: 3 },
    { departmentId: 332, departmentName: 'Alpha IT', activeEmployeeCount: 1 },
  ],
}

function leaveRow(overrides: Partial<LeaveRequest> = {}): LeaveRequest {
  return {
    id: 4242,
    employeeId: 777,
    employeeCode: 'EMPSECRET',
    employeeFullName: 'Hidden Person Name',
    leaveTypeId: 1,
    leaveTypeName: 'Annual Leave',
    startDate: '2026-10-01',
    endDate: '2026-10-02',
    daysRequested: 2,
    durationType: 'FULL_DAY',
    status: 'PENDING',
    reason: 'Private medical reason',
    reviewedByUserId: null,
    reviewedAt: null,
    rejectionReason: null,
    ...overrides,
  }
}

// Deliberately inconsistent: one row of content, but 17 pending requests in total.
const PENDING_PAGE: LeavePage<LeaveRequest> = {
  content: [leaveRow()],
  page: { size: 1, totalElements: 17, totalPages: 17, number: 0 },
}

const EMPTY_PENDING_PAGE: LeavePage<LeaveRequest> = {
  content: [],
  page: { size: 1, totalElements: 0, totalPages: 0, number: 0 },
}

function payrollRun(overrides: Partial<PayrollRun> = {}): PayrollRun {
  return {
    id: 9876,
    periodMonth: 3,
    periodYear: 2026,
    periodStart: '2026-03-01',
    periodEnd: '2026-03-31',
    status: 'CALCULATED',
    failureReason: null,
    epfRateSnapshot: 0.11,
    socsoRateSnapshot: 0.005,
    eisRateSnapshot: 0.002,
    incomeTaxRateSnapshot: 0.05,
    version: 3,
    createdAt: '2026-04-02T10:15:30.123456',
    updatedAt: '2026-04-02T10:20:00',
    ...overrides,
  }
}

function payrollPage(runs: PayrollRun[]): PayrollPage<PayrollRun> {
  return { content: runs, page: { size: 1, totalElements: runs.length, totalPages: runs.length, number: 0 } }
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

const RAW_ERROR = {
  response: { data: { detail: 'org.postgresql.util.PSQLException: SELECT * FROM leave_requests at com.hrpayroll.Internal' } },
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AdminDashboardPage />
    </MemoryRouter>,
  )
}

function section(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('AdminDashboardPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue(HEADCOUNT)
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue(PENDING_PAGE)
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue(payrollPage([payrollRun()]))
  })

  // ---------------------------------------------------------------- requests

  it('requests headcount with no arguments', () => {
    renderPage()

    expect(reportsApi.getHeadcountReport).toHaveBeenCalledTimes(1)
    expect(vi.mocked(reportsApi.getHeadcountReport).mock.calls[0]).toEqual([])
  })

  it('requests pending leave with exactly page 0, size 1, status PENDING', () => {
    renderPage()

    expect(leaveApi.listLeaveRequests).toHaveBeenCalledTimes(1)
    expect(leaveApi.listLeaveRequests).toHaveBeenCalledWith({ status: 'PENDING', page: 0, size: 1 })
  })

  it('requests the latest payroll run with exactly page 0, size 1 and no filter', () => {
    renderPage()

    expect(payrollApi.listPayrollRuns).toHaveBeenCalledTimes(1)
    expect(payrollApi.listPayrollRuns).toHaveBeenCalledWith({ page: 0, size: 1 })
  })

  it('never sends an employeeId, userId, or actorId in any dashboard request', () => {
    renderPage()

    const allArgs = [
      ...vi.mocked(reportsApi.getHeadcountReport).mock.calls,
      ...vi.mocked(leaveApi.listLeaveRequests).mock.calls,
      ...vi.mocked(payrollApi.listPayrollRuns).mock.calls,
    ].flat()
    for (const arg of allArgs) {
      if (arg && typeof arg === 'object') {
        expect(arg).not.toHaveProperty('employeeId')
        expect(arg).not.toHaveProperty('userId')
        expect(arg).not.toHaveProperty('actorId')
      }
    }
  })

  // ---------------------------------------------------------------- headcount

  it('renders the backend totalActiveEmployees, never a sum of department rows', async () => {
    renderPage()

    const active = await screen.findByRole('region', { name: 'Active Employees' })
    await within(active).findByText('9')
    expect(within(active).queryByText('4')).not.toBeInTheDocument()
  })

  it('renders department rows exactly in backend order', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: /active employees by department/i })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
      ['Zeta Ops', '3'],
      ['Alpha IT', '1'],
    ])
  })

  it('shows a visible empty state for zero active employees while still showing the backend total', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue({ totalActiveEmployees: 0, departments: [] })
    renderPage()

    const active = await screen.findByRole('region', { name: 'Active Employees' })
    expect(await within(active).findByText('No active employees found.')).toBeInTheDocument()
    expect(within(active).getByText('0')).toBeInTheDocument()
    expect(within(active).queryByRole('table')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- pending leave

  it('renders page.totalElements as the pending count, never the content length', async () => {
    renderPage()

    const pending = await screen.findByRole('region', { name: 'Pending Leave Requests' })
    await within(pending).findByText('17')
    expect(within(pending).queryByText('1')).not.toBeInTheDocument()
  })

  it('never renders the over-fetched leave row details', async () => {
    renderPage()

    await within(await screen.findByRole('region', { name: 'Pending Leave Requests' })).findByText('17')
    expect(screen.queryByText(/Hidden Person Name/)).not.toBeInTheDocument()
    expect(screen.queryByText(/EMPSECRET/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Private medical reason/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Annual Leave/)).not.toBeInTheDocument()
    expect(screen.queryByText(/4242/)).not.toBeInTheDocument()
    expect(screen.queryByText(/777/)).not.toBeInTheDocument()
  })

  it('links the pending-leave section to /admin/leave', async () => {
    renderPage()

    const pending = await screen.findByRole('region', { name: 'Pending Leave Requests' })
    await within(pending).findByText('17')
    expect(within(pending).getByRole('link', { name: /review leave requests/i })).toHaveAttribute('href', '/admin/leave')
  })

  it('shows a visible empty state for zero pending leave requests', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue(EMPTY_PENDING_PAGE)
    renderPage()

    const pending = await screen.findByRole('region', { name: 'Pending Leave Requests' })
    expect(await within(pending).findByText('No leave requests are awaiting review.')).toBeInTheDocument()
    expect(within(pending).getByText('0')).toBeInTheDocument()
  })

  // ---------------------------------------------------------------- latest payroll run

  it('renders the latest run period via formatPayrollPeriod, its status as text, and its created date', async () => {
    renderPage()

    const payroll = await screen.findByRole('region', { name: 'Latest Payroll Run' })
    expect(await within(payroll).findByText('March 2026')).toBeInTheDocument()
    expect(within(payroll).getByText('CALCULATED')).toBeInTheDocument()
    expect(within(payroll).getByText('2 April 2026')).toBeInTheDocument()
  })

  it('links to the latest run detail without displaying its internal id as text', async () => {
    renderPage()

    const payroll = await screen.findByRole('region', { name: 'Latest Payroll Run' })
    await within(payroll).findByText('March 2026')
    expect(within(payroll).getByRole('link', { name: /view payroll run/i })).toHaveAttribute('href', '/admin/payroll/9876')
    expect(screen.queryByText(/9876/)).not.toBeInTheDocument()
  })

  it('never renders failureReason, rate snapshots, or version for the latest run', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue(
      payrollPage([payrollRun({ status: 'FAILED', failureReason: 'Employee EMP042 has no salary structure' })]),
    )
    renderPage()

    const payroll = await screen.findByRole('region', { name: 'Latest Payroll Run' })
    expect(await within(payroll).findByText('FAILED')).toBeInTheDocument()
    expect(screen.queryByText(/EMP042/)).not.toBeInTheDocument()
    expect(screen.queryByText(/0\.11/)).not.toBeInTheDocument()
  })

  it('shows a visible empty state when there is no payroll run', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue(payrollPage([]))
    renderPage()

    const payroll = await screen.findByRole('region', { name: 'Latest Payroll Run' })
    expect(await within(payroll).findByText('No payroll runs yet.')).toBeInTheDocument()
    expect(within(payroll).queryByRole('link', { name: /view payroll run/i })).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- loading independence

  it('shows an independent loading message for each section', () => {
    vi.mocked(reportsApi.getHeadcountReport).mockReturnValue(new Promise(() => {}))
    vi.mocked(leaveApi.listLeaveRequests).mockReturnValue(new Promise(() => {}))
    vi.mocked(payrollApi.listPayrollRuns).mockReturnValue(new Promise(() => {}))
    renderPage()

    expect(screen.getByText('Loading active employees...')).toBeInTheDocument()
    expect(screen.getByText('Loading pending leave requests...')).toBeInTheDocument()
    expect(screen.getByText('Loading latest payroll run...')).toBeInTheDocument()
  })

  it('resolving one section does not clear another section loading state', async () => {
    const headcount = deferred<HeadcountReport>()
    const pending = deferred<LeavePage<LeaveRequest>>()
    const payroll = deferred<PayrollPage<PayrollRun>>()
    vi.mocked(reportsApi.getHeadcountReport).mockReturnValue(headcount.promise)
    vi.mocked(leaveApi.listLeaveRequests).mockReturnValue(pending.promise)
    vi.mocked(payrollApi.listPayrollRuns).mockReturnValue(payroll.promise)
    renderPage()

    pending.resolve(PENDING_PAGE)
    await waitFor(() => expect(screen.queryByText('Loading pending leave requests...')).not.toBeInTheDocument())
    expect(screen.getByText('Loading active employees...')).toBeInTheDocument()
    expect(screen.getByText('Loading latest payroll run...')).toBeInTheDocument()

    headcount.resolve(HEADCOUNT)
    await waitFor(() => expect(screen.queryByText('Loading active employees...')).not.toBeInTheDocument())
    expect(screen.getByText('Loading latest payroll run...')).toBeInTheDocument()

    payroll.resolve(payrollPage([payrollRun()]))
    await waitFor(() => expect(screen.queryByText('Loading latest payroll run...')).not.toBeInTheDocument())
  })

  // ---------------------------------------------------------------- error independence

  it('a headcount failure shows a fixed safe alert and does not hide pending leave or payroll', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockRejectedValue(RAW_ERROR)
    renderPage()

    const active = await screen.findByRole('region', { name: 'Active Employees' })
    expect(await within(active).findByRole('alert')).toHaveTextContent('Could not load active employees. Please try again.')
    expect(await within(section('Pending Leave Requests')).findByText('17')).toBeInTheDocument()
    expect(await within(section('Latest Payroll Run')).findByText('March 2026')).toBeInTheDocument()
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('a pending-leave failure shows a fixed safe alert and does not hide headcount or payroll', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockRejectedValue(RAW_ERROR)
    renderPage()

    const pending = await screen.findByRole('region', { name: 'Pending Leave Requests' })
    expect(await within(pending).findByRole('alert')).toHaveTextContent(
      'Could not load pending leave requests. Please try again.',
    )
    expect(await within(section('Active Employees')).findByText('9')).toBeInTheDocument()
    expect(await within(section('Latest Payroll Run')).findByText('March 2026')).toBeInTheDocument()
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('a payroll failure shows a fixed safe alert and does not hide headcount or pending leave', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockRejectedValue(RAW_ERROR)
    renderPage()

    const payroll = await screen.findByRole('region', { name: 'Latest Payroll Run' })
    expect(await within(payroll).findByRole('alert')).toHaveTextContent('Could not load latest payroll run. Please try again.')
    expect(await within(section('Active Employees')).findByText('9')).toBeInTheDocument()
    expect(await within(section('Pending Leave Requests')).findByText('17')).toBeInTheDocument()
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('never renders raw backend, SQL, stack-trace, or internal error text', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockRejectedValue(RAW_ERROR)
    vi.mocked(leaveApi.listLeaveRequests).mockRejectedValue(new Error('java.lang.NullPointerException at Service.java:42'))
    vi.mocked(payrollApi.listPayrollRuns).mockRejectedValue(RAW_ERROR)
    renderPage()

    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(3))
    expect(screen.queryByText(/PSQLException|SELECT|com\.hrpayroll|NullPointerException|java\.lang|Service\.java/)).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- quick actions

  it('provides quick links to Employees, Leave Requests, Payroll, and Reports', () => {
    renderPage()

    const quick = section('Quick Actions')
    expect(within(quick).getByRole('link', { name: 'Employees' })).toHaveAttribute('href', '/admin/employees')
    expect(within(quick).getByRole('link', { name: 'Leave Requests' })).toHaveAttribute('href', '/admin/leave')
    expect(within(quick).getByRole('link', { name: 'Payroll' })).toHaveAttribute('href', '/admin/payroll')
    expect(within(quick).getByRole('link', { name: 'Reports' })).toHaveAttribute('href', '/admin/reports')
  })

  // ---------------------------------------------------------------- accessibility / scope

  it('has exactly one h1 "Admin Dashboard" and an h2 per dashboard section', () => {
    renderPage()

    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent('Admin Dashboard')
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)).toEqual([
      'Active Employees',
      'Pending Leave Requests',
      'Latest Payroll Run',
      'Quick Actions',
    ])
  })

  it('renders the department breakdown as a semantic table with an sr-only caption inside a positioned overflow-x-auto wrapper', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: 'Active employees by department' })
    expect(table.querySelector('caption')).toHaveClass('sr-only')
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Department',
      'Active Employees',
    ])
    const wrapper = table.parentElement!
    expect(wrapper).toHaveClass('relative')
    expect(wrapper).toHaveClass('overflow-x-auto')
  })

  it('introduces no chart, canvas, SVG, export, or download control', async () => {
    renderPage()
    await within(section('Latest Payroll Run')).findByText('March 2026')

    expect(document.querySelector('canvas')).not.toBeInTheDocument()
    expect(document.querySelector('svg')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/export|download/i)).not.toBeInTheDocument()
  })
})
