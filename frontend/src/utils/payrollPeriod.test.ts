import { describe, expect, it, vi } from 'vitest'
import { formatPayrollPeriod } from './payrollPeriod'

describe('formatPayrollPeriod', () => {
  it('formats January', () => {
    expect(formatPayrollPeriod(1, 2026)).toBe('January 2026')
  })

  it('formats December', () => {
    expect(formatPayrollPeriod(12, 2026)).toBe('December 2026')
  })

  it('formats a normal middle month', () => {
    expect(formatPayrollPeriod(3, 2026)).toBe('March 2026')
  })

  it('never constructs a Date or depends on browser timezone', () => {
    const dateSpy = vi.spyOn(globalThis, 'Date')
    try {
      formatPayrollPeriod(6, 2026)
      expect(dateSpy).not.toHaveBeenCalled()
    } finally {
      dateSpy.mockRestore()
    }
  })

  it('rejects month 0', () => {
    expect(() => formatPayrollPeriod(0, 2026)).toThrow()
  })

  it('rejects month 13', () => {
    expect(() => formatPayrollPeriod(13, 2026)).toThrow()
  })

  it('rejects a fractional month', () => {
    expect(() => formatPayrollPeriod(3.5, 2026)).toThrow()
  })

  it('rejects a non-integer year', () => {
    expect(() => formatPayrollPeriod(3, 2026.5)).toThrow()
  })
})
