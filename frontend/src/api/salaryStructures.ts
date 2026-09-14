import { apiClient } from './client'

export type SalaryStructure = {
  id: number
  employeeId: number
  basePay: number
  housingAllowance: number
  transportAllowance: number
  effectiveFrom: string
  effectiveTo: string | null
}

// housingAllowance/transportAllowance are optional here -- the backend
// normalizes an omitted value to zero (mirrors the DB's own default), so a
// form that only sets base pay has nothing extra to send. effectiveTo
// omitted (or undefined) means open-ended.
export type CreateSalaryStructureInput = {
  basePay: number
  housingAllowance?: number
  transportAllowance?: number
  effectiveFrom: string
  effectiveTo?: string
}

export async function listSalaryStructures(employeeId: number): Promise<SalaryStructure[]> {
  const response = await apiClient.get<SalaryStructure[]>(`/api/employees/${employeeId}/salary-structures`)
  return response.data
}

export async function createSalaryStructure(
  employeeId: number,
  input: CreateSalaryStructureInput,
): Promise<SalaryStructure> {
  const response = await apiClient.post<SalaryStructure>(`/api/employees/${employeeId}/salary-structures`, input)
  return response.data
}

// Calls the EMPLOYEE-only self-service endpoint -- identity comes from the
// caller's JWT on the backend, never from a client-supplied employee id.
export async function getOwnSalaryHistory(): Promise<SalaryStructure[]> {
  const response = await apiClient.get<SalaryStructure[]>('/api/employees/me/salary-structures')
  return response.data
}
