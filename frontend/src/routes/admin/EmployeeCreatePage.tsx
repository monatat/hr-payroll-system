import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { listDepartments, type Department } from '../../api/departments'
import { extractErrorMessage } from '../../api/errors'
import { createEmployee } from '../../api/employees'
import { EmployeeForm, type EmployeeFormValues } from './EmployeeForm'

const EMPTY_VALUES: EmployeeFormValues = {
  employeeCode: '',
  fullName: '',
  icNumber: '',
  departmentId: '',
  jobTitle: '',
  employmentStatus: 'ACTIVE',
  hireDate: '',
  bankName: '',
  bankAccountNo: '',
  phoneNumber: '',
}

export function EmployeeCreatePage() {
  const [departments, setDepartments] = useState<Department[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    listDepartments()
      .then(setDepartments)
      .catch(() => setDepartments([]))
  }, [])

  async function handleSubmit(values: EmployeeFormValues) {
    setError(null)
    setSubmitting(true)
    try {
      const created = await createEmployee({
        employeeCode: values.employeeCode,
        fullName: values.fullName,
        icNumber: values.icNumber,
        departmentId: Number(values.departmentId),
        jobTitle: values.jobTitle,
        employmentStatus: values.employmentStatus,
        hireDate: values.hireDate,
        bankName: values.bankName || undefined,
        bankAccountNo: values.bankAccountNo || undefined,
        phoneNumber: values.phoneNumber || undefined,
      })
      navigate(`/admin/employees/${created.id}`)
    } catch (err) {
      setError(extractErrorMessage(err, 'Could not create employee.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-xl rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <h1 className="text-xl font-semibold text-slate-900">Create employee</h1>
      {error && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="mt-4">
        <EmployeeForm
          departments={departments}
          initialValues={EMPTY_VALUES}
          mode="create"
          submitting={submitting}
          onSubmit={handleSubmit}
        />
      </div>
    </section>
  )
}
