import { apiClient } from './client'

export type AttendanceTodayStatus = 'NOT_CLOCKED_IN' | 'CLOCKED_IN' | 'CLOCKED_OUT'

// Matches the backend's AttendanceResponse exactly -- id/workDate/clockInAt/clockOutAt only.
// employeeId is deliberately absent: the backend derives identity from the JWT, and nothing here
// can spoof it. Named "Record" (not "Response") to match this project's established convention of
// dropping the backend DTO's "Response" suffix on the frontend (e.g. PayslipResponse -> Payslip,
// PayrollRunResponse -> PayrollRun).
export type AttendanceRecord = {
  id: number
  workDate: string
  clockInAt: string
  clockOutAt: string | null
}

// attendance is null only for NOT_CLOCKED_IN -- this is a normal state, never an error response.
export type AttendanceTodayResponse = {
  state: AttendanceTodayStatus
  attendance: AttendanceRecord | null
}

// Same PagedModel shape used by every Spring endpoint in this project (see payroll.ts/leave.ts) --
// duplicated locally rather than imported cross-module, matching this codebase's established
// per-module convention (no shared paging type is exported from any module).
export type PagedResponse<T> = {
  content: T[]
  page: {
    size: number
    totalElements: number
    totalPages: number
    number: number
  }
}

// No request body: identity comes only from the existing apiClient interceptor's Bearer token,
// never a client-supplied employee id. Duplicate-clock-in conflicts (409
// ATTENDANCE_ALREADY_CLOCKED_IN) surface as an ordinary rejected promise through the existing
// apiClient error boundary -- this module never catches or rewrites them.
export async function clockIn(): Promise<AttendanceRecord> {
  const response = await apiClient.post<AttendanceRecord>('/api/employees/me/attendance/clock-in')
  return response.data
}

// No request body, same identity rule as clockIn.
export async function clockOut(): Promise<AttendanceRecord> {
  const response = await apiClient.post<AttendanceRecord>('/api/employees/me/attendance/clock-out')
  return response.data
}

export async function getTodayAttendance(): Promise<AttendanceTodayResponse> {
  const response = await apiClient.get<AttendanceTodayResponse>('/api/employees/me/attendance/today')
  return response.data
}

// Sends only page/size -- never sort, never employeeId. The backend's fixed workDate-DESC/
// id-DESC ordering is trusted as-is; this module never reorders, filters, or mutates the result.
export async function listOwnAttendance(page = 0, size = 20): Promise<PagedResponse<AttendanceRecord>> {
  const response = await apiClient.get<PagedResponse<AttendanceRecord>>('/api/employees/me/attendance', {
    params: { page, size },
  })
  return response.data
}
