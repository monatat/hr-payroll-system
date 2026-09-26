import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HeadcountReport, PayrollCostReport } from '../../api/reports'
import * as reportsApi from '../../api/reports'
import { ReportsPage } from './ReportsPage'

vi.mock('../../api/reports', () => ({
  getPayrollCostReport: vi.fn(),
  getHeadcountReport: vi.fn(),
}))

const MIN_YEAR = 2000
const MAX_YEAR = 2100

function currentDefaultYear(): number {
  return Math.min(Math.max(new Date().getFullYear(), MIN_YEAR), MAX_YEAR)
}

// formatMYR renders a U+00A0 NO-BREAK SPACE between "RM" and the digits (see currency.test.ts),
// but Testing Library's default text normalizer collapses all whitespace -- NBSP included -- into
// a single regular space before a getByText/findByText comparison runs, so the DOM query below
// must use a plain space, not the raw NBSP formatMYR itself produces.
function rm(digits: string): string {
  return `RM ${digits}`
}

const FAKE_PAYROLL_REPORT: PayrollCostReport = {
  year: currentDefaultYear(),
  annualTotalGrossPay: 12345.67,
  monthlyTotals: [
    { periodMonth: 1, totalGrossPay: 1234.56 },
    { periodMonth: 2, totalGrossPay: 11111.11 },
  ],
}

const FAKE_HEADCOUNT_REPORT: HeadcountReport = {
  totalActiveEmployees: 8,
  departments: [
    { departmentId: 332, departmentName: 'IT', activeEmployeeCount: 5 },
    { departmentId: 401, departmentName: 'Sales', activeEmployeeCount: 3 },
  ],
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

function renderPage() {
  return render(<ReportsPage />)
}

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.mocked(reportsApi.getPayrollCostReport).mockReset()
    vi.mocked(reportsApi.getHeadcountReport).mockReset()
    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue(FAKE_PAYROLL_REPORT)
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue(FAKE_HEADCOUNT_REPORT)
  })

  // ---------------------------------------------------------------- initial rendering / loading

  it('renders the Reports heading', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Reports', level: 1 })).toBeInTheDocument()
  })

  it('renders a properly labelled year control defaulting to the clamped current year', () => {
    renderPage()

    const yearControl = screen.getByLabelText('Year')
    expect(yearControl).toBeInTheDocument()
    expect(yearControl).toHaveValue(String(currentDefaultYear()))
  })

  it('calls getPayrollCostReport with the initial selected year and getHeadcountReport with no argument', () => {
    renderPage()

    expect(reportsApi.getPayrollCostReport).toHaveBeenCalledWith(currentDefaultYear())
    expect(reportsApi.getPayrollCostReport).toHaveBeenCalledTimes(1)
    expect(reportsApi.getHeadcountReport).toHaveBeenCalledWith()
    expect(reportsApi.getHeadcountReport).toHaveBeenCalledTimes(1)
  })

  it('shows independent loading states for payroll and headcount while both requests are pending', async () => {
    const payrollDeferred = deferred<PayrollCostReport>()
    const headcountDeferred = deferred<HeadcountReport>()
    vi.mocked(reportsApi.getPayrollCostReport).mockReturnValue(payrollDeferred.promise)
    vi.mocked(reportsApi.getHeadcountReport).mockReturnValue(headcountDeferred.promise)

    renderPage()

    expect(screen.getByText(/loading payroll cost report/i)).toBeInTheDocument()
    expect(screen.getByText(/loading headcount report/i)).toBeInTheDocument()

    headcountDeferred.resolve(FAKE_HEADCOUNT_REPORT)
    await waitFor(() => expect(screen.queryByText(/loading headcount report/i)).not.toBeInTheDocument())
    expect(screen.getByText(/loading payroll cost report/i)).toBeInTheDocument()

    payrollDeferred.resolve(FAKE_PAYROLL_REPORT)
    await waitFor(() => expect(screen.queryByText(/loading payroll cost report/i)).not.toBeInTheDocument())
  })

  // ---------------------------------------------------------------- payroll cost success

  it('displays the backend annual total formatted with the MYR formatter', async () => {
    renderPage()

    expect(await screen.findByText(rm('12,345.67'))).toBeInTheDocument()
  })

  it('displays monthly rows in the backend order using the payroll-period and MYR formatters, without sorting or filling months', async () => {
    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue({
      year: currentDefaultYear(),
      annualTotalGrossPay: 999,
      monthlyTotals: [
        { periodMonth: 11, totalGrossPay: 300 },
        { periodMonth: 3, totalGrossPay: 200 },
      ],
    })

    renderPage()

    const table = await screen.findByRole('table', { name: /payroll/i })
    const rows = within(table).getAllByRole('row')
    // rows[0] is the header row.
    expect(within(rows[1]).getByText(`November ${currentDefaultYear()}`)).toBeInTheDocument()
    expect(within(rows[1]).getByText(rm('300.00'))).toBeInTheDocument()
    expect(within(rows[2]).getByText(`March ${currentDefaultYear()}`)).toBeInTheDocument()
    expect(within(rows[2]).getByText(rm('200.00'))).toBeInTheDocument()
    expect(rows).toHaveLength(3)
  })

  it('never computes annualTotalGrossPay from monthlyTotals -- renders the backend value even when inconsistent', async () => {
    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue({
      year: currentDefaultYear(),
      annualTotalGrossPay: 999999.99,
      monthlyTotals: [
        { periodMonth: 1, totalGrossPay: 100 },
        { periodMonth: 2, totalGrossPay: 200 },
      ],
    })

    renderPage()

    expect(await screen.findByText(rm('999,999.99'))).toBeInTheDocument()
    expect(screen.queryByText(rm('300.00'))).not.toBeInTheDocument()
  })

  it('shows the payroll empty state when monthlyTotals is empty, while still showing the authoritative annual zero', async () => {
    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue({
      year: currentDefaultYear(),
      annualTotalGrossPay: 0,
      monthlyTotals: [],
    })

    renderPage()

    expect(await screen.findByText('No payroll cost data for this year.')).toBeInTheDocument()
    expect(screen.getByText(rm('0.00'))).toBeInTheDocument()
  })

  // ---------------------------------------------------------------- headcount success

  it('displays totalActiveEmployees exactly as returned', async () => {
    renderPage()

    expect(await screen.findByText('8')).toBeInTheDocument()
  })

  it('displays department rows in the backend order with name and count, without sorting', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue({
      totalActiveEmployees: 4,
      departments: [
        { departmentId: 9, departmentName: 'Zulu', activeEmployeeCount: 1 },
        { departmentId: 1, departmentName: 'Alpha', activeEmployeeCount: 3 },
      ],
    })

    renderPage()

    const table = await screen.findByRole('table', { name: /headcount/i })
    const rows = within(table).getAllByRole('row')
    expect(within(rows[1]).getByText('Zulu')).toBeInTheDocument()
    expect(within(rows[1]).getByText('1')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Alpha')).toBeInTheDocument()
    expect(within(rows[2]).getByText('3')).toBeInTheDocument()
  })

  it('never computes totalActiveEmployees from departments -- renders the backend value even when inconsistent', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue({
      totalActiveEmployees: 999,
      departments: [
        { departmentId: 1, departmentName: 'IT', activeEmployeeCount: 5 },
        { departmentId: 2, departmentName: 'Sales', activeEmployeeCount: 3 },
      ],
    })

    renderPage()

    expect(await screen.findByText('999')).toBeInTheDocument()
  })

  it('shows the headcount empty state when departments is empty, while still showing the authoritative total zero', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockResolvedValue({ totalActiveEmployees: 0, departments: [] })

    renderPage()

    expect(await screen.findByText('No active employees found.')).toBeInTheDocument()
    const totalStats = await screen.findAllByText('0')
    expect(totalStats.length).toBeGreaterThan(0)
  })

  it('renders only department identity and aggregate count columns, no employee-level field', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: /headcount/i })
    const headerCells = within(table).getAllByRole('columnheader')
    expect(headerCells.map((cell) => cell.textContent)).toEqual(['Department', 'Active Employees'])
  })

  // ---------------------------------------------------------------- independent failures

  it('shows a fixed safe payroll error message and does not prevent headcount from rendering', async () => {
    vi.mocked(reportsApi.getPayrollCostReport).mockRejectedValue({
      response: { data: { detail: 'raw internal SQL failure xyz' } },
    })

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load payroll cost report. Please try again.')
    expect(screen.queryByText(/raw internal SQL failure/i)).not.toBeInTheDocument()
    expect(await screen.findByText('8')).toBeInTheDocument()
  })

  it('shows a fixed safe headcount error message and does not prevent payroll from rendering', async () => {
    vi.mocked(reportsApi.getHeadcountReport).mockRejectedValue({
      response: { data: { detail: 'raw internal SQL failure xyz' } },
    })

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load headcount report. Please try again.')
    expect(screen.queryByText(/raw internal SQL failure/i)).not.toBeInTheDocument()
    expect(await screen.findByText(rm('12,345.67'))).toBeInTheDocument()
  })

  it('retries payroll naturally when the year is changed after a failure', async () => {
    vi.mocked(reportsApi.getPayrollCostReport).mockRejectedValueOnce(new Error('network down'))
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('alert')

    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue(FAKE_PAYROLL_REPORT)
    await user.selectOptions(screen.getByLabelText('Year'), String(currentDefaultYear() + 1))

    expect(await screen.findByText(rm('12,345.67'))).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- year selection

  it('changing the year calls getPayrollCostReport with the new year and sends no other argument', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText(rm('12,345.67'))

    await user.selectOptions(screen.getByLabelText('Year'), String(currentDefaultYear() - 1))

    expect(reportsApi.getPayrollCostReport).toHaveBeenLastCalledWith(currentDefaultYear() - 1)
    expect(vi.mocked(reportsApi.getPayrollCostReport).mock.calls.at(-1)).toEqual([currentDefaultYear() - 1])
  })

  it('changing the year does not call getHeadcountReport again', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText(rm('12,345.67'))

    await user.selectOptions(screen.getByLabelText('Year'), String(currentDefaultYear() - 1))
    await screen.findByText(rm('12,345.67'))

    expect(reportsApi.getHeadcountReport).toHaveBeenCalledTimes(1)
  })

  it('enters a loading state for payroll while a new year request is pending', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText(rm('12,345.67'))

    const nextYearDeferred = deferred<PayrollCostReport>()
    vi.mocked(reportsApi.getPayrollCostReport).mockReturnValue(nextYearDeferred.promise)

    await user.selectOptions(screen.getByLabelText('Year'), String(currentDefaultYear() - 1))

    expect(screen.getByText(/loading payroll cost report/i)).toBeInTheDocument()

    nextYearDeferred.resolve({ year: currentDefaultYear() - 1, annualTotalGrossPay: 1, monthlyTotals: [] })
    await waitFor(() => expect(screen.queryByText(/loading payroll cost report/i)).not.toBeInTheDocument())
  })

  it('replaces the prior payroll report with the new response after a year change', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText(rm('12,345.67'))

    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue({
      year: currentDefaultYear() - 1,
      annualTotalGrossPay: 555,
      monthlyTotals: [],
    })

    await user.selectOptions(screen.getByLabelText('Year'), String(currentDefaultYear() - 1))

    expect(await screen.findByText(rm('555.00'))).toBeInTheDocument()
    expect(screen.queryByText(rm('12,345.67'))).not.toBeInTheDocument()
  })

  it('never lets a stale, slower response for an older selected year overwrite the newer selected-year response', async () => {
    const user = userEvent.setup()
    const olderYearDeferred = deferred<PayrollCostReport>()
    vi.mocked(reportsApi.getPayrollCostReport).mockReturnValue(olderYearDeferred.promise)
    renderPage()

    const olderYear = currentDefaultYear()
    const newerYear = currentDefaultYear() - 1
    vi.mocked(reportsApi.getPayrollCostReport).mockResolvedValue({
      year: newerYear,
      annualTotalGrossPay: 42,
      monthlyTotals: [],
    })

    await user.selectOptions(screen.getByLabelText('Year'), String(newerYear))
    await screen.findByText(rm('42.00'))

    // The older year's request resolves after the newer selection already rendered.
    olderYearDeferred.resolve({ year: olderYear, annualTotalGrossPay: 999, monthlyTotals: [] })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.getByText(rm('42.00'))).toBeInTheDocument()
    expect(screen.queryByText(rm('999.00'))).not.toBeInTheDocument()
  })

  it('does not create a duplicate payroll request on an ordinary rerender with the same selected year', async () => {
    const { rerender } = renderPage()
    await screen.findByText(rm('12,345.67'))

    // `rerender` re-renders the SAME mounted instance in place (not a fresh mount), so the
    // `[year]` effect must not re-run since `year` itself did not change.
    rerender(<ReportsPage />)
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(reportsApi.getPayrollCostReport).toHaveBeenCalledTimes(1)
  })

  it('only offers valid 2000-2100 year options in the year control', () => {
    renderPage()

    const options = screen.getAllByRole('option') as HTMLOptionElement[]
    expect(options).toHaveLength(MAX_YEAR - MIN_YEAR + 1)
    expect(options[0].value).toBe(String(MIN_YEAR))
    expect(options.at(-1)!.value).toBe(String(MAX_YEAR))
    expect(options.every((option) => Number(option.value) >= MIN_YEAR && Number(option.value) <= MAX_YEAR)).toBe(true)
  })

  // ---------------------------------------------------------------- accessibility / responsiveness

  it('renders the payroll table with semantic headers and an accessible caption', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: /payroll/i })
    const headerCells = within(table).getAllByRole('columnheader')
    expect(headerCells.map((cell) => cell.textContent)).toEqual(['Month', 'Gross Payroll Cost'])
  })

  it('renders the headcount table with semantic headers and an accessible caption', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: /headcount/i })
    expect(table).toBeInTheDocument()
  })

  it('wraps each table in the established overflow-x-auto responsive container', async () => {
    renderPage()
    await screen.findByText(rm('12,345.67'))

    const tables = screen.getAllByRole('table')
    for (const table of tables) {
      expect(table.parentElement).toHaveClass('overflow-x-auto')
    }
  })

  it('introduces no chart, canvas, or SVG-only visualization', async () => {
    renderPage()
    await screen.findByText(rm('12,345.67'))

    expect(document.querySelector('canvas')).not.toBeInTheDocument()
    expect(document.querySelector('svg')).not.toBeInTheDocument()
  })

  it('has no export/download control', async () => {
    renderPage()
    await screen.findByText(rm('12,345.67'))

    expect(screen.queryByRole('button', { name: /export/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /export/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument()
  })

  it('has no employee-identity input -- the year control is the only input', () => {
    renderPage()

    const controls = screen.getAllByRole('combobox').concat(screen.queryAllByRole('textbox'))
    expect(controls).toHaveLength(1)
    expect(controls[0]).toHaveAccessibleName('Year')
  })
})
