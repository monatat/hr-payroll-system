import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as payrollApi from '../../api/payroll'
import { PayrollRunListPage } from './PayrollRunListPage'

vi.mock('../../api/payroll')

function renderPage() {
  return render(
    <MemoryRouter>
      <PayrollRunListPage />
    </MemoryRouter>,
  )
}

function payrollRun(overrides: Partial<payrollApi.PayrollRun> = {}): payrollApi.PayrollRun {
  return {
    id: 1,
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
    version: 1,
    createdAt: '2026-03-02T10:00:00',
    updatedAt: '2026-03-02T10:00:00',
    ...overrides,
  }
}

const emptyPage: payrollApi.PagedResponse<payrollApi.PayrollRun> = {
  content: [],
  page: { size: 10, totalElements: 0, totalPages: 0, number: 0 },
}

async function revealCreateForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Create Payroll Run' }))
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, month: string, year: string) {
  await revealCreateForm(user)
  await user.selectOptions(screen.getByLabelText('Month'), month)
  await user.clear(screen.getByLabelText('Year'))
  if (year) await user.type(screen.getByLabelText('Year'), year)
  await user.click(screen.getByRole('button', { name: 'Create' }))
}

describe('PayrollRunListPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue(emptyPage)
  })

  it('shows a loading state initially', () => {
    renderPage()

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('calls listPayrollRuns with page 0, size 10, and no status/year filters initially', async () => {
    renderPage()

    await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenCalledWith({ page: 0, size: 10 }))
  })

  it('shows a safe error message on API failure', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockRejectedValue({ response: { data: { detail: 'DB down' } } })

    renderPage()

    expect(await screen.findByText('DB down')).toBeInTheDocument()
  })

  it('falls back to a safe generic error message when the backend gives no detail', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockRejectedValue(new Error('network exploded'))

    renderPage()

    expect(await screen.findByText(/could not load payroll runs/i)).toBeInTheDocument()
  })

  it('shows an empty state when there are no runs', async () => {
    renderPage()

    expect(await screen.findByText(/no payroll runs/i)).toBeInTheDocument()
  })

  it('renders populated rows with period, status, created date, and a View link', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [
        payrollRun({ id: 42, periodMonth: 3, periodYear: 2026, status: 'CALCULATED', createdAt: '2026-03-02T10:00:00' }),
      ],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()

    expect(await screen.findByText('March 2026')).toBeInTheDocument()
    expect(screen.getByText('CALCULATED')).toBeInTheDocument()
    expect(screen.getByText('2026-03-02 10:00')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /view/i })
    expect(link).toHaveAttribute('href', '/admin/payroll/42')
  })

  it.each(['PROCESSING', 'CALCULATED', 'COMPLETED', 'FAILED'] as const)(
    'renders the %s status badge with visible text',
    async (status) => {
      vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
        content: [payrollRun({ status })],
        page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
      })

      renderPage()

      expect(await screen.findByText(status)).toBeInTheDocument()
    },
  )

  it('sends the selected status filter and resets to page 0', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenLastCalledWith({ page: 1, size: 10 }))

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'FAILED')
    await waitFor(() =>
      expect(payrollApi.listPayrollRuns).toHaveBeenLastCalledWith({ page: 0, size: 10, status: 'FAILED' }),
    )
  })

  it('sends the entered year filter and resets to page 0', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenLastCalledWith({ page: 1, size: 10 }))

    await user.type(screen.getByLabelText('Filter by year'), '2026')
    await waitFor(() =>
      expect(payrollApi.listPayrollRuns).toHaveBeenLastCalledWith({ page: 0, size: 10, year: 2026 }),
    )
  })

  it('disables Previous on the first page and Next on the last page', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun()],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()
    await screen.findByText('Page 1 of 1')

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('disables Previous/Next while a page load is in flight', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    let resolveNext!: (value: payrollApi.PagedResponse<payrollApi.PayrollRun>) => void
    vi.mocked(payrollApi.listPayrollRuns).mockReturnValue(
      new Promise((resolve) => {
        resolveNext = resolve
      }),
    )

    const nextButton = screen.getByRole('button', { name: 'Next' })
    await user.click(nextButton)
    expect(nextButton).toBeDisabled()

    resolveNext({ content: [payrollRun()], page: { size: 10, totalElements: 25, totalPages: 3, number: 1 } })
    await waitFor(() => expect(nextButton).not.toBeDisabled())
  })

  it('does not send duplicate page requests from rapid double clicks on Next', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValueOnce({
      content: [payrollRun()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    let resolveNext!: (value: payrollApi.PagedResponse<payrollApi.PayrollRun>) => void
    vi.mocked(payrollApi.listPayrollRuns).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveNext = resolve
        }),
    )
    vi.mocked(payrollApi.listPayrollRuns).mockClear()

    const nextButton = screen.getByRole('button', { name: 'Next' })
    // The first click starts a request that never resolves until we tell it to -- the button
    // becomes disabled once loading is true, so a second click while it's still pending (the
    // real "duplicate click" scenario) must not fire a second request.
    await user.click(nextButton)
    await user.click(nextButton)

    expect(payrollApi.listPayrollRuns).toHaveBeenCalledTimes(1)
    resolveNext({ content: [payrollRun()], page: { size: 10, totalElements: 25, totalPages: 3, number: 1 } })
  })

  it('does not re-sort backend content client-side', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun({ id: 2, periodMonth: 1 }), payrollRun({ id: 1, periodMonth: 6 })],
      page: { size: 10, totalElements: 2, totalPages: 1, number: 0 },
    })

    renderPage()
    await screen.findByText('June 2026')

    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('January 2026')).toBeInTheDocument()
    expect(within(rows[2]).getByText('June 2026')).toBeInTheDocument()
  })

  it('never exposes payroll amounts, rate snapshots, or pdfPath in the list', async () => {
    vi.mocked(payrollApi.listPayrollRuns).mockResolvedValue({
      content: [payrollRun()],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()
    await screen.findByText('March 2026')

    expect(screen.queryByText(/pdfPath/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/0\.11/)).not.toBeInTheDocument()
    expect(screen.queryByText(/0\.005/)).not.toBeInTheDocument()
  })

  describe('create-run form', () => {
    it('reveals the form on click and hides it again on Cancel', async () => {
      const user = userEvent.setup()
      renderPage()
      await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenCalled())

      expect(screen.queryByLabelText('Month')).not.toBeInTheDocument()
      await revealCreateForm(user)
      expect(screen.getByLabelText('Month')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Cancel' }))
      expect(screen.queryByLabelText('Month')).not.toBeInTheDocument()
    })

    it('offers all 12 named months and constrains year to 2000-2100', async () => {
      const user = userEvent.setup()
      renderPage()
      await revealCreateForm(user)

      const monthSelect = screen.getByLabelText('Month') as HTMLSelectElement
      const optionLabels = Array.from(monthSelect.options).map((option) => option.textContent)
      expect(optionLabels).toEqual([
        'January',
        'February',
        'March',
        'April',
        'May',
        'June',
        'July',
        'August',
        'September',
        'October',
        'November',
        'December',
      ])

      const yearInput = screen.getByLabelText('Year')
      expect(yearInput).toHaveAttribute('min', '2000')
      expect(yearInput).toHaveAttribute('max', '2100')
    })

    it('does not call createPayrollRun for an out-of-range year', async () => {
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '1999')

      expect(payrollApi.createPayrollRun).not.toHaveBeenCalled()
      expect(await screen.findByRole('alert')).toHaveTextContent(/year/i)
    })

    it('does not call createPayrollRun when year is blank', async () => {
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '')

      expect(payrollApi.createPayrollRun).not.toHaveBeenCalled()
      expect(await screen.findByRole('alert')).toBeInTheDocument()
    })

    it('successfully creates a run, shows success feedback, and re-fetches page 0', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockResolvedValue(payrollRun({ id: 99, periodMonth: 4, periodYear: 2027 }))
      const user = userEvent.setup()
      renderPage()
      await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenCalledTimes(1))

      await fillAndSubmit(user, '4', '2027')

      await waitFor(() => expect(payrollApi.createPayrollRun).toHaveBeenCalledWith({ month: 4, year: 2027 }))
      const successMessage = await screen.findByText(/payroll run created/i)
      expect(successMessage.closest('[aria-live="polite"]')).not.toBeNull()
      await waitFor(() => expect(payrollApi.listPayrollRuns).toHaveBeenCalledTimes(2))
      expect(payrollApi.listPayrollRuns).toHaveBeenLastCalledWith({ page: 0, size: 10 })
      expect(screen.queryByLabelText('Month')).not.toBeInTheDocument()
    })

    it('does not navigate automatically after a successful creation', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockResolvedValue(payrollRun({ id: 99 }))
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '4', '2027')

      await screen.findByText(/payroll run created/i)
      expect(screen.getByText('Payroll Runs')).toBeInTheDocument()
    })

    it('prevents duplicate submission while a create request is in flight', async () => {
      let resolveCreate!: (value: payrollApi.PayrollRun) => void
      vi.mocked(payrollApi.createPayrollRun).mockReturnValue(
        new Promise((resolve) => {
          resolveCreate = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage()
      await revealCreateForm(user)
      await user.selectOptions(screen.getByLabelText('Month'), '5')
      await user.clear(screen.getByLabelText('Year'))
      await user.type(screen.getByLabelText('Year'), '2028')

      const submitButton = screen.getByRole('button', { name: 'Create' })
      await user.click(submitButton)
      expect(submitButton).toBeDisabled()
      await user.click(submitButton)

      expect(payrollApi.createPayrollRun).toHaveBeenCalledTimes(1)
      resolveCreate(payrollRun())
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Create' })).not.toBeInTheDocument())
    })

    it('shows the backend safe detail for PAYROLL_RUN_PERIOD_ALREADY_EXISTS', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockRejectedValue({
        response: {
          data: { code: 'PAYROLL_RUN_PERIOD_ALREADY_EXISTS', detail: 'A payroll run for March 2026 already exists.' },
        },
      })
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '2026')

      expect(await screen.findByText('A payroll run for March 2026 already exists.')).toBeInTheDocument()
    })

    it('shows the backend safe detail for PAYROLL_RUN_FAILED without exposing internals', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockRejectedValue({
        response: { data: { code: 'PAYROLL_RUN_FAILED', detail: 'No eligible employees for this period.' } },
      })
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '2026')

      expect(await screen.findByText('No eligible employees for this period.')).toBeInTheDocument()
    })

    it('shows a generic fallback for an unrecognized error code and never leaks its detail', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockRejectedValue({
        response: { data: { code: 'SOME_OTHER_CODE', detail: 'Do not leak this internal detail.' } },
      })
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '2026')

      expect(await screen.findByText(/could not create payroll run/i)).toBeInTheDocument()
      expect(screen.queryByText('Do not leak this internal detail.')).not.toBeInTheDocument()
    })

    it('reports errors with role="alert"', async () => {
      vi.mocked(payrollApi.createPayrollRun).mockRejectedValue({
        response: { data: { code: 'PAYROLL_RUN_FAILED', detail: 'Calculation failed.' } },
      })
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '2026')

      expect(await screen.findByRole('alert')).toHaveTextContent('Calculation failed.')
    })

    it('keeps the form usable after a failed request', async () => {
      vi.mocked(payrollApi.createPayrollRun)
        .mockRejectedValueOnce({ response: { data: { code: 'PAYROLL_RUN_FAILED', detail: 'Calculation failed.' } } })
        .mockResolvedValueOnce(payrollRun())
      const user = userEvent.setup()
      renderPage()

      await fillAndSubmit(user, '3', '2026')
      await screen.findByText('Calculation failed.')

      await user.click(screen.getByRole('button', { name: 'Create' }))
      await waitFor(() => expect(payrollApi.createPayrollRun).toHaveBeenCalledTimes(2))
    })
  })
})
