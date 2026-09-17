import { describe, expect, it } from 'vitest'
import { extractBlobProblemDetails, extractErrorCode, extractErrorMessage } from './errors'

function axiosBlobError(data: unknown, headers: Record<string, string> = {}) {
  return { response: { data, headers } }
}

describe('extractBlobProblemDetails', () => {
  it('parses an application/problem+json Blob (Blob.type-derived content type)', async () => {
    const blob = new Blob([JSON.stringify({ code: 'PAYSLIP_PDF_NOT_AVAILABLE', detail: 'PDF not ready.' })], {
      type: 'application/problem+json',
    })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: 'PAYSLIP_PDF_NOT_AVAILABLE', detail: 'PDF not ready.' })
  })

  it('parses a plain application/json Blob', async () => {
    const blob = new Blob([JSON.stringify({ code: 'PAYSLIP_NOT_FOUND', detail: 'Not found.' })], {
      type: 'application/json',
    })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: 'PAYSLIP_NOT_FOUND', detail: 'Not found.' })
  })

  it('falls back to the header-derived content type when Blob.type is empty', async () => {
    const blob = new Blob([JSON.stringify({ code: 'X', detail: 'Y' })])
    expect(blob.type).toBe('')

    const result = await extractBlobProblemDetails(
      axiosBlobError(blob, { 'content-type': 'application/problem+json' }),
    )

    expect(result).toEqual({ code: 'X', detail: 'Y' })
  })

  it('returns nulls for malformed JSON', async () => {
    const blob = new Blob(['{not valid json'], { type: 'application/json' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('returns nulls for an empty Blob', async () => {
    const blob = new Blob([], { type: 'application/json' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('returns nulls for a non-JSON Blob (e.g. an actual PDF)', async () => {
    const blob = new Blob(['%PDF-1.6 binary content'], { type: 'application/pdf' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('returns nulls when response.data is not a Blob', async () => {
    const result = await extractBlobProblemDetails(axiosBlobError({ code: 'X', detail: 'Y' }))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('returns nulls when code/detail are missing', async () => {
    const blob = new Blob([JSON.stringify({ somethingElse: true })], { type: 'application/json' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('returns nulls when code/detail have the wrong types', async () => {
    const blob = new Blob([JSON.stringify({ code: 123, detail: { nested: true } })], { type: 'application/json' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('parses JSON when the header says problem+json even though Blob.type is generic', async () => {
    const blob = new Blob([JSON.stringify({ code: 'A', detail: 'B' })], { type: 'application/octet-stream' })

    const result = await extractBlobProblemDetails(
      axiosBlobError(blob, { 'content-type': 'application/problem+json' }),
    )

    expect(result).toEqual({ code: 'A', detail: 'B' })
  })

  it('parses JSON when Blob.type says problem+json even though the header is generic', async () => {
    const blob = new Blob([JSON.stringify({ code: 'C', detail: 'D' })], { type: 'application/problem+json' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob, { 'content-type': 'application/octet-stream' }))

    expect(result).toEqual({ code: 'C', detail: 'D' })
  })

  it('returns nulls when neither Blob.type nor the header indicates JSON', async () => {
    const blob = new Blob([JSON.stringify({ code: 'E', detail: 'F' })], { type: 'application/octet-stream' })

    const result = await extractBlobProblemDetails(axiosBlobError(blob, { 'content-type': 'application/octet-stream' }))

    expect(result).toEqual({ code: null, detail: null })
  })

  it('never throws for a completely malformed error shape', async () => {
    await expect(extractBlobProblemDetails(null)).resolves.toEqual({ code: null, detail: null })
    await expect(extractBlobProblemDetails(undefined)).resolves.toEqual({ code: null, detail: null })
    await expect(extractBlobProblemDetails('not an error object')).resolves.toEqual({ code: null, detail: null })
    await expect(extractBlobProblemDetails(new Error('plain error, no response'))).resolves.toEqual({
      code: null,
      detail: null,
    })
  })
})

describe('existing extractErrorMessage/extractErrorCode behavior (unchanged)', () => {
  it('extractErrorMessage still reads response.data.detail', () => {
    const error = { response: { data: { detail: 'Something went wrong.' } } }
    expect(extractErrorMessage(error, 'fallback')).toBe('Something went wrong.')
  })

  it('extractErrorMessage still falls back when detail is absent', () => {
    expect(extractErrorMessage({}, 'fallback')).toBe('fallback')
  })

  it('extractErrorCode still reads response.data.code', () => {
    const error = { response: { data: { code: 'SOME_CODE' } } }
    expect(extractErrorCode(error)).toBe('SOME_CODE')
  })

  it('extractErrorCode still returns null when code is absent', () => {
    expect(extractErrorCode({})).toBeNull()
  })
})
