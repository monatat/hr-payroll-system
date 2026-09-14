import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as employeesApi from '../../api/employees'
import * as salaryApi from '../../api/salaryStructures'
import { SalaryHistoryPage } from './SalaryHistoryPage'

vi.mock('../../api/employees')
vi.mock('../../api/salaryStructures')

const EMPLOYEE = {
  id: 7,
  employeeCode: 'E7',
  fullName: 'Ada Lovelace',
  departmentId: 1,
  departmentName: 'Engineering',
  jobTitle: 'Engineer',
  employmentStatus: 'ACTIVE' as const,
  hireDate: '2020-01-01',
  phoneNumber: null,
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/admin/employees/7/salary']}>
      <Routes>
        <Route path="/admin/employees/:id/salary" element={<SalaryHistoryPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SalaryHistoryPage (HR_ADMIN)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(employeesApi.getEmployee).mockResolvedValue(EMPLOYEE)
  })

  it('renders an open-ended and a closed salary structure', async () => {
    vi.mocked(salaryApi.listSalaryStructures).mockResolvedValue([
      {
        id: 1,
        employeeId: 7,
        basePay: 4000,
        housingAllowance: 0,
        transportAllowance: 0,
        effectiveFrom: '2026-01-01',
        effectiveTo: '2026-06-30',
      },
      {
        id: 2,
        employeeId: 7,
        basePay: 4500,
        housingAllowance: 200,
        transportAllowance: 100,
        effectiveFrom: '2026-07-01',
        effectiveTo: null,
      },
    ])

    renderPage()

    expect(await screen.findByText('4000')).toBeInTheDocument()
    expect(screen.getByText('2026-06-30')).toBeInTheDocument()
    expect(screen.getByText('4500')).toBeInTheDocument()
    expect(screen.getByText('Present')).toBeInTheDocument()
  })

  it('submits the create form and refreshes the list on success', async () => {
    vi.mocked(salaryApi.listSalaryStructures).mockResolvedValue([])
    vi.mocked(salaryApi.createSalaryStructure).mockResolvedValue({
      id: 10,
      employeeId: 7,
      basePay: 5000,
      housingAllowance: 0,
      transportAllowance: 0,
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('No salary structures yet.')

    await user.type(screen.getByLabelText('Base pay'), '5000')
    await user.type(screen.getByLabelText('Effective from'), '2026-01-01')
    await user.click(screen.getByRole('button', { name: 'Create salary structure' }))

    await waitFor(() => expect(salaryApi.createSalaryStructure).toHaveBeenCalledWith(7, {
      basePay: 5000,
      housingAllowance: undefined,
      transportAllowance: undefined,
      effectiveFrom: '2026-01-01',
      effectiveTo: undefined,
    }))
  })

  it('shows the 409 overlap error returned by the API', async () => {
    vi.mocked(salaryApi.listSalaryStructures).mockResolvedValue([])
    vi.mocked(salaryApi.createSalaryStructure).mockRejectedValue({
      response: {
        data: { detail: 'The proposed salary structure period overlaps an existing salary structure for this employee.' },
      },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('No salary structures yet.')

    await user.type(screen.getByLabelText('Base pay'), '5000')
    await user.type(screen.getByLabelText('Effective from'), '2026-01-01')
    await user.click(screen.getByRole('button', { name: 'Create salary structure' }))

    expect(
      await screen.findByText('The proposed salary structure period overlaps an existing salary structure for this employee.'),
    ).toBeInTheDocument()
  })

  it('shows a 400 validation error returned by the API', async () => {
    vi.mocked(salaryApi.listSalaryStructures).mockResolvedValue([])
    vi.mocked(salaryApi.createSalaryStructure).mockRejectedValue({
      response: { data: { detail: 'effectiveTo cannot be before effectiveFrom' } },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('No salary structures yet.')

    await user.type(screen.getByLabelText('Base pay'), '5000')
    await user.type(screen.getByLabelText('Effective from'), '2026-01-01')
    await user.click(screen.getByRole('button', { name: 'Create salary structure' }))

    expect(await screen.findByText('effectiveTo cannot be before effectiveFrom')).toBeInTheDocument()
  })
})
