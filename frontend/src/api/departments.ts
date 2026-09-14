import { apiClient } from './client'

export type Department = {
  id: number
  name: string
}

export async function listDepartments(): Promise<Department[]> {
  const response = await apiClient.get<Department[]>('/api/departments')
  return response.data
}

export async function createDepartment(name: string): Promise<Department> {
  const response = await apiClient.post<Department>('/api/departments', { name })
  return response.data
}
