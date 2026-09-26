import { useEffect, useState } from 'react'
import { getHeadcountReport, getPayrollCostReport, type HeadcountReport, type PayrollCostReport } from '../../api/reports'
import { formatMYR } from '../../utils/currency'
import { formatPayrollPeriod } from '../../utils/payrollPeriod'

const MIN_YEAR = 2000
const MAX_YEAR = 2100
const YEAR_OPTIONS = Array.from({ length: MAX_YEAR - MIN_YEAR + 1 }, (_, index) => MIN_YEAR + index)

const PAYROLL_LOAD_ERROR = 'Could not load payroll cost report. Please try again.'
const HEADCOUNT_LOAD_ERROR = 'Could not load headcount report. Please try again.'

function defaultYear(): number {
  return Math.min(Math.max(new Date().getFullYear(), MIN_YEAR), MAX_YEAR)
}

/**
 * HR_ADMIN-only Reports page (Phase 10D): payroll cost for a selected year, and current ACTIVE
 * headcount. Payroll and headcount are two fully independent sections -- separate loading, error,
 * and data state, each backed by its own effect -- so a failure in one never blocks the other.
 * Renders every backend aggregate/order exactly as received (Phase 10C's reports.ts already
 * guarantees no recomputation happened before this); this component itself never sums, sorts,
 * filters, or fills in a missing month. No employee-level field ever reaches this page, since
 * neither API type carries one.
 */
export function ReportsPage() {
  const [year, setYear] = useState(defaultYear)

  const [payrollReport, setPayrollReport] = useState<PayrollCostReport | null>(null)
  const [payrollLoading, setPayrollLoading] = useState(true)
  const [payrollError, setPayrollError] = useState<string | null>(null)

  const [headcountReport, setHeadcountReport] = useState<HeadcountReport | null>(null)
  const [headcountLoading, setHeadcountLoading] = useState(true)
  const [headcountError, setHeadcountError] = useState<string | null>(null)

  // Re-run only when `year` changes -- a stale response from a previously selected year is
  // guarded by the closure-scoped `cancelled` flag, the same cancellation convention already used
  // by PayrollRunListPage/AttendancePage: changing `year` triggers this effect's cleanup (setting
  // the OLD closure's `cancelled` to true) before the new effect instance runs with its own fresh,
  // independent `cancelled` variable, so an older, slower response can never overwrite a newer one.
  useEffect(() => {
    let cancelled = false
    setPayrollLoading(true)
    setPayrollError(null)
    getPayrollCostReport(year)
      .then((data) => {
        if (!cancelled) setPayrollReport(data)
      })
      .catch(() => {
        if (cancelled) return
        setPayrollReport(null)
        setPayrollError(PAYROLL_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setPayrollLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [year])

  // No dependency on `year` -- headcount is current-ACTIVE-only and carries no year dimension, so
  // it is fetched exactly once, independent of the payroll year selector.
  useEffect(() => {
    let cancelled = false
    setHeadcountLoading(true)
    setHeadcountError(null)
    getHeadcountReport()
      .then((data) => {
        if (!cancelled) setHeadcountReport(data)
      })
      .catch(() => {
        if (cancelled) return
        setHeadcountReport(null)
        setHeadcountError(HEADCOUNT_LOAD_ERROR)
      })
      .finally(() => {
        if (!cancelled) setHeadcountLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <section className="space-y-6">
      <h1 className="text-xl font-semibold text-slate-900">Reports</h1>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Payroll Cost</h2>

        <div className="mt-3">
          <label htmlFor="reports-year" className="block text-sm font-medium text-slate-700">
            Year
          </label>
          <select
            id="reports-year"
            value={year}
            onChange={(event) => setYear(Number(event.target.value))}
            className="mt-1 w-32 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
          >
            {YEAR_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </div>

        {payrollLoading && <p className="mt-3 text-sm text-slate-500">Loading payroll cost report...</p>}
        {payrollError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {payrollError}
          </p>
        )}

        {!payrollLoading && !payrollError && payrollReport && (
          <>
            <p className="mt-3 text-sm text-slate-700">
              Annual Payroll Cost: <strong className="font-semibold text-slate-900">{formatMYR(payrollReport.annualTotalGrossPay)}</strong>
            </p>

            <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200">
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <caption className="sr-only">Monthly payroll cost</caption>
                <thead>
                  <tr className="text-left text-slate-500">
                    <th className="px-4 py-2">Month</th>
                    <th className="px-4 py-2">Gross Payroll Cost</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {payrollReport.monthlyTotals.map((row) => (
                    <tr key={row.periodMonth}>
                      <td className="px-4 py-2">{formatPayrollPeriod(row.periodMonth, year)}</td>
                      <td className="px-4 py-2">{formatMYR(row.totalGrossPay)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {payrollReport.monthlyTotals.length === 0 && (
                <p className="p-4 text-sm text-slate-500">No payroll cost data for this year.</p>
              )}
            </div>
          </>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Current Headcount</h2>

        {headcountLoading && <p className="mt-3 text-sm text-slate-500">Loading headcount report...</p>}
        {headcountError && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {headcountError}
          </p>
        )}

        {!headcountLoading && !headcountError && headcountReport && (
          <>
            <p className="mt-3 text-sm text-slate-700">
              Total Active Employees:{' '}
              <strong className="font-semibold text-slate-900">{headcountReport.totalActiveEmployees}</strong>
            </p>

            <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200">
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <caption className="sr-only">Active employee headcount by department</caption>
                <thead>
                  <tr className="text-left text-slate-500">
                    <th className="px-4 py-2">Department</th>
                    <th className="px-4 py-2">Active Employees</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {headcountReport.departments.map((row) => (
                    <tr key={row.departmentId}>
                      <td className="px-4 py-2">{row.departmentName}</td>
                      <td className="px-4 py-2">{row.activeEmployeeCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {headcountReport.departments.length === 0 && (
                <p className="p-4 text-sm text-slate-500">No active employees found.</p>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  )
}
