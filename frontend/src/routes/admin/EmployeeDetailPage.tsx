import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { extractErrorMessage } from '../../api/errors'
import {
  changeEmployeeStatus,
  getEmployee,
  getEmployeeSensitive,
  type Employee,
  type EmployeeSensitive,
} from '../../api/employees'

export function EmployeeDetailPage() {
  const { id } = useParams<{ id: string }>()
  const employeeId = Number(id)

  const [employee, setEmployee] = useState<Employee | null>(null)
  const [sensitive, setSensitive] = useState<EmployeeSensitive | null>(null)
  const [sensitiveError, setSensitiveError] = useState<string | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [statusChanging, setStatusChanging] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)

  async function loadEmployee() {
    try {
      setEmployee(await getEmployee(employeeId))
    } catch (err) {
      setLoadError(extractErrorMessage(err, 'Could not load employee.'))
    }
  }

  useEffect(() => {
    loadEmployee()
    // Reset any previously revealed sensitive data when navigating between employees.
    setSensitive(null)
  }, [employeeId])

  async function handleRevealSensitive() {
    setSensitiveError(null)
    try {
      setSensitive(await getEmployeeSensitive(employeeId))
    } catch (err) {
      setSensitiveError(extractErrorMessage(err, 'Could not load sensitive information.'))
    }
  }

  async function handleToggleStatus() {
    if (!employee) return
    setStatusError(null)
    setStatusChanging(true)
    try {
      const nextStatus = employee.employmentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      const updated = await changeEmployeeStatus(employeeId, nextStatus)
      setEmployee(updated)
    } catch (err) {
      setStatusError(extractErrorMessage(err, 'Could not change employment status.'))
    } finally {
      setStatusChanging(false)
    }
  }

  if (loadError) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError}
      </p>
    )
  }

  if (!employee) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  return (
    <section className="mx-auto max-w-xl space-y-6">
      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">{employee.fullName}</h1>
            <p className="text-sm text-slate-500">{employee.employeeCode}</p>
          </div>
          <span
            className={`rounded-full px-3 py-1 text-xs font-medium ${
              employee.employmentStatus === 'ACTIVE' ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'
            }`}
          >
            {employee.employmentStatus}
          </span>
        </div>

        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <dt className="text-slate-500">Department</dt>
          <dd className="text-slate-900">{employee.departmentName}</dd>
          <dt className="text-slate-500">Job title</dt>
          <dd className="text-slate-900">{employee.jobTitle}</dd>
          <dt className="text-slate-500">Hire date</dt>
          <dd className="text-slate-900">{employee.hireDate}</dd>
          <dt className="text-slate-500">Phone number</dt>
          <dd className="text-slate-900">{employee.phoneNumber ?? '—'}</dd>
        </dl>

        <div className="mt-6 flex flex-wrap gap-3">
          <Link
            to={`/admin/employees/${employee.id}/edit`}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Edit
          </Link>
          <Link
            to={`/admin/employees/${employee.id}/salary`}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Salary history
          </Link>
          <button
            type="button"
            onClick={handleToggleStatus}
            disabled={statusChanging}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {employee.employmentStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'}
          </button>
        </div>
        {statusError && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {statusError}
          </p>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Sensitive information</h2>
        <p className="mt-1 text-xs text-slate-500">
          IC number and bank details are never loaded automatically -- request them explicitly.
        </p>

        {!sensitive ? (
          <button
            type="button"
            onClick={handleRevealSensitive}
            className="mt-3 rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            View sensitive info
          </button>
        ) : (
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-slate-500">IC number</dt>
            <dd className="text-slate-900">{sensitive.icNumber}</dd>
            <dt className="text-slate-500">Bank name</dt>
            <dd className="text-slate-900">{sensitive.bankName ?? '—'}</dd>
            <dt className="text-slate-500">Bank account no.</dt>
            <dd className="text-slate-900">{sensitive.bankAccountNo ?? '—'}</dd>
          </dl>
        )}
        {sensitiveError && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {sensitiveError}
          </p>
        )}
      </div>
    </section>
  )
}
