import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import * as reportsApi from './reports'
import type { DepartmentHeadcount, HeadcountReport, MonthlyPayrollCost, PayrollCostReport } from './reports'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const FAKE_MONTHLY_TOTALS: MonthlyPayrollCost[] = [
  { periodMonth: 1, totalGrossPay: 1234.56 },
  { periodMonth: 2, totalGrossPay: 2345.67 },
]

const FAKE_PAYROLL_COST_REPORT: PayrollCostReport = {
  year: 2026,
  annualTotalGrossPay: 12345.67,
  monthlyTotals: FAKE_MONTHLY_TOTALS,
}

const FAKE_DEPARTMENTS: DepartmentHeadcount[] = [
  { departmentId: 332, departmentName: 'IT', activeEmployeeCount: 5 },
  { departmentId: 401, departmentName: 'Sales', activeEmployeeCount: 3 },
]

const FAKE_HEADCOUNT_REPORT: HeadcountReport = {
  totalActiveEmployees: 8,
  departments: FAKE_DEPARTMENTS,
}

describe('reports API', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset()
    vi.mocked(apiClient.post).mockReset()
  })

  // ---------------------------------------------------------------- payroll cost

  it('getPayrollCostReport calls GET /api/reports/payroll-cost', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYROLL_COST_REPORT })

    await reportsApi.getPayrollCostReport(2026)

    const [url] = vi.mocked(apiClient.get).mock.calls[0]
    expect(url).toBe('/api/reports/payroll-cost')
  })

  it('getPayrollCostReport sends exactly { params: { year } } and no request body', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYROLL_COST_REPORT })

    await reportsApi.getPayrollCostReport(2026)

    expect(apiClient.get).toHaveBeenCalledWith('/api/reports/payroll-cost', { params: { year: 2026 } })
  })

  it('getPayrollCostReport never sends an employee/caller/actor/department/status/sort/page/size parameter', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYROLL_COST_REPORT })

    await reportsApi.getPayrollCostReport(2026)

    const [, config] = vi.mocked(apiClient.get).mock.calls[0]
    const params = (config as { params: Record<string, unknown> }).params
    expect(Object.keys(params)).toEqual(['year'])
    expect(params).not.toHaveProperty('employeeId')
    expect(params).not.toHaveProperty('userId')
    expect(params).not.toHaveProperty('actorId')
    expect(params).not.toHaveProperty('departmentId')
    expect(params).not.toHaveProperty('status')
    expect(params).not.toHaveProperty('sort')
    expect(params).not.toHaveProperty('page')
    expect(params).not.toHaveProperty('size')
  })

  it('getPayrollCostReport returns response.data unchanged', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_PAYROLL_COST_REPORT })

    const result = await reportsApi.getPayrollCostReport(2026)

    expect(result).toBe(FAKE_PAYROLL_COST_REPORT)
  })

  it('getPayrollCostReport preserves monthlyTotals order exactly', async () => {
    const outOfOrder: PayrollCostReport = {
      year: 2026,
      annualTotalGrossPay: 999,
      monthlyTotals: [
        { periodMonth: 11, totalGrossPay: 1 },
        { periodMonth: 3, totalGrossPay: 2 },
        { periodMonth: 7, totalGrossPay: 3 },
      ],
    }
    vi.mocked(apiClient.get).mockResolvedValue({ data: outOfOrder })

    const result = await reportsApi.getPayrollCostReport(2026)

    expect(result.monthlyTotals.map((m) => m.periodMonth)).toEqual([11, 3, 7])
  })

  it('getPayrollCostReport preserves an empty monthlyTotals array', async () => {
    const empty: PayrollCostReport = { year: 2026, annualTotalGrossPay: 0, monthlyTotals: [] }
    vi.mocked(apiClient.get).mockResolvedValue({ data: empty })

    const result = await reportsApi.getPayrollCostReport(2026)

    expect(result.monthlyTotals).toEqual([])
  })

  it('getPayrollCostReport never computes annualTotalGrossPay from monthlyTotals', async () => {
    // Deliberately inconsistent with the sum of monthlyTotals (1234.56 + 2345.67 = 3580.23) --
    // if this module ever recomputed the annual total client-side instead of trusting the
    // backend's authoritative value, the result would be 3580.23, not this value.
    const inconsistent: PayrollCostReport = {
      year: 2026,
      annualTotalGrossPay: 999999.99,
      monthlyTotals: FAKE_MONTHLY_TOTALS,
    }
    vi.mocked(apiClient.get).mockResolvedValue({ data: inconsistent })

    const result = await reportsApi.getPayrollCostReport(2026)

    expect(result.annualTotalGrossPay).toBe(999999.99)
  })

  it('getPayrollCostReport preserves decimal aggregate values exactly as received', async () => {
    const precise: PayrollCostReport = {
      year: 2026,
      annualTotalGrossPay: 0.03,
      monthlyTotals: [{ periodMonth: 1, totalGrossPay: 0.03 }],
    }
    vi.mocked(apiClient.get).mockResolvedValue({ data: precise })

    const result = await reportsApi.getPayrollCostReport(2026)

    expect(result.annualTotalGrossPay).toBe(0.03)
    expect(result.monthlyTotals[0].totalGrossPay).toBe(0.03)
  })

  it('getPayrollCostReport propagates a rejected apiClient error unchanged', async () => {
    const failure = new Error('network unreachable')
    vi.mocked(apiClient.get).mockRejectedValue(failure)

    await expect(reportsApi.getPayrollCostReport(2026)).rejects.toBe(failure)
  })

  // ---------------------------------------------------------------- headcount

  it('getHeadcountReport calls GET /api/reports/headcount', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_HEADCOUNT_REPORT })

    await reportsApi.getHeadcountReport()

    const [url] = vi.mocked(apiClient.get).mock.calls[0]
    expect(url).toBe('/api/reports/headcount')
  })

  it('getHeadcountReport calls apiClient.get with no params object or request body', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_HEADCOUNT_REPORT })

    await reportsApi.getHeadcountReport()

    expect(apiClient.get).toHaveBeenCalledWith('/api/reports/headcount')
    expect(vi.mocked(apiClient.get).mock.calls[0]).toHaveLength(1)
  })

  it('getHeadcountReport sends no employee/caller identity anywhere in the request', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_HEADCOUNT_REPORT })

    await reportsApi.getHeadcountReport()

    const [url, config] = vi.mocked(apiClient.get).mock.calls[0]
    expect(url).not.toMatch(/\/\d+/)
    expect(config).toBeUndefined()
  })

  it('getHeadcountReport returns response.data unchanged', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_HEADCOUNT_REPORT })

    const result = await reportsApi.getHeadcountReport()

    expect(result).toBe(FAKE_HEADCOUNT_REPORT)
  })

  it('getHeadcountReport preserves department ordering exactly', async () => {
    const outOfOrder: HeadcountReport = {
      totalActiveEmployees: 3,
      departments: [
        { departmentId: 9, departmentName: 'Zulu', activeEmployeeCount: 1 },
        { departmentId: 1, departmentName: 'Alpha', activeEmployeeCount: 2 },
      ],
    }
    vi.mocked(apiClient.get).mockResolvedValue({ data: outOfOrder })

    const result = await reportsApi.getHeadcountReport()

    expect(result.departments.map((d) => d.departmentId)).toEqual([9, 1])
  })

  it('getHeadcountReport preserves an empty departments array', async () => {
    const empty: HeadcountReport = { totalActiveEmployees: 0, departments: [] }
    vi.mocked(apiClient.get).mockResolvedValue({ data: empty })

    const result = await reportsApi.getHeadcountReport()

    expect(result.departments).toEqual([])
  })

  it('getHeadcountReport never computes totalActiveEmployees from departments', async () => {
    // Deliberately inconsistent with the sum of departments (5 + 3 = 8) -- if this module ever
    // recomputed the total client-side instead of trusting the backend's authoritative value,
    // the result would be 8, not this value.
    const inconsistent: HeadcountReport = {
      totalActiveEmployees: 999,
      departments: FAKE_DEPARTMENTS,
    }
    vi.mocked(apiClient.get).mockResolvedValue({ data: inconsistent })

    const result = await reportsApi.getHeadcountReport()

    expect(result.totalActiveEmployees).toBe(999)
  })

  it('getHeadcountReport propagates a rejected apiClient error unchanged', async () => {
    const failure = new Error('server exploded')
    vi.mocked(apiClient.get).mockRejectedValue(failure)

    await expect(reportsApi.getHeadcountReport()).rejects.toBe(failure)
  })

  // ---------------------------------------------------------------- type/contract checks

  // The typed object literals and runtime Object.keys assertions below provide complementary
  // compile-time/runtime checks for the approved representative shapes. The exact exported type
  // declarations in reports.ts remain the source of truth and were directly reviewed: each
  // declares exactly the fields asserted here, with no employee id/name/code/email/IC/
  // salary-structure/payslip field and no optional field beyond what is shown.
  it('MonthlyPayrollCost contains only the approved aggregate fields', () => {
    // The type annotation (not `as`) makes this an excess-property-checked object literal: an
    // extra or renamed field here would fail to compile. That check guards against a literal
    // drifting out of sync with the type, but -- like the Object.keys comparison below -- it is a
    // representative-sample check, not proof that the type declaration itself has no further
    // optional field; that fact was confirmed by directly reading reports.ts.
    const sample: MonthlyPayrollCost = { periodMonth: 1, totalGrossPay: 100 }
    expect(Object.keys(sample).sort()).toEqual(['periodMonth', 'totalGrossPay'])
  })

  it('PayrollCostReport contains only the approved aggregate fields', () => {
    const sample: PayrollCostReport = { year: 2026, annualTotalGrossPay: 100, monthlyTotals: [] }
    expect(Object.keys(sample).sort()).toEqual(['annualTotalGrossPay', 'monthlyTotals', 'year'])
  })

  it('DepartmentHeadcount contains only department identity and aggregate count fields', () => {
    const sample: DepartmentHeadcount = { departmentId: 1, departmentName: 'IT', activeEmployeeCount: 1 }
    expect(Object.keys(sample).sort()).toEqual(['activeEmployeeCount', 'departmentId', 'departmentName'])
  })

  it('HeadcountReport contains only the total and department-breakdown fields', () => {
    const sample: HeadcountReport = { totalActiveEmployees: 1, departments: [] }
    expect(Object.keys(sample).sort()).toEqual(['departments', 'totalActiveEmployees'])
  })

  // "No broad `any` type" and "no direct axios import or manual JWT logic" are source-text
  // properties, not runtime behavior -- a Node `fs`-based source scan was tried and rejected: this
  // project's browser-facing tsconfig.app.json (which type-checks every file under src, tests
  // included) declares no Node types, so any such test would only pass by adding @types/node to
  // that config, a dependency/config change outside Phase 10C's scope. Direct source review
  // confirms reports.ts imports only apiClient and contains no manual authorization/JWT logic; the
  // request-shape tests above independently verify the arguments used by the current API calls,
  // but (being tests of this module's present behavior, not of the file's import list) they do not
  // themselves guarantee that a hypothetical future unrelated direct-Axios call could never be
  // added elsewhere in the file.
  it('module exports exactly two HTTP functions', () => {
    const functionExports = Object.keys(reportsApi).filter(
      (key) => typeof (reportsApi as unknown as Record<string, unknown>)[key] === 'function',
    )
    expect(functionExports.sort()).toEqual(['getHeadcountReport', 'getPayrollCostReport'])
  })
})
