import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as payrollApi from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { PayslipListPage } from './PayslipListPage'

vi.mock('../../api/payroll')

function renderPage() {
  return render(
    <MemoryRouter>
      <PayslipListPage />
    </MemoryRouter>,
  )
}

function payslip(overrides: Partial<payrollApi.Payslip> = {}): payrollApi.Payslip {
  return {
    id: 100,
    payrollRunId: 999999,
    employeeId: 20,
    employeeCodeSnapshot: 'EMP001',
    employeeNameSnapshot: 'Jane Doe',
    departmentNameSnapshot: 'Engineering',
    jobTitleSnapshot: 'Engineer',
    basePay: 5000,
    housingAllowance: 500,
    transportAllowance: 200,
    grossPay: 5700,
    epfDeduction: 627,
    socsoDeduction: 28.5,
    eisDeduction: 11.4,
    incomeTax: 285,
    totalDeductions: 951.9,
    netPay: 4748.1,
    pdfStatus: 'PENDING',
    createdAt: '2099-01-01T00:00:00',
    periodMonth: 3,
    periodYear: 2026,
    ...overrides,
  }
}

const emptyPage: payrollApi.PagedResponse<payrollApi.Payslip> = {
  content: [],
  page: { size: 10, totalElements: 0, totalPages: 0, number: 0 },
}

function onePagePayslips(items: payrollApi.Payslip[]): payrollApi.PagedResponse<payrollApi.Payslip> {
  return { content: items, page: { size: 10, totalElements: items.length, totalPages: 1, number: 0 } }
}

function myr(amount: number): string {
  return formatMYR(amount).replace(/ /g, ' ')
}

describe('PayslipListPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(emptyPage)
  })

  // ---- Loading/data ----

  it('shows a loading state initially', () => {
    vi.mocked(payrollApi.listOwnPayslips).mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('calls listOwnPayslips(0, 10) on the initial load', async () => {
    renderPage()

    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenCalledWith(0, 10))
  })

  it('never passes or derives an employee id in the request', async () => {
    renderPage()

    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenCalled())
    expect(vi.mocked(payrollApi.listOwnPayslips).mock.calls[0]).toEqual([0, 10])
  })

  it('shows a fixed safe error message on API failure', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockRejectedValue({
      response: { data: { detail: 'Some backend-provided text that must not leak here' } },
    })

    renderPage()

    expect(await screen.findByText('Could not load your payslips. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('Some backend-provided text that must not leak here')).not.toBeInTheDocument()
  })

  it('renders the error with role="alert"', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockRejectedValue(new Error('network fail'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your payslips. Please try again.')
  })

  it('shows an empty state when there are no payslips', async () => {
    renderPage()

    expect(await screen.findByText(/no payslips/i)).toBeInTheDocument()
  })

  it('renders a populated table', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()

    expect(await screen.findByText('March 2026')).toBeInTheDocument()
  })

  // ---- Presentation ----

  it('derives the payroll period from periodMonth/periodYear, never createdAt', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(
      onePagePayslips([payslip({ periodMonth: 6, periodYear: 2027, createdAt: '2099-01-01T00:00:00' })]),
    )

    renderPage()

    expect(await screen.findByText('June 2027')).toBeInTheDocument()
    expect(screen.queryByText(/2099/)).not.toBeInTheDocument()
  })

  it('formats gross pay using formatMYR', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.getByText(myr(5700))).toBeInTheDocument()
  })

  it('formats total deductions using formatMYR', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.getByText(myr(951.9))).toBeInTheDocument()
  })

  it('formats net pay using formatMYR', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.getByText(myr(4748.1))).toBeInTheDocument()
  })

  it.each(['PENDING', 'GENERATED', 'FAILED'] as const)('renders %s PDF status as visible text', async (pdfStatus) => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip({ pdfStatus })]))

    renderPage()

    expect(await screen.findByText(pdfStatus)).toBeInTheDocument()
  })

  it('links View to the employee payslip detail route', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip({ id: 42 })]))

    renderPage()

    const link = await screen.findByRole('link', { name: /view/i })
    expect(link).toHaveAttribute('href', '/employee/payslips/42')
  })

  it('never renders a download button', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument()
  })

  it('never renders employee identity, payrollRunId, deduction breakdown, or pdfPath', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.queryByText('EMP001')).not.toBeInTheDocument()
    expect(screen.queryByText('Jane Doe')).not.toBeInTheDocument()
    expect(screen.queryByText('Engineering')).not.toBeInTheDocument()
    expect(screen.queryByText('Engineer')).not.toBeInTheDocument()
    expect(screen.queryByText(myr(500))).not.toBeInTheDocument()
    expect(screen.queryByText(myr(627))).not.toBeInTheDocument()
    expect(screen.queryByText('999999')).not.toBeInTheDocument()
    expect(screen.queryByText(/pdfPath/i)).not.toBeInTheDocument()
  })

  it('preserves backend row order', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(
      onePagePayslips([payslip({ id: 2, periodMonth: 1 }), payslip({ id: 1, periodMonth: 6 })]),
    )

    renderPage()
    await screen.findByText('June 2026')

    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('January 2026')).toBeInTheDocument()
    expect(within(rows[2]).getByText('June 2026')).toBeInTheDocument()
  })

  // ---- Pagination ----

  it('disables Previous on the first page and Next on the last page', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('Next loads the next page', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue({
      content: [payslip()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenLastCalledWith(1, 10))
  })

  it('Previous loads the previous page', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue({
      content: [payslip()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenLastCalledWith(1, 10))

    await user.click(screen.getByRole('button', { name: 'Previous' }))

    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenLastCalledWith(0, 10))
  })

  it('always requests page size 10', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue({
      content: [payslip()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenLastCalledWith(1, 10))
  })

  it('prevents duplicate in-flight page requests', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValueOnce({
      content: [payslip()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    let resolveNext!: (value: payrollApi.PagedResponse<payrollApi.Payslip>) => void
    vi.mocked(payrollApi.listOwnPayslips).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveNext = resolve
        }),
    )
    vi.mocked(payrollApi.listOwnPayslips).mockClear()

    const nextButton = screen.getByRole('button', { name: 'Next' })
    await user.click(nextButton)
    await user.click(nextButton)

    expect(payrollApi.listOwnPayslips).toHaveBeenCalledTimes(1)
    resolveNext({ content: [payslip()], page: { size: 10, totalElements: 25, totalPages: 3, number: 1 } })
  })

  it('clamps to the last available page when a fetch reports the current page is out of range', async () => {
    vi.mocked(payrollApi.listOwnPayslips)
      .mockResolvedValueOnce({
        content: [payslip()],
        page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
      })
      // Clicking Next requests page 1, but the response says the data has shrunk to a single page
      // -- simulating payslip data that changed between the initial load and this navigation.
      .mockResolvedValueOnce({ content: [], page: { size: 10, totalElements: 1, totalPages: 1, number: 1 } })
      .mockResolvedValueOnce({
        content: [payslip({ id: 1, periodMonth: 12 })],
        page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
      })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('December 2026')).toBeInTheDocument()
    await waitFor(() => expect(payrollApi.listOwnPayslips).toHaveBeenLastCalledWith(0, 10))
  })

  it('keeps pagination usable after a recoverable page-load error', async () => {
    vi.mocked(payrollApi.listOwnPayslips)
      .mockResolvedValueOnce({
        content: [payslip()],
        page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
      })
      .mockRejectedValueOnce(new Error('transient failure'))
      .mockResolvedValueOnce({
        content: [payslip()],
        page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
      })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Could not load your payslips. Please try again.')).toBeInTheDocument()

    const previousButton = screen.getByRole('button', { name: 'Previous' })
    expect(previousButton).toBeEnabled()
    await user.click(previousButton)

    expect(await screen.findByText('March 2026')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // jsdom performs no layout, so this guards the containment structure rather than pixel widths: the
  // sr-only "Actions" header is absolutely positioned and, without a positioned scroll wrapper, escapes
  // overflow-x-auto and widens the whole page (reproduced with the same DOM/CSS structure in an isolated
  // browser-engine check during Phase 12A).
  it('keeps the payslips table and its visually-hidden Actions header inside a positioned overflow-x-auto container', async () => {
    vi.mocked(payrollApi.listOwnPayslips).mockResolvedValue(onePagePayslips([payslip()]))
    renderPage()

    const table = await screen.findByRole('table', { name: 'My payslips' })
    await within(table).findByText('March 2026')
    const wrapper = table.parentElement!
    expect(wrapper).toHaveClass('overflow-x-auto')
    expect(wrapper).toHaveClass('relative')

    const actionsHeader = within(table).getByRole('columnheader', { name: 'Actions' })
    expect(within(actionsHeader).getByText('Actions')).toHaveClass('sr-only')
    expect(wrapper).toContainElement(actionsHeader)
  })
})
