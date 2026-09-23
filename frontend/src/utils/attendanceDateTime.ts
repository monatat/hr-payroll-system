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

const FALLBACK = '—'

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

// Backend LocalDateTime values look like "2026-03-10T09:00:00" or "...T09:00:00.123456" -- seconds
// and any fractional part are captured but deliberately never used (see formatAttendanceTime).
const TIME_PATTERN = /^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})(:\d{2}(\.\d+)?)?$/

// Never constructs a Date -- Date-based parsing is timezone-sensitive (the same instant can render
// as a different local day depending on the viewer's timezone), which would silently corrupt the
// backend's Asia/Kuala_Lumpur business date. A regex-based string parse sidesteps that entire class
// of bug, matching this project's existing formatPayrollPeriod convention exactly.
export function formatAttendanceDate(workDate: string): string {
  const match = DATE_PATTERN.exec(workDate)
  if (!match) return FALLBACK

  const [, year, monthStr, dayStr] = match
  const monthIndex = Number(monthStr) - 1
  const day = Number(dayStr)
  if (monthIndex < 0 || monthIndex > 11 || day < 1 || day > 31) return FALLBACK

  return `${day} ${MONTH_NAMES[monthIndex]} ${year}`
}

// Same never-construct-a-Date approach as formatAttendanceDate. Displays hour:minute in 12-hour
// form with an AM/PM suffix (e.g. "9:00 AM", "2:30 PM"); seconds and any fractional-second part are
// always dropped, never rounded or displayed, since attendance timestamps are shown to
// minute-level precision only.
export function formatAttendanceTime(value: string | null): string {
  if (value === null) return FALLBACK

  const match = TIME_PATTERN.exec(value)
  if (!match) return FALLBACK

  const hour24 = Number(match[1])
  const minute = Number(match[2])
  if (hour24 < 0 || hour24 > 23 || minute < 0 || minute > 59) return FALLBACK

  const period = hour24 < 12 ? 'AM' : 'PM'
  const hour12Raw = hour24 % 12
  const hour12 = hour12Raw === 0 ? 12 : hour12Raw
  const minuteStr = String(minute).padStart(2, '0')

  return `${hour12}:${minuteStr} ${period}`
}
