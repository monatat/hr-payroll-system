import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as employeesApi from '../../api/employees'
import { EmployeeProfilePage } from './EmployeeProfilePage'

vi.mock('../../api/employees')

const baseProfile = {
  id: 7,
  employeeCode: 'E7',
  fullName: 'Self Service',
  departmentId: 1,
  departmentName: 'Engineering',
  jobTitle: 'Dev',
  employmentStatus: 'ACTIVE' as const,
  hireDate: '2024-01-01',
  phoneNumber: '0123456789',
}

describe('EmployeeProfilePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(employeesApi.getOwnProfile).mockResolvedValue(baseProfile)
  })

  it('renders the own profile, including the current phone number', async () => {
    render(<EmployeeProfilePage />)

    expect(await screen.findByText('Self Service')).toBeInTheDocument()
    expect(screen.getByDisplayValue('0123456789')).toBeInTheDocument()
    expect(screen.getByText('Engineering')).toBeInTheDocument()
  })

  it('saves an updated phone number', async () => {
    vi.mocked(employeesApi.updateOwnProfile).mockResolvedValue({ ...baseProfile, phoneNumber: '0199999999' })
    const user = userEvent.setup()

    render(<EmployeeProfilePage />)
    await screen.findByText('Self Service')

    const input = screen.getByLabelText('Phone number')
    await user.clear(input)
    await user.type(input, '0199999999')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(employeesApi.updateOwnProfile).toHaveBeenCalledWith('0199999999'))
    expect(await screen.findByText('Saved.')).toBeInTheDocument()
  })
})
