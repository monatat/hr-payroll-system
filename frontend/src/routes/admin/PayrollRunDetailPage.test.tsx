import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as payrollApi from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { PayrollRunDetailPage } from './PayrollRunDetailPage'

vi.mock('../../api/payroll')

function renderPage(id = '5') {
  return render(
    <MemoryRouter initialEntries={[`/admin/payroll/${id}`]}>
      <Routes>
        <Route path="/admin/payroll/:id" element={<PayrollRunDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function payrollRun(overrides: Partial<payrollApi.PayrollRun> = {}): payrollApi.PayrollRun {
  return {
    id: 5,
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
    updatedAt: '2026-03-02T10:05:00',
    ...overrides,
  }
}

function payslip(overrides: Partial<payrollApi.Payslip> = {}): payrollApi.Payslip {
  return {
    id: 100,
    payrollRunId: 5,
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
    createdAt: '2026-03-02T10:00:00',
    periodMonth: 3,
    periodYear: 2026,
    ...overrides,
  }
}

const emptyPayslipPage: payrollApi.PagedResponse<payrollApi.Payslip> = {
  content: [],
  page: { size: 10, totalElements: 0, totalPages: 0, number: 0 },
}

function onePagePayslips(items: payrollApi.Payslip[]): payrollApi.PagedResponse<payrollApi.Payslip> {
  return { content: items, page: { size: 10, totalElements: items.length, totalPages: 1, number: 0 } }
}

function axiosError(code: string, detail: string) {
  return { response: { data: { code, detail } } }
}

function blobProblemDetailError(code: string, detail: string, contentType = 'application/problem+json') {
  const blob = new Blob([JSON.stringify({ code, detail })], { type: contentType })
  return { response: { data: blob, headers: { 'content-type': contentType } } }
}

describe('PayrollRunDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun())
    vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(emptyPayslipPage)
  })

  // ---- Loading and route ----

  it('shows a loading state initially', () => {
    vi.mocked(payrollApi.getPayrollRun).mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it.each(['abc', '0', '-5', '3.5'])('makes no API call for an invalid id (%s)', async (id) => {
    renderPage(id)

    expect(await screen.findByText('Payroll run not found.')).toBeInTheDocument()
    expect(payrollApi.getPayrollRun).not.toHaveBeenCalled()
    expect(payrollApi.listPayrollRunPayslips).not.toHaveBeenCalled()
  })

  it('shows a safe error message when the initial load fails generically', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockRejectedValue({ response: { data: { detail: 'DB unreachable.' } } })

    renderPage()

    expect(await screen.findByText('DB unreachable.')).toBeInTheDocument()
  })

  it('renders a safe not-found message for an unknown run', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockRejectedValue(
      axiosError('PAYROLL_RUN_NOT_FOUND', 'Payroll run with id 999999 was not found.'),
    )

    renderPage('999999')

    expect(await screen.findByText('Payroll run not found.')).toBeInTheDocument()
  })

  it('links back to the payroll run list', async () => {
    renderPage()

    const link = await screen.findByRole('link', { name: /back to payroll runs/i })
    expect(link).toHaveAttribute('href', '/admin/payroll')
  })

  // ---- Summary ----

  it('renders the period from periodMonth/periodYear via formatPayrollPeriod', async () => {
    renderPage()

    expect(await screen.findByText('March 2026')).toBeInTheDocument()
  })

  it('renders all summary fields', async () => {
    renderPage()

    await screen.findByText('March 2026')
    expect(screen.getByText('2026-03-02T10:00:00')).toBeInTheDocument()
    expect(screen.getByText('2026-03-02T10:05:00')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('0.11')).toBeInTheDocument()
    expect(screen.getByText('0.005')).toBeInTheDocument()
    expect(screen.getByText('0.002')).toBeInTheDocument()
    expect(screen.getByText('0.05')).toBeInTheDocument()
    expect(screen.getByText(/demonstration/i)).toBeInTheDocument()
  })

  it('renders failureReason only when present', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'FAILED', failureReason: null }))
    renderPage()
    await screen.findByText('March 2026')
    expect(screen.queryByText(/failure reason/i)).not.toBeInTheDocument()
  })

  it('shows failureReason text when present', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(
      payrollRun({ status: 'FAILED', failureReason: 'No eligible employees for this period.' }),
    )
    renderPage()

    expect(await screen.findByText('No eligible employees for this period.')).toBeInTheDocument()
  })

  it.each(['PROCESSING', 'CALCULATED', 'COMPLETED', 'FAILED'] as const)(
    'renders the %s status as visible badge text',
    async (status) => {
      vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status }))
      renderPage()

      expect(await screen.findByText(status)).toBeInTheDocument()
    },
  )

  it('never renders pdfPath or an editable payroll control in the summary', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'COMPLETED' }))
    renderPage()
    await screen.findByText('March 2026')

    expect(screen.queryByText(/pdfPath/i)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
  })

  // ---- State-specific actions ----

  it('shows only Retry Payroll for a FAILED run', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'FAILED', failureReason: 'x' }))
    renderPage()

    expect(await screen.findByRole('button', { name: 'Retry Payroll' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate PDFs' })).not.toBeInTheDocument()
  })

  it('shows only Generate PDFs for a CALCULATED run', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'CALCULATED' }))
    renderPage()

    expect(await screen.findByRole('button', { name: 'Generate PDFs' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry Payroll' })).not.toBeInTheDocument()
  })

  it('shows neither action for a PROCESSING run', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'PROCESSING' }))
    renderPage()
    await screen.findByText('PROCESSING')

    expect(screen.queryByRole('button', { name: 'Retry Payroll' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate PDFs' })).not.toBeInTheDocument()
  })

  it('shows neither action for a COMPLETED run', async () => {
    vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'COMPLETED' }))
    renderPage()
    await screen.findByText('COMPLETED')

    expect(screen.queryByRole('button', { name: 'Retry Payroll' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate PDFs' })).not.toBeInTheDocument()
  })

  // ---- Retry ----

  describe('retry', () => {
    beforeEach(() => {
      vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'FAILED', failureReason: 'x' }))
    })

    it('requires confirmation before calling retryPayrollRun', async () => {
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))

      expect(payrollApi.retryPayrollRun).not.toHaveBeenCalled()
      expect(await screen.findByRole('button', { name: 'Confirm retry' })).toBeInTheDocument()
    })

    it('performs no request when confirmation is cancelled', async () => {
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(payrollApi.retryPayrollRun).not.toHaveBeenCalled()
      expect(screen.queryByRole('button', { name: 'Confirm retry' })).not.toBeInTheDocument()
    })

    it('prevents duplicate retry submission', async () => {
      let resolveRetry!: (value: payrollApi.PayrollRun) => void
      vi.mocked(payrollApi.retryPayrollRun).mockReturnValue(
        new Promise((resolve) => {
          resolveRetry = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      const confirmButton = screen.getByRole('button', { name: 'Confirm retry' })

      await user.click(confirmButton)
      expect(confirmButton).toBeDisabled()
      await user.click(confirmButton)

      expect(payrollApi.retryPayrollRun).toHaveBeenCalledTimes(1)
      resolveRetry(payrollRun({ status: 'CALCULATED' }))
    })

    it('re-fetches the run and payslips after a successful retry', async () => {
      vi.mocked(payrollApi.retryPayrollRun).mockResolvedValue(payrollRun({ status: 'CALCULATED' }))
      const user = userEvent.setup()
      renderPage()
      await waitFor(() => expect(payrollApi.getPayrollRun).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledTimes(1))

      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))

      await waitFor(() => expect(payrollApi.retryPayrollRun).toHaveBeenCalledWith(5))
      const successMessage = await screen.findByText(/payroll run retried/i)
      expect(successMessage.closest('[aria-live="polite"]')).not.toBeNull()
      await waitFor(() => expect(payrollApi.getPayrollRun).toHaveBeenCalledTimes(2))
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledTimes(2))
    })

    it('shows the backend safe detail for a recognized retry error', async () => {
      vi.mocked(payrollApi.retryPayrollRun).mockRejectedValue(
        axiosError('PAYROLL_RUN_NOT_FAILED', 'Only a FAILED payroll run can be retried.'),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))

      expect(await screen.findByText('Only a FAILED payroll run can be retried.')).toBeInTheDocument()
    })

    it('shows a fixed fallback for an unrecognized retry error', async () => {
      vi.mocked(payrollApi.retryPayrollRun).mockRejectedValue(
        axiosError('SOME_OTHER_CODE', 'Do not leak this internal detail.'),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))

      expect(await screen.findByText(/could not retry payroll run/i)).toBeInTheDocument()
      expect(screen.queryByText('Do not leak this internal detail.')).not.toBeInTheDocument()
    })

    it('keeps the page usable after a retry error', async () => {
      vi.mocked(payrollApi.retryPayrollRun)
        .mockRejectedValueOnce(axiosError('PAYROLL_RUN_FAILED', 'Calculation failed again.'))
        .mockResolvedValueOnce(payrollRun({ status: 'CALCULATED' }))
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))
      await screen.findByText('Calculation failed again.')

      // The confirmation stays open (rather than silently discarding it) so the user can retry --
      // same convention as LeaveRequestDetailPage's approve/reject confirm-on-error behavior.
      const confirmButton = screen.getByRole('button', { name: 'Confirm retry' })
      expect(confirmButton).toBeEnabled()
      await user.click(confirmButton)
      await waitFor(() => expect(payrollApi.retryPayrollRun).toHaveBeenCalledTimes(2))
    })
  })

  // ---- Generate ----

  describe('generate', () => {
    beforeEach(() => {
      vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'CALCULATED' }))
    })

    it('requires confirmation before calling generatePayrollPdfs', async () => {
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))

      expect(payrollApi.generatePayrollPdfs).not.toHaveBeenCalled()
      expect(await screen.findByRole('button', { name: 'Confirm generate' })).toBeInTheDocument()
    })

    it('performs no request when confirmation is cancelled', async () => {
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(payrollApi.generatePayrollPdfs).not.toHaveBeenCalled()
      expect(screen.queryByRole('button', { name: 'Confirm generate' })).not.toBeInTheDocument()
    })

    it('prevents duplicate generation submission', async () => {
      let resolveGenerate!: (value: payrollApi.PayrollPdfGenerationResult) => void
      vi.mocked(payrollApi.generatePayrollPdfs).mockReturnValue(
        new Promise((resolve) => {
          resolveGenerate = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      const confirmButton = screen.getByRole('button', { name: 'Confirm generate' })

      await user.click(confirmButton)
      expect(confirmButton).toBeDisabled()
      await user.click(confirmButton)

      expect(payrollApi.generatePayrollPdfs).toHaveBeenCalledTimes(1)
      resolveGenerate({ payrollRunId: 5, status: 'COMPLETED', generatedCount: 2, failedCount: 0, skippedCount: 0 })
    })

    it('shows a full-success banner when failedCount is 0', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockResolvedValue({
        payrollRunId: 5,
        status: 'COMPLETED',
        generatedCount: 3,
        failedCount: 0,
        skippedCount: 1,
      })
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      const banner = (await screen.findByText(/generated successfully/i)).closest('div')!
      expect(banner).toHaveClass('bg-emerald-50')
      expect(within(banner).getByText(/3/)).toBeInTheDocument()
      expect(within(banner).getByText(/1/)).toBeInTheDocument()
    })

    it('shows a visually distinct partial-success banner when failedCount > 0', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockResolvedValue({
        payrollRunId: 5,
        status: 'CALCULATED',
        generatedCount: 2,
        failedCount: 1,
        skippedCount: 0,
      })
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      const banner = (await screen.findByText(/could not be generated/i)).closest('div')!
      expect(banner).toHaveClass('bg-amber-50')
      expect(banner).not.toHaveClass('bg-emerald-50')
      expect(within(banner).getByText(/generate pdfs.*again/i)).toBeInTheDocument()
    })

    it('renders generated/failed/skipped counts in the result banner', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockResolvedValue({
        payrollRunId: 5,
        status: 'CALCULATED',
        generatedCount: 4,
        failedCount: 2,
        skippedCount: 3,
      })
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      const banner = (await screen.findByText(/could not be generated/i)).closest('div')!
      expect(within(banner).getByText(/4/)).toBeInTheDocument()
      expect(within(banner).getByText(/2/)).toBeInTheDocument()
      expect(within(banner).getByText(/3/)).toBeInTheDocument()
    })

    it('re-fetches the run and payslips after successful generation', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockResolvedValue({
        payrollRunId: 5,
        status: 'COMPLETED',
        generatedCount: 1,
        failedCount: 0,
        skippedCount: 0,
      })
      const user = userEvent.setup()
      renderPage()
      await waitFor(() => expect(payrollApi.getPayrollRun).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledTimes(1))

      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      await waitFor(() => expect(payrollApi.generatePayrollPdfs).toHaveBeenCalledWith(5))
      await waitFor(() => expect(payrollApi.getPayrollRun).toHaveBeenCalledTimes(2))
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledTimes(2))
    })

    it('removes Generate PDFs once the refreshed run is COMPLETED', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockResolvedValue({
        payrollRunId: 5,
        status: 'COMPLETED',
        generatedCount: 1,
        failedCount: 0,
        skippedCount: 0,
      })
      vi.mocked(payrollApi.getPayrollRun)
        .mockResolvedValueOnce(payrollRun({ status: 'CALCULATED' }))
        .mockResolvedValueOnce(payrollRun({ status: 'COMPLETED' }))
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      await waitFor(() => expect(screen.getByText('COMPLETED')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: 'Generate PDFs' })).not.toBeInTheDocument()
    })

    it('shows the backend safe detail for a recognized generation error', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockRejectedValue(
        axiosError('PAYROLL_RUN_HAS_NO_PAYSLIPS', 'This payroll run has no payslips to generate PDFs for.'),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      expect(await screen.findByText('This payroll run has no payslips to generate PDFs for.')).toBeInTheDocument()
    })

    it('shows a fixed fallback for an unrecognized generation error', async () => {
      vi.mocked(payrollApi.generatePayrollPdfs).mockRejectedValue(
        axiosError('SOME_OTHER_CODE', 'Do not leak this internal detail.'),
      )
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Generate PDFs' }))
      await user.click(screen.getByRole('button', { name: 'Confirm generate' }))

      expect(await screen.findByText(/could not generate payslip pdfs/i)).toBeInTheDocument()
      expect(screen.queryByText('Do not leak this internal detail.')).not.toBeInTheDocument()
    })
  })

  // ---- Payslips ----

  describe('payslips', () => {
    it('shows an empty state when there are no payslips', async () => {
      renderPage()

      expect(await screen.findByText(/no payslips/i)).toBeInTheDocument()
    })

    it('renders populated rows with snapshot fields', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(onePagePayslips([payslip()]))

      renderPage()

      expect(await screen.findByText('EMP001')).toBeInTheDocument()
      expect(screen.getByText('Jane Doe')).toBeInTheDocument()
      expect(screen.getByText('Engineering')).toBeInTheDocument()
      expect(screen.getByText('Engineer')).toBeInTheDocument()
    })

    it('formats every monetary field using formatMYR', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(onePagePayslips([payslip()]))

      renderPage()
      await screen.findByText('EMP001')

      // React Testing Library's default text normalizer collapses the NBSP that formatMYR emits
      // into a regular space before matching, so the expected string must be normalized the same
      // way rather than compared against formatMYR's raw (NBSP-containing) output.
      for (const amount of [5000, 500, 200, 5700, 627, 28.5, 11.4, 285, 951.9, 4748.1]) {
        expect(screen.getByText(formatMYR(amount).replace(/ /g, ' '))).toBeInTheDocument()
      }
    })

    it('preserves backend row ordering', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([
          payslip({ id: 2, employeeCodeSnapshot: 'EMP-B' }),
          payslip({ id: 1, employeeCodeSnapshot: 'EMP-A' }),
        ]),
      )

      renderPage()
      await screen.findByText('EMP-B')

      const rows = screen.getAllByRole('row')
      expect(within(rows[1]).getByText('EMP-B')).toBeInTheDocument()
      expect(within(rows[2]).getByText('EMP-A')).toBeInTheDocument()
    })

    it('disables Previous on the first page and Next on the last page', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(onePagePayslips([payslip()]))
      renderPage()
      await screen.findByText('EMP001')

      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    })

    it('requests page 0 with size 10 initially', async () => {
      renderPage()

      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledWith(5, 0, 10))
    })

    it('prevents duplicate in-flight page requests', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValueOnce({
        content: [payslip()],
        page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
      })
      const user = userEvent.setup()
      renderPage()
      await screen.findByText('EMP001')

      let resolveNext!: (value: payrollApi.PagedResponse<payrollApi.Payslip>) => void
      vi.mocked(payrollApi.listPayrollRunPayslips).mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveNext = resolve
          }),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockClear()

      const nextButton = screen.getByRole('button', { name: 'Next' })
      await user.click(nextButton)
      await user.click(nextButton)

      expect(payrollApi.listPayrollRunPayslips).toHaveBeenCalledTimes(1)
      resolveNext({ content: [payslip()], page: { size: 10, totalElements: 25, totalPages: 3, number: 1 } })
    })

    it('preserves the current payslip page after a successful retry refresh', async () => {
      vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'FAILED', failureReason: 'x' }))
      vi.mocked(payrollApi.retryPayrollRun).mockResolvedValue(payrollRun({ status: 'CALCULATED' }))
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue({
        content: [payslip()],
        page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
      })
      const user = userEvent.setup()
      renderPage()
      await screen.findByText('EMP001')
      await user.click(screen.getByRole('button', { name: 'Next' }))
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenLastCalledWith(5, 1, 10))

      await user.click(screen.getByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))

      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenLastCalledWith(5, 1, 10))
    })

    it('moves to the last available page when the refreshed total shrinks below the current page', async () => {
      vi.mocked(payrollApi.getPayrollRun).mockResolvedValue(payrollRun({ status: 'FAILED', failureReason: 'x' }))
      vi.mocked(payrollApi.retryPayrollRun).mockResolvedValue(payrollRun({ status: 'CALCULATED' }))
      vi.mocked(payrollApi.listPayrollRunPayslips)
        .mockResolvedValueOnce({
          content: [payslip()],
          page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
        })
        .mockResolvedValueOnce({
          content: [payslip({ id: 999, employeeCodeSnapshot: 'PAGE2' })],
          page: { size: 10, totalElements: 25, totalPages: 3, number: 1 },
        })
      const user = userEvent.setup()
      renderPage()
      await screen.findByText('EMP001')
      await user.click(screen.getByRole('button', { name: 'Next' }))
      await screen.findByText('PAGE2')

      vi.mocked(payrollApi.listPayrollRunPayslips)
        .mockResolvedValueOnce({
          content: [],
          page: { size: 10, totalElements: 1, totalPages: 1, number: 1 },
        })
        .mockResolvedValueOnce({
          content: [payslip({ id: 1, employeeCodeSnapshot: 'ONLY-ONE' })],
          page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
        })

      await user.click(screen.getByRole('button', { name: 'Retry Payroll' }))
      await user.click(screen.getByRole('button', { name: 'Confirm retry' }))

      expect(await screen.findByText('ONLY-ONE')).toBeInTheDocument()
      await waitFor(() => expect(payrollApi.listPayrollRunPayslips).toHaveBeenLastCalledWith(5, 0, 10))
    })

    it('never renders or forwards pdfPath', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(onePagePayslips([payslip()]))
      renderPage()

      await screen.findByText('EMP001')
      expect(screen.queryByText(/pdfPath/i)).not.toBeInTheDocument()
    })

    // jsdom performs no layout, so this guards the containment structure rather than pixel widths:
    // the sr-only "Actions" header is absolutely positioned, and without a positioned scroll wrapper
    // it escapes overflow-x-auto and widens the whole page (confirmed in a real browser, Phase 10E).
    it('keeps the table and its visually-hidden header inside a positioned overflow-x-auto container', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(onePagePayslips([payslip()]))
      renderPage()

      const table = await screen.findByRole('table', { name: 'Payslips for this payroll run' })
      await within(table).findByText('EMP001')
      const wrapper = table.parentElement!
      expect(wrapper).toHaveClass('overflow-x-auto')
      expect(wrapper).toHaveClass('relative')

      const actionsHeader = within(table).getByRole('columnheader', { name: 'Actions' })
      expect(within(actionsHeader).getByText('Actions')).toHaveClass('sr-only')
      expect(wrapper).toContainElement(actionsHeader)
    })
  })

  // ---- Download ----

  describe('download', () => {
    it('enables Download PDF for a GENERATED payslip', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]),
      )
      renderPage()

      const button = await screen.findByRole('button', { name: 'Download PDF' })
      expect(button).toBeEnabled()
    })

    it('does not call downloadPayslipPdf for a PENDING payslip', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'PENDING' })]),
      )
      renderPage()

      await screen.findByText(/not ready/i)
      expect(screen.queryByRole('button', { name: 'Download PDF' })).not.toBeInTheDocument()
      expect(payrollApi.downloadPayslipPdf).not.toHaveBeenCalled()
    })

    it('does not call downloadPayslipPdf for a FAILED payslip', async () => {
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'FAILED' })]),
      )
      renderPage()

      await screen.findByText(/generation failed/i)
      expect(screen.queryByRole('button', { name: 'Download PDF' })).not.toBeInTheDocument()
      expect(payrollApi.downloadPayslipPdf).not.toHaveBeenCalled()
    })

    it('calls downloadPayslipPdf with the correct payslip id', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockResolvedValue(undefined)
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ id: 777, pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      await waitFor(() => expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledWith(777))
    })

    it('prevents a duplicate download for the same row while in flight', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ id: 777, pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()
      const button = await screen.findByRole('button', { name: 'Download PDF' })

      await user.click(button)
      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledTimes(1)
      await user.click(screen.getByRole('button', { name: /downloading/i }))

      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledTimes(1)
      resolveDownload()
    })

    it('shows a row-level in-flight state while leaving other rows usable', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([
          payslip({ id: 1, employeeCodeSnapshot: 'ROW-1', pdfStatus: 'GENERATED' }),
          payslip({ id: 2, employeeCodeSnapshot: 'ROW-2', pdfStatus: 'GENERATED' }),
        ]),
      )
      const user = userEvent.setup()
      renderPage()
      await screen.findByText('ROW-1')

      const rows = screen.getAllByRole('row')
      const firstRowButton = within(rows[1]).getByRole('button', { name: 'Download PDF' })
      const secondRowButton = within(rows[2]).getByRole('button', { name: 'Download PDF' })

      await user.click(firstRowButton)

      expect(within(rows[1]).getByRole('button', { name: /downloading/i })).toBeDisabled()
      expect(secondRowButton).toBeEnabled()
      resolveDownload()
    })

    it('tracks two concurrent row downloads independently, without one clearing the other', async () => {
      const pending: Record<number, { resolve: () => void }> = {}
      vi.mocked(payrollApi.downloadPayslipPdf).mockImplementation(
        (payslipId: number) =>
          new Promise<void>((resolve) => {
            pending[payslipId] = { resolve }
          }),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([
          payslip({ id: 1, employeeCodeSnapshot: 'ROW-A', pdfStatus: 'GENERATED' }),
          payslip({ id: 2, employeeCodeSnapshot: 'ROW-B', pdfStatus: 'GENERATED' }),
        ]),
      )
      const user = userEvent.setup()
      renderPage()
      await screen.findByText('ROW-A')
      const rows = screen.getAllByRole('row')

      // 1-3: click A, verify A downloading+disabled, B still enabled.
      await user.click(within(rows[1]).getByRole('button', { name: 'Download PDF' }))
      expect(within(rows[1]).getByRole('button', { name: /downloading/i })).toBeDisabled()
      expect(within(rows[2]).getByRole('button', { name: 'Download PDF' })).toBeEnabled()

      // 4-5: click B while A is still pending -- both must independently show downloading.
      await user.click(within(rows[2]).getByRole('button', { name: 'Download PDF' }))
      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledWith(1)
      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledWith(2)
      expect(within(rows[1]).getByRole('button', { name: /downloading/i })).toBeDisabled()
      expect(within(rows[2]).getByRole('button', { name: /downloading/i })).toBeDisabled()

      // 6-7: resolving only A must not clear B's in-flight state (the state-overwrite defect).
      pending[1].resolve()
      await waitFor(() => expect(within(rows[1]).getByRole('button', { name: 'Download PDF' })).toBeEnabled())
      expect(within(rows[2]).getByRole('button', { name: /downloading/i })).toBeDisabled()

      // 8-9: a click on B while B is still pending must not fire a duplicate request.
      await user.click(within(rows[2]).getByRole('button', { name: /downloading/i }))
      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledTimes(2)

      // 10-11: resolving B returns it to normal too.
      pending[2].resolve()
      await waitFor(() => expect(within(rows[2]).getByRole('button', { name: 'Download PDF' })).toBeEnabled())
    })

    it('clears the in-flight state after a successful download', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()
      const button = await screen.findByRole('button', { name: 'Download PDF' })

      await user.click(button)
      expect(await screen.findByRole('button', { name: /downloading/i })).toBeInTheDocument()
      resolveDownload()

      expect(await screen.findByRole('button', { name: 'Download PDF' })).toBeEnabled()
    })

    it('renders a recognized JSON Blob ProblemDetail safely', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(
        blobProblemDetailError('PAYSLIP_PDF_NOT_AVAILABLE', 'This payslip PDF is not available yet.'),
      )
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('This payslip PDF is not available yet.')
    })

    it('shows the fixed fallback for a malformed/non-JSON Blob error', async () => {
      const malformedBlob = new Blob(['not json'], { type: 'application/pdf' })
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({ response: { data: malformedBlob, headers: {} } })
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
    })

    it('never exposes path or internal data in a download error', async () => {
      const blob = new Blob(
        [JSON.stringify({ code: 'PAYROLL_PDF_STORAGE_UNAVAILABLE', detail: 'Storage is unavailable.', path: '/data/payroll-pdfs/secret.pdf' })],
        { type: 'application/problem+json' },
      )
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({
        response: { data: blob, headers: { 'content-type': 'application/problem+json' } },
      })
      vi.mocked(payrollApi.listPayrollRunPayslips).mockResolvedValue(
        onePagePayslips([payslip({ pdfStatus: 'GENERATED' })]),
      )
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Storage is unavailable.')).toBeInTheDocument()
      expect(screen.queryByText(/data\/payroll-pdfs/)).not.toBeInTheDocument()
    })
  })
})
