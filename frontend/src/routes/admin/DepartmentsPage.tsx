import { useEffect, useState, type FormEvent } from 'react'
import { createDepartment, listDepartments, type Department } from '../../api/departments'
import { extractErrorMessage } from '../../api/errors'

export function DepartmentsPage() {
  const [departments, setDepartments] = useState<Department[]>([])
  const [loading, setLoading] = useState(true)
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function loadDepartments() {
    setLoading(true)
    try {
      setDepartments(await listDepartments())
    } catch (err) {
      setError(extractErrorMessage(err, 'Could not load departments.'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadDepartments()
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await createDepartment(name)
      setName('')
      await loadDepartments()
    } catch (err) {
      setError(extractErrorMessage(err, 'Could not create department.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="space-y-6">
      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Departments</h1>

        <form onSubmit={handleSubmit} className="mt-4 flex items-end gap-3">
          <div className="flex-1">
            <label htmlFor="department-name" className="block text-sm font-medium text-slate-700">
              New department name
            </label>
            <input
              id="department-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
            />
          </div>
          <button
            type="submit"
            disabled={submitting}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {submitting ? 'Creating...' : 'Create'}
          </button>
        </form>
        {error && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {error}
          </p>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        {loading ? (
          <p className="text-sm text-slate-500">Loading...</p>
        ) : departments.length === 0 ? (
          <p className="text-sm text-slate-500">No departments yet.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {departments.map((department) => (
              <li key={department.id} className="py-2 text-sm text-slate-900">
                {department.name}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
