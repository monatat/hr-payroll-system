import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { listDepartments, type Department } from '../../api/departments'
import { extractErrorMessage } from '../../api/errors'
import { getEmployee, updateEmployee } from '../../api/employees'
import { EmployeeForm, type EmployeeFormValues } from './EmployeeForm'

export function EmployeeEditPage() {
  const { id } = useParams<{ id: string }>()
  const employeeId = Number(id)
  const navigate = useNavigate()

  const [departments, setDepartments] = useState<Department[]>([])
  const [initialValues, setInitialValues] = useState<EmployeeFormValues | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listDepartments()
      .then(setDepartments)
      .catch(() => setDepartments([]))
    getEmployee(employeeId)
      .then((employee) =>
        setInitialValues({
          employeeCode: employee.employeeCode,
          fullName: employee.fullName,
          // Never pre-filled: GET responses never expose it (masked by design).
          icNumber: '',
          departmentId: String(employee.departmentId),
          jobTitle: employee.jobTitle,
          employmentStatus: employee.employmentStatus,
          hireDate: employee.hireDate,
          bankName: '',
          bankAccountNo: '',
          phoneNumber: employee.phoneNumber ?? '',
        }),
      )
      .catch((err) => setError(extractErrorMessage(err, 'Could not load employee.')))
  }, [employeeId])

  async function handleSubmit(values: EmployeeFormValues) {
    setError(null)
    setSubmitting(true)
    try {
      await updateEmployee(employeeId, {
        fullName: values.fullName,
        icNumber: values.icNumber || undefined,
        departmentId: Number(values.departmentId),
        jobTitle: values.jobTitle,
        employmentStatus: values.employmentStatus,
        hireDate: values.hireDate,
        bankName: values.bankName || undefined,
        bankAccountNo: values.bankAccountNo || undefined,
        phoneNumber: values.phoneNumber || undefined,
      })
      navigate(`/admin/employees/${employeeId}`)
    } catch (err) {
      setError(extractErrorMessage(err, 'Could not update employee.'))
    } finally {
      setSubmitting(false)
    }
  }

  if (!initialValues) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  return (
    <section className="mx-auto max-w-xl rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <h1 className="text-xl font-semibold text-slate-900">Edit employee</h1>
      {error && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="mt-4">
        <EmployeeForm
          departments={departments}
          initialValues={initialValues}
          mode="edit"
          submitting={submitting}
          onSubmit={handleSubmit}
        />
      </div>
    </section>
  )
}
