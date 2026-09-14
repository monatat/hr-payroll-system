import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listDepartments, type Department } from '../../api/departments'
import { listEmployees, type Employee, type EmploymentStatus } from '../../api/employees'

const PAGE_SIZE = 10

export function EmployeeListPage() {
  const [employees, setEmployees] = useState<Employee[]>([])
  const [departments, setDepartments] = useState<Department[]>([])
  const [q, setQ] = useState('')
  const [departmentId, setDepartmentId] = useState('')
  const [status, setStatus] = useState<EmploymentStatus | ''>('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    listDepartments()
      .then(setDepartments)
      .catch(() => setDepartments([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listEmployees({
      q: q || undefined,
      departmentId: departmentId ? Number(departmentId) : undefined,
      status: status || undefined,
      page,
      size: PAGE_SIZE,
    })
      .then((result) => {
        if (cancelled) return
        setEmployees(result.content)
        setTotalPages(result.page.totalPages)
      })
      .catch(() => {
        if (!cancelled) {
          setEmployees([])
          setTotalPages(0)
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [q, departmentId, status, page])

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-slate-900">Employees</h1>
        <Link to="/admin/employees/new" className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white">
          Create employee
        </Link>
      </div>

      <div className="flex flex-wrap gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <input
          placeholder="Search by name or code"
          value={q}
          onChange={(event) => {
            setPage(0)
            setQ(event.target.value)
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        />
        <select
          aria-label="Filter by department"
          value={departmentId}
          onChange={(event) => {
            setPage(0)
            setDepartmentId(event.target.value)
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        >
          <option value="">All departments</option>
          {departments.map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </select>
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(event) => {
            setPage(0)
            setStatus(event.target.value as EmploymentStatus | '')
          }}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
        >
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
      </div>

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-4 py-2">Code</th>
              <th className="px-4 py-2">Name</th>
              <th className="px-4 py-2">Department</th>
              <th className="px-4 py-2">Job title</th>
              <th className="px-4 py-2">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {employees.map((employee) => (
              <tr key={employee.id}>
                <td className="px-4 py-2">
                  <Link to={`/admin/employees/${employee.id}`} className="text-blue-600 hover:underline">
                    {employee.employeeCode}
                  </Link>
                </td>
                <td className="px-4 py-2">{employee.fullName}</td>
                <td className="px-4 py-2">{employee.departmentName}</td>
                <td className="px-4 py-2">{employee.jobTitle}</td>
                <td className="px-4 py-2">{employee.employmentStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && <p className="p-4 text-sm text-slate-500">Loading...</p>}
        {!loading && employees.length === 0 && <p className="p-4 text-sm text-slate-500">No employees found.</p>}
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <button
          type="button"
          disabled={page <= 0}
          onClick={() => setPage((current) => current - 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Previous
        </button>
        <span>
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={page + 1 >= totalPages}
          onClick={() => setPage((current) => current + 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </section>
  )
}
