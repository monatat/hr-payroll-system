import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as departmentsApi from '../../api/departments'
import * as employeesApi from '../../api/employees'
import { EmployeeListPage } from './EmployeeListPage'

vi.mock('../../api/departments')
vi.mock('../../api/employees')

function renderPage() {
  return render(
    <MemoryRouter>
      <EmployeeListPage />
    </MemoryRouter>,
  )
}

const emptyPage = { content: [], page: { size: 10, totalElements: 0, totalPages: 0, number: 0 } }

describe('EmployeeListPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(departmentsApi.listDepartments).mockResolvedValue([{ id: 1, name: 'Engineering' }])
  })

  it('renders a page of employees', async () => {
    vi.mocked(employeesApi.listEmployees).mockResolvedValue({
      content: [
        {
          id: 1,
          employeeCode: 'E1',
          fullName: 'Alice',
          departmentId: 1,
          departmentName: 'Engineering',
          jobTitle: 'Dev',
          employmentStatus: 'ACTIVE',
          hireDate: '2024-01-01',
          phoneNumber: null,
        },
      ],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()

    expect(await screen.findByText('Alice')).toBeInTheDocument()
  })

  it('searches by typing into the search box', async () => {
    vi.mocked(employeesApi.listEmployees).mockResolvedValue(emptyPage)
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('No employees found.')

    await user.type(screen.getByPlaceholderText('Search by name or code'), 'Bob')

    await waitFor(() => {
      expect(employeesApi.listEmployees).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Bob' }))
    })
  })

  it('supports pagination controls', async () => {
    vi.mocked(employeesApi.listEmployees).mockResolvedValue({
      content: [],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      expect(employeesApi.listEmployees).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    })
  })

  it('filters by department', async () => {
    vi.mocked(employeesApi.listEmployees).mockResolvedValue(emptyPage)
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('No employees found.')

    await user.selectOptions(screen.getByLabelText('Filter by department'), '1')

    await waitFor(() => {
      expect(employeesApi.listEmployees).toHaveBeenLastCalledWith(expect.objectContaining({ departmentId: 1 }))
    })
  })
})
