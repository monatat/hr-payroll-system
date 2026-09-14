import { useState, type FormEvent } from 'react'
import type { Department } from '../../api/departments'
import type { EmploymentStatus } from '../../api/employees'

export type EmployeeFormValues = {
  employeeCode: string
  fullName: string
  icNumber: string
  departmentId: string
  jobTitle: string
  employmentStatus: EmploymentStatus
  hireDate: string
  bankName: string
  bankAccountNo: string
  phoneNumber: string
}

type EmployeeFormProps = {
  departments: Department[]
  initialValues: EmployeeFormValues
  mode: 'create' | 'edit'
  submitting: boolean
  onSubmit: (values: EmployeeFormValues) => void
}

const LABEL_CLASS = 'block text-sm font-medium text-slate-700'
const INPUT_CLASS = 'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900'

export function EmployeeForm({ departments, initialValues, mode, submitting, onSubmit }: EmployeeFormProps) {
  const [values, setValues] = useState<EmployeeFormValues>(initialValues)

  function update<K extends keyof EmployeeFormValues>(key: K, value: EmployeeFormValues[K]) {
    setValues((previous) => ({ ...previous, [key]: value }))
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    onSubmit(values)
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label htmlFor="employeeCode" className={LABEL_CLASS}>
          Employee code
        </label>
        <input
          id="employeeCode"
          value={values.employeeCode}
          onChange={(event) => update('employeeCode', event.target.value)}
          disabled={mode === 'edit'}
          required
          className={`${INPUT_CLASS} disabled:bg-slate-100 disabled:text-slate-500`}
        />
        {mode === 'edit' && <p className="mt-1 text-xs text-slate-400">Employee code cannot be changed.</p>}
      </div>

      <div>
        <label htmlFor="fullName" className={LABEL_CLASS}>
          Full name
        </label>
        <input
          id="fullName"
          value={values.fullName}
          onChange={(event) => update('fullName', event.target.value)}
          required
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="icNumber" className={LABEL_CLASS}>
          IC number {mode === 'edit' && <span className="font-normal text-slate-400">(leave blank to keep unchanged)</span>}
        </label>
        <input
          id="icNumber"
          value={values.icNumber}
          onChange={(event) => update('icNumber', event.target.value)}
          required={mode === 'create'}
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="departmentId" className={LABEL_CLASS}>
          Department
        </label>
        <select
          id="departmentId"
          value={values.departmentId}
          onChange={(event) => update('departmentId', event.target.value)}
          required
          className={INPUT_CLASS}
        >
          <option value="">Select a department</option>
          {departments.map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="jobTitle" className={LABEL_CLASS}>
          Job title
        </label>
        <input
          id="jobTitle"
          value={values.jobTitle}
          onChange={(event) => update('jobTitle', event.target.value)}
          required
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="employmentStatus" className={LABEL_CLASS}>
          Employment status
        </label>
        <select
          id="employmentStatus"
          value={values.employmentStatus}
          onChange={(event) => update('employmentStatus', event.target.value as EmploymentStatus)}
          className={INPUT_CLASS}
        >
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
      </div>

      <div>
        <label htmlFor="hireDate" className={LABEL_CLASS}>
          Hire date
        </label>
        <input
          id="hireDate"
          type="date"
          value={values.hireDate}
          onChange={(event) => update('hireDate', event.target.value)}
          required
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="bankName" className={LABEL_CLASS}>
          Bank name (optional{mode === 'edit' ? ', leave blank to keep unchanged' : ''})
        </label>
        <input
          id="bankName"
          value={values.bankName}
          onChange={(event) => update('bankName', event.target.value)}
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="bankAccountNo" className={LABEL_CLASS}>
          Bank account number (optional{mode === 'edit' ? ', leave blank to keep unchanged' : ''})
        </label>
        <input
          id="bankAccountNo"
          value={values.bankAccountNo}
          onChange={(event) => update('bankAccountNo', event.target.value)}
          className={INPUT_CLASS}
        />
      </div>

      <div>
        <label htmlFor="phoneNumber" className={LABEL_CLASS}>
          Phone number (optional)
        </label>
        <input
          id="phoneNumber"
          value={values.phoneNumber}
          onChange={(event) => update('phoneNumber', event.target.value)}
          className={INPUT_CLASS}
        />
      </div>

      <button
        type="submit"
        disabled={submitting}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
      >
        {submitting ? 'Saving...' : mode === 'create' ? 'Create employee' : 'Save changes'}
      </button>
    </form>
  )
}
