import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as salaryApi from '../../api/salaryStructures'
import { SalaryHistoryPage } from './SalaryHistoryPage'

vi.mock('../../api/salaryStructures')

describe('SalaryHistoryPage (EMPLOYEE, read-only)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('renders own salary history using the self-service endpoint', async () => {
    vi.mocked(salaryApi.getOwnSalaryHistory).mockResolvedValue([
      {
        id: 1,
        employeeId: 7,
        basePay: 4000,
        housingAllowance: 0,
        transportAllowance: 0,
        effectiveFrom: '2026-01-01',
        effectiveTo: null,
      },
    ])

    render(<SalaryHistoryPage />)

    expect(await screen.findByText('4000')).toBeInTheDocument()
    expect(screen.getByText('Present')).toBeInTheDocument()
    expect(salaryApi.getOwnSalaryHistory).toHaveBeenCalledTimes(1)
  })

  it('renders no create, edit, or delete controls', async () => {
    vi.mocked(salaryApi.getOwnSalaryHistory).mockResolvedValue([])

    render(<SalaryHistoryPage />)

    await screen.findByText('No salary structures yet.')

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('shows a load error from the API', async () => {
    vi.mocked(salaryApi.getOwnSalaryHistory).mockRejectedValue({
      response: { data: { detail: 'Employee profile not found' } },
    })

    render(<SalaryHistoryPage />)

    expect(await screen.findByText('Employee profile not found')).toBeInTheDocument()
  })
})
