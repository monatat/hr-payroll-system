const MONTH_NAMES = [
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
]

// Never constructs a Date -- Date-based month formatting is timezone-sensitive (a date near
// midnight can render as the wrong local month/day depending on the viewer's timezone), which is
// unacceptable for a payroll period label that must read identically everywhere. A static lookup
// table sidesteps that entire class of bug.
export function formatPayrollPeriod(month: number, year: number): string {
  if (!Number.isInteger(month) || month < 1 || month > 12) {
    throw new Error(`formatPayrollPeriod: month must be an integer from 1 to 12, got ${month}`)
  }
  if (!Number.isInteger(year)) {
    throw new Error(`formatPayrollPeriod: year must be an integer, got ${year}`)
  }
  return `${MONTH_NAMES[month - 1]} ${year}`
}
