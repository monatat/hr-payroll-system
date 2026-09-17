import { describe, expect, it } from 'vitest'
import { formatMYR } from './currency'

// Intl.NumberFormat('en-MY', ...) renders a U+00A0 NO-BREAK SPACE between the "RM" symbol and the
// digits (verified directly against this test environment's actual ICU output) -- not a regular
// U+0020 space -- so every expectation below must use  , not a literal space, to stay
// deterministic rather than coincidentally passing.
const NBSP = ' '

describe('formatMYR', () => {
  it('formats zero', () => {
    expect(formatMYR(0)).toBe(`RM${NBSP}0.00`)
  })

  it('formats a normal amount with two decimal places', () => {
    expect(formatMYR(4748.1)).toBe(`RM${NBSP}4,748.10`)
  })

  it('formats an amount in the thousands with a grouping separator', () => {
    expect(formatMYR(1234567.89)).toBe(`RM${NBSP}1,234,567.89`)
  })

  it('always renders exactly two decimal places', () => {
    expect(formatMYR(5)).toBe(`RM${NBSP}5.00`)
  })

  it('formats a negative amount', () => {
    expect(formatMYR(-50.5)).toBe(`-RM${NBSP}50.50`)
  })

  it('rejects NaN rather than silently rendering it as a payroll value', () => {
    expect(() => formatMYR(NaN)).toThrow()
  })

  it('rejects Infinity rather than silently rendering it as a payroll value', () => {
    expect(() => formatMYR(Infinity)).toThrow()
    expect(() => formatMYR(-Infinity)).toThrow()
  })
})
