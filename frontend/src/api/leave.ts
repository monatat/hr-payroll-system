import { apiClient } from './client'

export type LeaveRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type LeaveDurationType = 'FULL_DAY' | 'HALF_DAY'

export type LeaveType = {
  id: number
  name: string
  defaultAnnualEntitlementDays: number
}

export type LeaveBalance = {
  id: number
  employeeId: number
  leaveTypeId: number
  leaveTypeName: string
  year: number
  entitledDays: number
  usedDays: number
  remainingDays: number
}

export type LeaveRequest = {
  id: number
  employeeId: number
  employeeCode: string
  employeeFullName: string
  leaveTypeId: number
  leaveTypeName: string
  startDate: string
  endDate: string
  daysRequested: number
  durationType: LeaveDurationType
  status: LeaveRequestStatus
  reason: string | null
  reviewedByUserId: number | null
  reviewedAt: string | null
  rejectionReason: string | null
}

// employeeId, daysRequested, status, review fields, and version are
// deliberately absent -- the backend derives employeeId from the JWT and
// always computes daysRequested itself; nothing here can spoof either.
// durationType is required (never optional) so the frontend always submits
// an explicit choice -- the backend's own nullable-default behavior exists
// only for older/transitional clients, not this one.
export type CreateLeaveRequestPayload = {
  leaveTypeId: number
  startDate: string
  endDate: string
  durationType: LeaveDurationType
  reason?: string
}

// Matches the shape returned by every Spring PagedModel endpoint in this
// project (see EmployeePage in employees.ts) -- shared here as a generic
// since this file has two paginated endpoints already (and a third,
// HR_ADMIN-facing one, arrives in Phase 7C-2).
export type PagedResponse<T> = {
  content: T[]
  page: {
    size: number
    totalElements: number
    totalPages: number
    number: number
  }
}

export async function listLeaveTypes(): Promise<LeaveType[]> {
  const response = await apiClient.get<LeaveType[]>('/api/leave-types')
  return response.data
}

// Calls the EMPLOYEE-only self-service endpoint -- identity comes from the
// caller's JWT on the backend, never from a client-supplied employee id.
export async function getOwnLeaveBalances(): Promise<LeaveBalance[]> {
  const response = await apiClient.get<LeaveBalance[]>('/api/leave-balances/me')
  return response.data
}

export async function createLeaveRequest(payload: CreateLeaveRequestPayload): Promise<LeaveRequest> {
  const response = await apiClient.post<LeaveRequest>('/api/leave-requests', payload)
  return response.data
}

export async function listOwnLeaveRequests(page: number, size: number): Promise<PagedResponse<LeaveRequest>> {
  const response = await apiClient.get<PagedResponse<LeaveRequest>>('/api/leave-requests/me', { params: { page, size } })
  return response.data
}

export type ListLeaveRequestsParams = {
  status?: LeaveRequestStatus
  employeeId?: number
  page?: number
  size?: number
}

// HR_ADMIN-only listing -- status/employeeId are both optional server-side
// filters, never client-side re-filtering of an already-paginated page.
export async function listLeaveRequests(params: ListLeaveRequestsParams = {}): Promise<PagedResponse<LeaveRequest>> {
  const response = await apiClient.get<PagedResponse<LeaveRequest>>('/api/leave-requests', { params })
  return response.data
}

// Any authenticated role may call this by id; the backend itself enforces
// that an EMPLOYEE only ever sees their own request (404 otherwise), so no
// separate admin-only detail endpoint is needed here.
export async function getLeaveRequest(id: number): Promise<LeaveRequest> {
  const response = await apiClient.get<LeaveRequest>(`/api/leave-requests/${id}`)
  return response.data
}

// HR_ADMIN-only endpoint for viewing another employee's balances (see
// LeaveBalance above -- same shape as the EMPLOYEE self-service response).
export async function getEmployeeLeaveBalances(employeeId: number): Promise<LeaveBalance[]> {
  const response = await apiClient.get<LeaveBalance[]>(`/api/employees/${employeeId}/leave-balances`)
  return response.data
}

export async function approveLeaveRequest(id: number): Promise<LeaveRequest> {
  const response = await apiClient.put<LeaveRequest>(`/api/leave-requests/${id}/approve`)
  return response.data
}

// The backend's reject endpoint takes `@RequestBody(required = false)` with
// one optional `reason` field -- an empty body is valid, so an omitted
// reason is sent as `{}` rather than `{ reason: undefined }`.
export async function rejectLeaveRequest(id: number, reason?: string): Promise<LeaveRequest> {
  const body = reason ? { reason } : {}
  const response = await apiClient.put<LeaveRequest>(`/api/leave-requests/${id}/reject`, body)
  return response.data
}
