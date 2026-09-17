import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { downloadPayslipPdf, fetchPayslipPdf, parsePayslipPdfFilename, saveBlobAsFile } from './payroll'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

describe('parsePayslipPdfFilename', () => {
  it('extracts a quoted filename', () => {
    expect(parsePayslipPdfFilename('attachment; filename="payslip-2026-03-EMP001.pdf"', 10)).toBe(
      'payslip-2026-03-EMP001.pdf',
    )
  })

  it('extracts an unquoted filename', () => {
    expect(parsePayslipPdfFilename('attachment; filename=payslip-2026-03-EMP001.pdf', 10)).toBe(
      'payslip-2026-03-EMP001.pdf',
    )
  })

  it('falls back when the header is missing', () => {
    expect(parsePayslipPdfFilename(undefined, 10)).toBe('payslip-10.pdf')
    expect(parsePayslipPdfFilename(null, 10)).toBe('payslip-10.pdf')
  })

  it('falls back on a blank quoted filename', () => {
    expect(parsePayslipPdfFilename('attachment; filename=""', 10)).toBe('payslip-10.pdf')
  })

  it('falls back on a malformed header with no filename param', () => {
    expect(parsePayslipPdfFilename('attachment', 10)).toBe('payslip-10.pdf')
  })

  it('strips unsafe characters instead of accepting them verbatim', () => {
    const result = parsePayslipPdfFilename('attachment; filename="bad name?.pdf"', 10)
    expect(result).not.toMatch(/[ ?]/)
    expect(result.endsWith('.pdf')).toBe(true)
  })

  it('never lets CR/LF survive into the resulting filename (header injection impossible)', () => {
    const result = parsePayslipPdfFilename('attachment; filename="a\r\nSet-Cookie: evil=1.pdf"', 10)
    expect(result).not.toMatch(/[\r\n]/)
  })

  it('rejects a semicolon-bearing raw value that leaves no .pdf extension', () => {
    const result = parsePayslipPdfFilename('attachment; filename=bad;name.pdf', 10)
    expect(result).toBe('payslip-10.pdf')
  })

  it('never contains a slash or backslash', () => {
    const result = parsePayslipPdfFilename('attachment; filename="sub/dir\\name.pdf"', 10)
    expect(result).not.toMatch(/[/\\]/)
  })

  it('rejects a path-traversal-shaped filename by falling back', () => {
    expect(parsePayslipPdfFilename('attachment; filename="../../etc/passwd.pdf"', 10)).toBe('payslip-10.pdf')
    expect(parsePayslipPdfFilename('attachment; filename="..\\..\\evil.pdf"', 10)).toBe('payslip-10.pdf')
  })

  it('rejects a bare "." or ".." filename by falling back', () => {
    expect(parsePayslipPdfFilename('attachment; filename="."', 10)).toBe('payslip-10.pdf')
    expect(parsePayslipPdfFilename('attachment; filename=".."', 10)).toBe('payslip-10.pdf')
  })

  it('falls back when there is no .pdf extension', () => {
    expect(parsePayslipPdfFilename('attachment; filename="payslip-2026-03-EMP001"', 10)).toBe('payslip-10.pdf')
  })

  it('never contains a quote or semicolon in the final result', () => {
    const result = parsePayslipPdfFilename('attachment; filename=bad"name.pdf', 10)
    expect(result).not.toMatch(/["; ]/)
    expect(result.endsWith('.pdf')).toBe(true)
  })
})

describe('fetchPayslipPdf', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset()
  })

  it('requests the PDF as a blob via apiClient', async () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    vi.mocked(apiClient.get).mockResolvedValue({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="payslip-2026-03-EMP001.pdf"' },
    })

    const result = await fetchPayslipPdf(10)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payslips/10/pdf', { responseType: 'blob' })
    expect(result.blob).toBe(blob)
    expect(result.filename).toBe('payslip-2026-03-EMP001.pdf')
  })

  it('uses the safe fallback filename when Content-Disposition is absent', async () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    vi.mocked(apiClient.get).mockResolvedValue({ data: blob, headers: {} })

    const result = await fetchPayslipPdf(99)

    expect(result.filename).toBe('payslip-99.pdf')
  })

  it('does not swallow request errors', async () => {
    const failure = new Error('download failed')
    vi.mocked(apiClient.get).mockRejectedValue(failure)

    await expect(fetchPayslipPdf(1)).rejects.toBe(failure)
  })
})

describe('saveBlobAsFile', () => {
  let createObjectURLSpy: ReturnType<typeof vi.fn>
  let revokeObjectURLSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    createObjectURLSpy = vi.fn(() => 'blob:mock-url')
    revokeObjectURLSpy = vi.fn()
    URL.createObjectURL = createObjectURLSpy as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = revokeObjectURLSpy as unknown as typeof URL.revokeObjectURL
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('creates an object URL, clicks a temporary anchor, then cleans up', () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    const clickSpy = vi.fn()
    const appendSpy = vi.spyOn(document.body, 'appendChild')
    const originalCreateElement = document.createElement.bind(document)
    let capturedAnchor: HTMLAnchorElement | undefined
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') {
        el.click = clickSpy
        capturedAnchor = el as HTMLAnchorElement
      }
      return el
    })

    saveBlobAsFile(blob, 'payslip-1.pdf')

    expect(createObjectURLSpy).toHaveBeenCalledWith(blob)
    expect(capturedAnchor?.href).toBe('blob:mock-url')
    expect(capturedAnchor?.download).toBe('payslip-1.pdf')
    expect(appendSpy).toHaveBeenCalledWith(capturedAnchor)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(document.body.contains(capturedAnchor!)).toBe(false)
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url')
  })

  it('still removes the anchor and revokes the object URL if click() throws', () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    const originalCreateElement = document.createElement.bind(document)
    let capturedAnchor: HTMLAnchorElement | undefined
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') {
        el.click = () => {
          throw new Error('click blocked')
        }
        capturedAnchor = el as HTMLAnchorElement
      }
      return el
    })

    expect(() => saveBlobAsFile(blob, 'payslip-1.pdf')).toThrow('click blocked')
    expect(document.body.contains(capturedAnchor!)).toBe(false)
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url')
  })

  it('revokes the object URL exactly once even if document.createElement("a") throws', () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    vi.spyOn(document, 'createElement').mockImplementation(() => {
      throw new Error('createElement blocked')
    })

    expect(() => saveBlobAsFile(blob, 'payslip-1.pdf')).toThrow('createElement blocked')
    expect(revokeObjectURLSpy).toHaveBeenCalledTimes(1)
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url')
  })

  it('revokes the object URL exactly once even if anchor.remove() throws during cleanup', () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') {
        el.click = vi.fn()
        el.remove = () => {
          throw new Error('remove blocked')
        }
      }
      return el
    })

    // The original cleanup failure may propagate, but the object URL must never leak.
    expect(() => saveBlobAsFile(blob, 'payslip-1.pdf')).toThrow('remove blocked')
    expect(revokeObjectURLSpy).toHaveBeenCalledTimes(1)
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url')
  })
})

describe('downloadPayslipPdf', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset()
    URL.createObjectURL = vi.fn(() => 'blob:mock-url') as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fetches then saves, never accepting a client-provided filename', async () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    vi.mocked(apiClient.get).mockResolvedValue({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="payslip-2026-03-EMP001.pdf"' },
    })
    const clickSpy = vi.fn()
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') el.click = clickSpy
      return el
    })

    // downloadPayslipPdf intentionally takes only an id -- there is no filename parameter to pass.
    await downloadPayslipPdf(10)

    expect(apiClient.get).toHaveBeenCalledWith('/api/payslips/10/pdf', { responseType: 'blob' })
    expect(clickSpy).toHaveBeenCalledTimes(1)
  })
})
