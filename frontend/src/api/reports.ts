import { apiClient } from './client'

// Mirrors the backend's MonthlyPayrollCostResponse exactly. Named without a "Response" suffix,
// matching this project's established convention of dropping the backend DTO's suffix on the
// frontend (e.g. PayrollRunResponse -> PayrollRun, PayslipResponse -> Payslip).
export type MonthlyPayrollCost = {
  periodMonth: number
  totalGrossPay: number
}

// annualTotalGrossPay/totalGrossPay are the backend's authoritative aggregates (BigDecimal on the
// wire, a plain JS `number` here, same convention as every other payroll amount in this project,
// e.g. Payslip.netPay) -- this module never recomputes either from monthlyTotals.
export type PayrollCostReport = {
  year: number
  annualTotalGrossPay: number
  monthlyTotals: MonthlyPayrollCost[]
}

// Mirrors the backend's DepartmentHeadcountResponse exactly. Deliberately carries no employee
// identity or compensation field -- only department identity and an aggregate count.
export type DepartmentHeadcount = {
  departmentId: number
  departmentName: string
  activeEmployeeCount: number
}

// totalActiveEmployees is the backend's authoritative count -- this module never recomputes it by
// summing departments.
export type HeadcountReport = {
  totalActiveEmployees: number
  departments: DepartmentHeadcount[]
}

// HR_ADMIN-only on the backend; identity is never a caller-supplied parameter here -- the only
// input is the report year. The backend's CALCULATED/COMPLETED-only, month-ascending aggregation
// is trusted as-is; this module never re-sorts, re-sums, or fills in missing months.
export async function getPayrollCostReport(year: number): Promise<PayrollCostReport> {
  const response = await apiClient.get<PayrollCostReport>('/api/reports/payroll-cost', { params: { year } })
  return response.data
}

// HR_ADMIN-only on the backend; no parameters at all -- the backend's current-ACTIVE-only,
// name-then-id-ordered aggregation is trusted as-is; this module never re-sorts or re-sums.
export async function getHeadcountReport(): Promise<HeadcountReport> {
  const response = await apiClient.get<HeadcountReport>('/api/reports/headcount')
  return response.data
}
