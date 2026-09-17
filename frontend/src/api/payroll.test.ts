import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import * as payrollApi from './payroll'
import type { PayrollRun, Payslip } from './payroll'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const FAKE_RUN: PayrollRun = {
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
}

const FAKE_PAYSLIP: Payslip = {
  id: 10,
  payrollRunId: 1,
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
}

const FAKE_RUN_PAGE = { content: [FAKE_RUN], page: { size: 20, totalElements: 1, totalPages: 1, number: 0 } }
const FAKE_PAYSLIP_PAGE = { content: [FAKE_PAYSLIP], page: { size: 20, totalElements: 1, totalPages: 1, number: 0 } }

describe('payroll API', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset()
    vi.mocked(apiClient.post).mockReset()
  })

  it('createPayrollRun sends exact body to POST /api/payroll-runs', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_RUN })

    const result = await payrollApi.createPayrollRun({ month: 3, year: 2026 })

    expect(apiClient.post).toHaveBeenCalledWith('/api/payroll-runs', { month: 3, year: 2026 })
    expect(result).toBe(FAKE_RUN)
  })

  it('listPayrollRuns sends status/year/page/size when provided', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_RUN_PAGE })

    await payrollApi.listPayrollRuns({ status: 'CALCULATED', year: 2026, page: 0, size: 10 })

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs', {
      params: { status: 'CALCULATED', year: 2026, page: 0, size: 10 },
    })
  })

  it('listPayrollRuns omits undefined status/year/page/size entirely', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_RUN_PAGE })

    await payrollApi.listPayrollRuns({})

    const call = vi.mocked(apiClient.get).mock.calls[0]
    expect(call[0]).toBe('/api/payroll-runs')
    expect(call[1]).toEqual({ params: {} })
    expect(Object.keys((call[1] as { params: object }).params)).toHaveLength(0)
  })

  it('listPayrollRuns defaults to no options at all', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_RUN_PAGE })

    await payrollApi.listPayrollRuns()

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs', { params: {} })
  })

  it('getPayrollRun requests the exact run path', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_RUN })

    const result = await payrollApi.getPayrollRun(42)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs/42')
    expect(result).toBe(FAKE_RUN)
  })

  it('listPayrollRunPayslips requests the run payslips path with pagination', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP_PAGE })

    const result = await payrollApi.listPayrollRunPayslips(7, 1, 25)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs/7/payslips', { params: { page: 1, size: 25 } })
    expect(result).toBe(FAKE_PAYSLIP_PAGE)
  })

  it('listPayrollRunPayslips preserves page 0 (falsy but valid)', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP_PAGE })

    await payrollApi.listPayrollRunPayslips(7, 0, 20)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs/7/payslips', { params: { page: 0, size: 20 } })
  })

  it('listPayrollRunPayslips omits page/size when not provided', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP_PAGE })

    await payrollApi.listPayrollRunPayslips(7)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payroll-runs/7/payslips', { params: {} })
  })

  it('retryPayrollRun sends POST with no business body', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_RUN })

    const result = await payrollApi.retryPayrollRun(5)

    expect(apiClient.post).toHaveBeenCalledWith('/api/payroll-runs/5/retry')
    expect(result).toBe(FAKE_RUN)
  })

  it('generatePayrollPdfs sends POST with no business body', async () => {
    const fakeResult = { payrollRunId: 5, status: 'COMPLETED' as const, generatedCount: 3, failedCount: 0, skippedCount: 0 }
    vi.mocked(apiClient.post).mockResolvedValue({ data: fakeResult })

    const result = await payrollApi.generatePayrollPdfs(5)

    expect(apiClient.post).toHaveBeenCalledWith('/api/payroll-runs/5/generate-pdfs')
    expect(result).toBe(fakeResult)
  })

  it('getPayslip requests the exact payslip path', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP })

    const result = await payrollApi.getPayslip(10)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payslips/10')
    expect(result).toBe(FAKE_PAYSLIP)
  })

  it('listOwnPayslips requests the self-service path with pagination', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP_PAGE })

    const result = await payrollApi.listOwnPayslips(2, 15)

    expect(apiClient.get).toHaveBeenCalledWith('/api/employees/me/payslips', { params: { page: 2, size: 15 } })
    expect(result).toBe(FAKE_PAYSLIP_PAGE)
  })

  it('listOwnPayslips never includes an employee id anywhere in the request', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYSLIP_PAGE })

    await payrollApi.listOwnPayslips()

    const [url, config] = vi.mocked(apiClient.get).mock.calls[0]
    expect(url).toBe('/api/employees/me/payslips')
    expect(url).not.toMatch(/\/\d+/)
    expect(config).not.toHaveProperty('params.employeeId')
  })

  it('does not swallow errors from apiClient.get', async () => {
    const failure = new Error('network unreachable')
    vi.mocked(apiClient.get).mockRejectedValue(failure)

    await expect(payrollApi.getPayrollRun(1)).rejects.toBe(failure)
  })

  it('does not swallow errors from apiClient.post', async () => {
    const failure = new Error('server exploded')
    vi.mocked(apiClient.post).mockRejectedValue(failure)

    await expect(payrollApi.createPayrollRun({ month: 1, year: 2026 })).rejects.toBe(failure)
  })
})
