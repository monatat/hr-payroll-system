import { apiClient } from './client'

export type EmploymentStatus = 'ACTIVE' | 'INACTIVE'

export type Employee = {
  id: number
  employeeCode: string
  fullName: string
  departmentId: number
  departmentName: string
  jobTitle: string
  employmentStatus: EmploymentStatus
  hireDate: string
  phoneNumber: string | null
}

export type EmployeePage = {
  content: Employee[]
  page: {
    size: number
    totalElements: number
    totalPages: number
    number: number
  }
}

export type CreateEmployeeInput = {
  employeeCode: string
  fullName: string
  icNumber: string
  departmentId: number
  jobTitle: string
  employmentStatus?: EmploymentStatus
  hireDate: string
  bankName?: string
  bankAccountNo?: string
  phoneNumber?: string
}

// icNumber/bankName/bankAccountNo are optional here (unlike create) --
// GET responses never return them, so a form editing e.g. just the job
// title has nothing to resend; omitting them leaves them unchanged on the
// backend rather than clearing them.
export type UpdateEmployeeInput = {
  fullName: string
  icNumber?: string
  departmentId: number
  jobTitle: string
  employmentStatus: EmploymentStatus
  hireDate: string
  bankName?: string
  bankAccountNo?: string
  phoneNumber?: string
}

export type EmployeeSensitive = {
  id: number
  icNumber: string
  bankName: string | null
  bankAccountNo: string | null
}

export type ListEmployeesParams = {
  q?: string
  departmentId?: number
  status?: EmploymentStatus
  page?: number
  size?: number
}

export async function listEmployees(params: ListEmployeesParams = {}): Promise<EmployeePage> {
  const response = await apiClient.get<EmployeePage>('/api/employees', { params })
  return response.data
}

export async function getEmployee(id: number): Promise<Employee> {
  const response = await apiClient.get<Employee>(`/api/employees/${id}`)
  return response.data
}

export async function createEmployee(input: CreateEmployeeInput): Promise<Employee> {
  const response = await apiClient.post<Employee>('/api/employees', input)
  return response.data
}

export async function updateEmployee(id: number, input: UpdateEmployeeInput): Promise<Employee> {
  const response = await apiClient.put<Employee>(`/api/employees/${id}`, input)
  return response.data
}

export async function changeEmployeeStatus(id: number, status: EmploymentStatus): Promise<Employee> {
  const response = await apiClient.patch<Employee>(`/api/employees/${id}/status`, { status })
  return response.data
}

// Calls the existing Phase 4 sensitive endpoint -- only invoked when the
// HR_ADMIN explicitly asks to view it, never loaded by default.
export async function getEmployeeSensitive(id: number): Promise<EmployeeSensitive> {
  const response = await apiClient.get<EmployeeSensitive>(`/api/employees/${id}/sensitive`)
  return response.data
}

export async function getOwnProfile(): Promise<Employee> {
  const response = await apiClient.get<Employee>('/api/employees/me')
  return response.data
}

export async function updateOwnProfile(phoneNumber: string): Promise<Employee> {
  const response = await apiClient.put<Employee>('/api/employees/me', { phoneNumber })
  return response.data
}
