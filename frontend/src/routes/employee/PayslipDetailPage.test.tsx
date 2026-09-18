import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as payrollApi from '../../api/payroll'
import { formatMYR } from '../../utils/currency'
import { PayslipDetailPage } from './PayslipDetailPage'

vi.mock('../../api/payroll')

function renderPage(id = '100') {
  return render(
    <MemoryRouter initialEntries={[`/employee/payslips/${id}`]}>
      <Routes>
        <Route path="/employee/payslips/:id" element={<PayslipDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function payslip(overrides: Partial<payrollApi.Payslip> = {}): payrollApi.Payslip {
  return {
    id: 100,
    payrollRunId: 999999,
    employeeId: 888888,
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

function axiosError(code: string, detail: string) {
  return { response: { data: { code, detail } } }
}

function blobError(code: string, detail?: string, contentType = 'application/problem+json') {
  const body: Record<string, string> = { code }
  if (detail !== undefined) body.detail = detail
  const blob = new Blob([JSON.stringify(body)], { type: contentType })
  return { response: { data: blob, headers: { 'content-type': contentType } } }
}

function myr(amount: number): string {
  return formatMYR(amount).replace(/ /g, ' ')
}

describe('PayslipDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip())
  })

  // ---- Route/id/load ----

  it('shows a loading state initially', () => {
    vi.mocked(payrollApi.getPayslip).mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('calls getPayslip with the valid id', async () => {
    renderPage('42')

    await waitFor(() => expect(payrollApi.getPayslip).toHaveBeenCalledWith(42))
  })

  it.each(['abc', '0', '-5', '3.5'])('makes no API call for an invalid id (%s)', async (id) => {
    renderPage(id)

    expect(await screen.findByText('Payslip not found.')).toBeInTheDocument()
    expect(payrollApi.getPayslip).not.toHaveBeenCalled()
    expect(payrollApi.downloadPayslipPdf).not.toHaveBeenCalled()
  })

  it('renders "Payslip not found." for PAYSLIP_NOT_FOUND (unknown id)', async () => {
    vi.mocked(payrollApi.getPayslip).mockRejectedValue(axiosError('PAYSLIP_NOT_FOUND', 'Payslip 999999 not found.'))

    renderPage('999999')

    expect(await screen.findByText('Payslip not found.')).toBeInTheDocument()
    expect(screen.queryByText('Payslip 999999 not found.')).not.toBeInTheDocument()
  })

  it('renders the identical "Payslip not found." message for a non-owned payslip, never distinguishing it', async () => {
    // The backend returns the exact same PAYSLIP_NOT_FOUND code/shape for a non-owned payslip as
    // for an unknown one -- this test uses a different backend detail text to prove the frontend
    // still collapses both to the identical fixed string, never leaking which case occurred.
    vi.mocked(payrollApi.getPayslip).mockRejectedValue(
      axiosError('PAYSLIP_NOT_FOUND', 'Payslip 555 belongs to a different employee.'),
    )

    renderPage('555')

    expect(await screen.findByText('Payslip not found.')).toBeInTheDocument()
    expect(screen.queryByText(/different employee/i)).not.toBeInTheDocument()
  })

  it('uses the fixed fallback for any other load failure', async () => {
    vi.mocked(payrollApi.getPayslip).mockRejectedValue(new Error('network exploded'))

    renderPage()

    expect(await screen.findByText('Could not load your payslip. Please try again.')).toBeInTheDocument()
  })

  it('never renders arbitrary backend detail for a load failure', async () => {
    vi.mocked(payrollApi.getPayslip).mockRejectedValue({ response: { data: { detail: 'Leak this? No.' } } })

    renderPage()

    await screen.findByText('Could not load your payslip. Please try again.')
    expect(screen.queryByText('Leak this? No.')).not.toBeInTheDocument()
  })

  it('renders load errors with role="alert"', async () => {
    vi.mocked(payrollApi.getPayslip).mockRejectedValue(new Error('x'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your payslip. Please try again.')
  })

  it('links back to the payslip list', async () => {
    renderPage()

    const link = await screen.findByRole('link', { name: /back to my payslips/i })
    expect(link).toHaveAttribute('href', '/employee/payslips')
  })

  // ---- Presentation ----

  it('renders the period from periodMonth/periodYear', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ periodMonth: 6, periodYear: 2027 }))

    renderPage()

    expect(await screen.findByText('June 2027')).toBeInTheDocument()
  })

  it('renders the employee snapshot fields', async () => {
    renderPage()

    expect(await screen.findByText('EMP001')).toBeInTheDocument()
    expect(screen.getByText('Jane Doe')).toBeInTheDocument()
    expect(screen.getByText('Engineering')).toBeInTheDocument()
    expect(screen.getByText('Engineer')).toBeInTheDocument()
  })

  it('renders every earnings field using formatMYR', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.getByText(myr(5000))).toBeInTheDocument()
    expect(screen.getByText(myr(500))).toBeInTheDocument()
    expect(screen.getByText(myr(200))).toBeInTheDocument()
    expect(screen.getByText(myr(5700))).toBeInTheDocument()
  })

  it('renders every deduction field using formatMYR', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.getByText(myr(627))).toBeInTheDocument()
    expect(screen.getByText(myr(28.5))).toBeInTheDocument()
    expect(screen.getByText(myr(11.4))).toBeInTheDocument()
    expect(screen.getByText(myr(285))).toBeInTheDocument()
    expect(screen.getByText(myr(951.9))).toBeInTheDocument()
  })

  it('renders net pay using formatMYR, visually prominent', async () => {
    renderPage()

    const netPayValue = await screen.findByText(myr(4748.1))
    expect(netPayValue).toBeInTheDocument()
    expect(netPayValue.className).toMatch(/text-(xl|2xl|3xl|lg)|font-(bold|semibold)/)
  })

  it.each(['PENDING', 'GENERATED', 'FAILED'] as const)('renders %s PDF status as visible text', async (pdfStatus) => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus }))

    renderPage()

    expect(await screen.findByText(pdfStatus)).toBeInTheDocument()
  })

  it('never renders payrollRunId', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.queryByText('999999')).not.toBeInTheDocument()
  })

  it('never renders employeeId', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.queryByText('888888')).not.toBeInTheDocument()
  })

  it('never renders pdfPath', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.queryByText(/pdfPath/i)).not.toBeInTheDocument()
  })

  it('renders no editable payroll control', async () => {
    renderPage()
    await screen.findByText('EMP001')

    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
  })

  it('performs no client-side payroll calculation -- net pay is exactly the stored value', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(
      payslip({ grossPay: 1234.56, totalDeductions: 234.56, netPay: 999.99 }),
    )

    renderPage()

    // If the page computed netPay itself (grossPay - totalDeductions = 1000.00) instead of
    // rendering the stored netPay verbatim, this would fail.
    expect(await screen.findByText(myr(999.99))).toBeInTheDocument()
    expect(screen.queryByText(myr(1000))).not.toBeInTheDocument()
  })

  // ---- Availability ----

  it('shows an enabled Download PDF button for a GENERATED payslip', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus: 'GENERATED' }))

    renderPage()

    expect(await screen.findByRole('button', { name: 'Download PDF' })).toBeEnabled()
  })

  it('shows the not-ready message and no button for PENDING', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus: 'PENDING' }))

    renderPage()

    expect(await screen.findByText('Your payslip PDF is not ready yet.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument()
  })

  it('shows the contact-HR message and no button for FAILED', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus: 'FAILED' }))

    renderPage()

    expect(await screen.findByText('Your payslip PDF could not be generated. Please contact HR.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument()
  })

  it('never calls downloadPayslipPdf for PENDING', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus: 'PENDING' }))

    renderPage()
    await screen.findByText('Your payslip PDF is not ready yet.')

    expect(payrollApi.downloadPayslipPdf).not.toHaveBeenCalled()
  })

  it('never calls downloadPayslipPdf for FAILED', async () => {
    vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ pdfStatus: 'FAILED' }))

    renderPage()
    await screen.findByText('Your payslip PDF could not be generated. Please contact HR.')

    expect(payrollApi.downloadPayslipPdf).not.toHaveBeenCalled()
  })

  // ---- Download ----

  describe('download', () => {
    beforeEach(() => {
      vi.mocked(payrollApi.getPayslip).mockResolvedValue(payslip({ id: 555, pdfStatus: 'GENERATED' }))
    })

    it('calls downloadPayslipPdf with only the payslip id', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      await waitFor(() => expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledWith(555))
    })

    it('prevents duplicate download clicks while pending', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage('555')
      const button = await screen.findByRole('button', { name: 'Download PDF' })

      await user.click(button)
      await user.click(screen.getByRole('button', { name: /downloading/i }))

      expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledTimes(1)
      resolveDownload()
    })

    it('shows "Downloading..." and disables the button while pending', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(screen.getByRole('button', { name: 'Downloading...' })).toBeDisabled()
      resolveDownload()
    })

    it('restores the button after a successful download', async () => {
      let resolveDownload!: () => void
      vi.mocked(payrollApi.downloadPayslipPdf).mockReturnValue(
        new Promise((resolve) => {
          resolveDownload = resolve
        }),
      )
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
      resolveDownload()

      expect(await screen.findByRole('button', { name: 'Download PDF' })).toBeEnabled()
    })

    it('clears an earlier error after a subsequent successful download', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf)
        .mockRejectedValueOnce(blobError('PAYSLIP_PDF_NOT_AVAILABLE', 'Not available yet.'))
        .mockResolvedValueOnce(undefined)
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
      await screen.findByText('Not available yet.')

      await user.click(screen.getByRole('button', { name: 'Download PDF' }))

      await waitFor(() => expect(screen.queryByText('Not available yet.')).not.toBeInTheDocument())
    })

    it('restores the button after a failed download and permits retry', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf)
        .mockRejectedValueOnce(new Error('boom'))
        .mockResolvedValueOnce(undefined)
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
      const button = await screen.findByRole('button', { name: 'Download PDF' })
      expect(button).toBeEnabled()

      await user.click(button)

      await waitFor(() => expect(payrollApi.downloadPayslipPdf).toHaveBeenCalledTimes(2))
    })

    it('shows the PAYSLIP_NOT_FOUND Blob error', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(blobError('PAYSLIP_NOT_FOUND', 'Payslip not found.'))
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Payslip not found.')
    })

    it('shows the PAYSLIP_PDF_NOT_AVAILABLE Blob error', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(
        blobError('PAYSLIP_PDF_NOT_AVAILABLE', 'Your payslip PDF is not available.'),
      )
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Your payslip PDF is not available.')
    })

    it('shows the PAYSLIP_PDF_FILE_MISSING Blob error', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(
        blobError('PAYSLIP_PDF_FILE_MISSING', 'The payslip PDF file is unavailable. Please contact HR.'),
      )
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The payslip PDF file is unavailable. Please contact HR.',
      )
    })

    it('shows the PAYROLL_PDF_STORAGE_UNAVAILABLE Blob error', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(
        blobError('PAYROLL_PDF_STORAGE_UNAVAILABLE', 'The payslip PDF service is unavailable. Please try again later.'),
      )
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The payslip PDF service is unavailable. Please try again later.',
      )
    })

    it('uses the code-specific fallback when a recognized code has no detail', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(blobError('PAYSLIP_PDF_NOT_AVAILABLE'))
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Your payslip PDF is not available.')).toBeInTheDocument()
    })

    it('uses the code-specific fallback when the detail is an empty string', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(blobError('PAYSLIP_PDF_FILE_MISSING', ''))
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('The payslip PDF file is unavailable. Please contact HR.')).toBeInTheDocument()
    })

    it('uses the generic fallback for malformed JSON', async () => {
      const blob = new Blob(['{not valid json'], { type: 'application/json' })
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({ response: { data: blob, headers: {} } })
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
    })

    it('uses the generic fallback for a non-JSON Blob', async () => {
      const blob = new Blob(['%PDF-1.6 binary junk'], { type: 'application/pdf' })
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({ response: { data: blob, headers: {} } })
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
    })

    it('uses the generic fallback for an empty Blob', async () => {
      const blob = new Blob([], { type: 'application/json' })
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({ response: { data: blob, headers: {} } })
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
    })

    it('uses the generic fallback for an unknown code', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(blobError('SOME_OTHER_CODE', 'Do not leak this.'))
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
      expect(screen.queryByText('Do not leak this.')).not.toBeInTheDocument()
    })

    it('uses the generic fallback for an unexpected (non-Blob) error shape', async () => {
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue(new Error('unexpected'))
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Could not download payslip PDF. Please try again.')).toBeInTheDocument()
    })

    it('never exposes a planted path/internal value in a download error', async () => {
      const blob = new Blob(
        [
          JSON.stringify({
            code: 'PAYROLL_PDF_STORAGE_UNAVAILABLE',
            detail: 'Storage unavailable.',
            path: '/data/payroll-pdfs/secret.pdf',
          }),
        ],
        { type: 'application/problem+json' },
      )
      vi.mocked(payrollApi.downloadPayslipPdf).mockRejectedValue({
        response: { data: blob, headers: { 'content-type': 'application/problem+json' } },
      })
      const user = userEvent.setup()
      renderPage('555')

      await user.click(await screen.findByRole('button', { name: 'Download PDF' }))

      expect(await screen.findByText('Storage unavailable.')).toBeInTheDocument()
      expect(screen.queryByText(/data\/payroll-pdfs/)).not.toBeInTheDocument()
    })
  })
})
