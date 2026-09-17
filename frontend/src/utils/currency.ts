const myrFormatter = new Intl.NumberFormat('en-MY', {
  style: 'currency',
  currency: 'MYR',
})

// Formats a monetary amount using the exact numeric convention already used by every payroll/
// salary API model (a plain JS `number`, e.g. Payslip.netPay) -- never a string amount requiring
// a second parse step, and never a manual comma-insertion hack. NaN/Infinity are rejected rather
// than silently rendered ("RM NaN"/"RM ∞"), since either could be mistaken by a reader for a real
// payslip figure.
export function formatMYR(amount: number): string {
  if (!Number.isFinite(amount)) {
    throw new Error(`formatMYR: amount must be a finite number, got ${amount}`)
  }
  return myrFormatter.format(amount)
}
