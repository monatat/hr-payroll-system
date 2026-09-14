import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as departmentsApi from '../../api/departments'
import { DepartmentsPage } from './DepartmentsPage'

vi.mock('../../api/departments')

describe('DepartmentsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('renders the list of departments', async () => {
    vi.mocked(departmentsApi.listDepartments).mockResolvedValue([
      { id: 1, name: 'Engineering' },
      { id: 2, name: 'HR' },
    ])

    render(<DepartmentsPage />)

    expect(await screen.findByText('Engineering')).toBeInTheDocument()
    expect(screen.getByText('HR')).toBeInTheDocument()
  })

  it('creates a department and refreshes the list', async () => {
    vi.mocked(departmentsApi.listDepartments)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ id: 3, name: 'Finance' }])
    vi.mocked(departmentsApi.createDepartment).mockResolvedValue({ id: 3, name: 'Finance' })
    const user = userEvent.setup()

    render(<DepartmentsPage />)
    await screen.findByText('No departments yet.')

    await user.type(screen.getByLabelText('New department name'), 'Finance')
    await user.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByText('Finance')).toBeInTheDocument()
    expect(departmentsApi.createDepartment).toHaveBeenCalledWith('Finance')
  })

  it('shows a duplicate-name error returned by the API', async () => {
    vi.mocked(departmentsApi.listDepartments).mockResolvedValue([])
    vi.mocked(departmentsApi.createDepartment).mockRejectedValue({
      response: { data: { detail: 'A department with this name already exists.' } },
    })
    const user = userEvent.setup()

    render(<DepartmentsPage />)
    await screen.findByText('No departments yet.')

    await user.type(screen.getByLabelText('New department name'), 'Engineering')
    await user.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByText('A department with this name already exists.')).toBeInTheDocument()
  })
})
