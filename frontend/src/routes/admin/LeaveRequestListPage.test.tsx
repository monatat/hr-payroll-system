import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as employeesApi from '../../api/employees'
import * as leaveApi from '../../api/leave'
import { LeaveRequestListPage } from './LeaveRequestListPage'

vi.mock('../../api/employees')
vi.mock('../../api/leave')

function renderPage() {
  return render(
    <MemoryRouter>
      <LeaveRequestListPage />
    </MemoryRouter>,
  )
}

const emptyPage: leaveApi.PagedResponse<leaveApi.LeaveRequest> = {
  content: [],
  page: { size: 10, totalElements: 0, totalPages: 0, number: 0 },
}

function leaveRequest(overrides: Partial<leaveApi.LeaveRequest> = {}): leaveApi.LeaveRequest {
  return {
    id: 1,
    employeeId: 7,
    employeeCode: 'E7',
    employeeFullName: 'Ada Lovelace',
    leaveTypeId: 1,
    leaveTypeName: 'Annual Leave',
    startDate: '2026-09-07',
    endDate: '2026-09-11',
    daysRequested: 5,
    durationType: 'FULL_DAY',
    status: 'PENDING',
    reason: null,
    reviewedByUserId: null,
    reviewedAt: null,
    rejectionReason: null,
    ...overrides,
  }
}

describe('LeaveRequestListPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(employeesApi.listEmployees).mockResolvedValue({
      content: [
        {
          id: 7,
          employeeCode: 'E7',
          fullName: 'Ada Lovelace',
          departmentId: 1,
          departmentName: 'Engineering',
          jobTitle: 'Dev',
          employmentStatus: 'ACTIVE',
          hireDate: '2024-01-01',
          phoneNumber: null,
        },
      ],
      page: { size: 100, totalElements: 1, totalPages: 1, number: 0 },
    })
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue(emptyPage)
  })

  it('defaults to the Pending filter', async () => {
    renderPage()

    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenCalledWith(expect.objectContaining({ status: 'PENDING' }))
    })
    expect(screen.getByLabelText('Filter by status')).toHaveValue('PENDING')
  })

  it('renders request rows and status badges', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [leaveRequest({ status: 'PENDING' })],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()

    expect(await screen.findByText('E7')).toBeInTheDocument()
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument()
    expect(screen.getByText('Annual Leave')).toBeInTheDocument()
    expect(screen.getByText('PENDING')).toBeInTheDocument()
  })

  it('loads employee options using the existing employee API', async () => {
    renderPage()

    await waitFor(() => expect(employeesApi.listEmployees).toHaveBeenCalled())
    expect(await screen.findByRole('option', { name: /E7.*Ada Lovelace/ })).toBeInTheDocument()
  })

  it('sends status and employeeId filters correctly', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitFor(() => expect(leaveApi.listLeaveRequests).toHaveBeenCalled())

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'APPROVED')

    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'APPROVED' }))
    })

    await user.selectOptions(await screen.findByLabelText('Filter by employee'), '7')

    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(expect.objectContaining({ employeeId: 7 }))
    })
  })

  it('resets to page zero when a filter changes', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [leaveRequest()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    })

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'ALL')
    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, status: undefined }))
    })
  })

  it('pagination loads the correct page while preserving filters', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [leaveRequest()],
      page: { size: 10, totalElements: 25, totalPages: 3, number: 0 },
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Page 1 of 3')

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'REJECTED')
    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'REJECTED', page: 0 }))
    })

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => {
      expect(leaveApi.listLeaveRequests).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: 'REJECTED', page: 1 }),
      )
    })
  })

  it('shows a "No pending requests" message when the Pending filter has no results', async () => {
    renderPage()

    expect(await screen.findByText(/no pending requests/i)).toBeInTheDocument()
  })

  it('shows a generic empty message for the All filter', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText(/no pending requests/i)

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'ALL')

    expect(await screen.findByText(/no leave requests found/i)).toBeInTheDocument()
  })

  it('shows an inline error via extractErrorMessage on API failure', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockRejectedValue({
      response: { data: { detail: 'Could not reach the leave service.' } },
    })

    renderPage()

    expect(await screen.findByText('Could not reach the leave service.')).toBeInTheDocument()
  })

  it('renders a Duration column with Full Day and Half Day labels', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [
        leaveRequest({ id: 1, durationType: 'FULL_DAY', daysRequested: 5 }),
        leaveRequest({ id: 2, durationType: 'HALF_DAY', daysRequested: 0.5 }),
      ],
      page: { size: 10, totalElements: 2, totalPages: 1, number: 0 },
    })

    renderPage()

    await screen.findByText('Full Day')
    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('Full Day')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Half Day')).toBeInTheDocument()
    expect(within(rows[2]).getByText('0.5')).toBeInTheDocument()
  })

  it('links each request to its detail route', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [leaveRequest({ id: 42 })],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })

    renderPage()

    const link = await screen.findByRole('link', { name: /review/i })
    expect(link).toHaveAttribute('href', '/admin/leave/42')
  })

  // jsdom performs no layout, so this guards the containment structure rather than pixel widths: the
  // sr-only "Actions" header is absolutely positioned and, without a positioned scroll wrapper, escapes
  // overflow-x-auto and widens the whole page (reproduced with the same DOM/CSS structure in an isolated
  // browser-engine check during Phase 12A).
  it('keeps the requests table and its visually-hidden Actions header inside a positioned overflow-x-auto container', async () => {
    vi.mocked(leaveApi.listLeaveRequests).mockResolvedValue({
      content: [leaveRequest()],
      page: { size: 10, totalElements: 1, totalPages: 1, number: 0 },
    })
    renderPage()

    const table = await screen.findByRole('table')
    await within(table).findByText('E7')
    const wrapper = table.parentElement!
    expect(wrapper).toHaveClass('overflow-x-auto')
    expect(wrapper).toHaveClass('relative')

    const actionsHeader = within(table).getByRole('columnheader', { name: 'Actions' })
    expect(within(actionsHeader).getByText('Actions')).toHaveClass('sr-only')
    expect(wrapper).toContainElement(actionsHeader)
  })
})
