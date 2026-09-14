import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as departmentsApi from '../../api/departments'
import * as employeesApi from '../../api/employees'
import { EmployeeCreatePage } from './EmployeeCreatePage'

vi.mock('../../api/departments')
vi.mock('../../api/employees')

function renderPage() {
  return render(
    <MemoryRouter>
      <EmployeeCreatePage />
    </MemoryRouter>,
  )
}

async function fillMinimalForm(user: ReturnType<typeof userEvent.setup>, employeeCode: string) {
  await user.type(screen.getByLabelText('Employee code'), employeeCode)
  await user.type(screen.getByLabelText('Full name'), 'New Hire')
  await user.type(screen.getByLabelText(/IC number/), '900101-01-1234')
  await user.selectOptions(screen.getByLabelText('Department'), '1')
  await user.type(screen.getByLabelText('Job title'), 'Dev')
  await user.type(screen.getByLabelText('Hire date'), '2024-01-01')
}

describe('EmployeeCreatePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(departmentsApi.listDepartments).mockResolvedValue([{ id: 1, name: 'Engineering' }])
  })

  it('submits the form and calls createEmployee with the entered values', async () => {
    vi.mocked(employeesApi.createEmployee).mockResolvedValue({
      id: 42,
      employeeCode: 'E42',
      fullName: 'New Hire',
      departmentId: 1,
      departmentName: 'Engineering',
      jobTitle: 'Dev',
      employmentStatus: 'ACTIVE',
      hireDate: '2024-01-01',
      phoneNumber: null,
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('option', { name: 'Engineering' })
    await fillMinimalForm(user, 'E42')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    await waitFor(() => expect(employeesApi.createEmployee).toHaveBeenCalled())
    const [payload] = vi.mocked(employeesApi.createEmployee).mock.calls[0]
    expect(payload.employeeCode).toBe('E42')
    expect(payload.departmentId).toBe(1)
    expect(payload.icNumber).toBe('900101-01-1234')
  })

  it('shows a duplicate-code error returned by the API', async () => {
    vi.mocked(employeesApi.createEmployee).mockRejectedValue({
      response: { data: { detail: 'An employee with this employee code already exists.' } },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('option', { name: 'Engineering' })
    await fillMinimalForm(user, 'DUP')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    expect(await screen.findByText('An employee with this employee code already exists.')).toBeInTheDocument()
  })
})
