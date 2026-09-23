import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatAttendanceDate, formatAttendanceTime } from './attendanceDateTime'

describe('formatAttendanceDate', () => {
  it('formats a valid workDate to the chosen "D Month YYYY" display', () => {
    expect(formatAttendanceDate('2026-03-10')).toBe('10 March 2026')
  })

  it('formats a single-digit day without a leading zero', () => {
    expect(formatAttendanceDate('2026-03-05')).toBe('5 March 2026')
  })

  it('formats invalid date input as the safe placeholder', () => {
    expect(formatAttendanceDate('not-a-date')).toBe('—')
  })

  it('formats an out-of-range month as the safe placeholder', () => {
    expect(formatAttendanceDate('2026-13-01')).toBe('—')
  })

  it('formats an empty string as the safe placeholder', () => {
    expect(formatAttendanceDate('')).toBe('—')
  })

  it('does not shift the date according to the host timezone', () => {
    vi.stubEnv('TZ', 'Pacific/Kiritimati') // UTC+14, far enough from Asia/Kuala_Lumpur (UTC+8)
    const farEast = formatAttendanceDate('2026-03-10')
    vi.stubEnv('TZ', 'Etc/GMT+12') // UTC-12
    const farWest = formatAttendanceDate('2026-03-10')
    expect(farEast).toBe('10 March 2026')
    expect(farWest).toBe('10 March 2026')
  })
})

describe('formatAttendanceTime', () => {
  it('formats a valid morning time', () => {
    expect(formatAttendanceTime('2026-03-10T09:00:00')).toBe('9:00 AM')
  })

  it('formats a valid afternoon time', () => {
    expect(formatAttendanceTime('2026-03-10T14:30:00')).toBe('2:30 PM')
  })

  it('formats a valid evening time', () => {
    expect(formatAttendanceTime('2026-03-10T23:15:00')).toBe('11:15 PM')
  })

  it('formats midnight as 12:00 AM', () => {
    expect(formatAttendanceTime('2026-03-11T00:00:00')).toBe('12:00 AM')
  })

  it('formats noon as 12:00 PM', () => {
    expect(formatAttendanceTime('2026-03-10T12:00:00')).toBe('12:00 PM')
  })

  it('ignores seconds, always displaying only hour and minute', () => {
    expect(formatAttendanceTime('2026-03-10T09:00:59')).toBe('9:00 AM')
  })

  it('ignores fractional seconds', () => {
    expect(formatAttendanceTime('2026-03-10T09:00:00.123456')).toBe('9:00 AM')
  })

  it('formats a null clockOutAt as the safe placeholder', () => {
    expect(formatAttendanceTime(null)).toBe('—')
  })

  it('formats invalid date-time input as the safe placeholder', () => {
    expect(formatAttendanceTime('not-a-datetime')).toBe('—')
  })

  it('formats an out-of-range hour as the safe placeholder', () => {
    expect(formatAttendanceTime('2026-03-10T25:00:00')).toBe('—')
  })

  it('formats a malformed string missing the time portion as the safe placeholder', () => {
    expect(formatAttendanceTime('2026-03-10')).toBe('—')
  })

  it('near-midnight Kuala Lumpur values retain their original business time', () => {
    expect(formatAttendanceTime('2026-03-10T23:59:00')).toBe('11:59 PM')
    expect(formatAttendanceTime('2026-03-11T00:01:00')).toBe('12:01 AM')
  })

  it('does not shift the time according to the host timezone', () => {
    vi.stubEnv('TZ', 'Pacific/Kiritimati')
    const farEast = formatAttendanceTime('2026-03-10T09:00:00')
    vi.stubEnv('TZ', 'Etc/GMT+12')
    const farWest = formatAttendanceTime('2026-03-10T09:00:00')
    expect(farEast).toBe('9:00 AM')
    expect(farWest).toBe('9:00 AM')
  })
})

afterEach(() => {
  vi.unstubAllEnvs()
})
