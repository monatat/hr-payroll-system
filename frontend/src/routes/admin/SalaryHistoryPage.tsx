import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { extractErrorMessage } from '../../api/errors'
import { getEmployee, type Employee } from '../../api/employees'
import { createSalaryStructure, listSalaryStructures, type SalaryStructure } from '../../api/salaryStructures'

export function SalaryHistoryPage() {
  const { id } = useParams<{ id: string }>()
  const employeeId = Number(id)

  const [employee, setEmployee] = useState<Employee | null>(null)
  const [structures, setStructures] = useState<SalaryStructure[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [basePay, setBasePay] = useState('')
  const [housingAllowance, setHousingAllowance] = useState('')
  const [transportAllowance, setTransportAllowance] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [effectiveTo, setEffectiveTo] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  async function loadHistory() {
    try {
      const [employeeData, structuresData] = await Promise.all([
        getEmployee(employeeId),
        listSalaryStructures(employeeId),
      ])
      setEmployee(employeeData)
      setStructures(structuresData)
    } catch (err) {
      setLoadError(extractErrorMessage(err, 'Could not load salary history.'))
    }
  }

  useEffect(() => {
    loadHistory()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeeId])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitError(null)
    setSubmitting(true)
    try {
      await createSalaryStructure(employeeId, {
        basePay: Number(basePay),
        housingAllowance: housingAllowance === '' ? undefined : Number(housingAllowance),
        transportAllowance: transportAllowance === '' ? undefined : Number(transportAllowance),
        effectiveFrom,
        effectiveTo: effectiveTo === '' ? undefined : effectiveTo,
      })
      setBasePay('')
      setHousingAllowance('')
      setTransportAllowance('')
      setEffectiveFrom('')
      setEffectiveTo('')
      const refreshed = await listSalaryStructures(employeeId)
      setStructures(refreshed)
    } catch (err) {
      setSubmitError(extractErrorMessage(err, 'Could not create salary structure.'))
    } finally {
      setSubmitting(false)
    }
  }

  if (loadError) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError}
      </p>
    )
  }

  if (!employee || !structures) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  return (
    <section className="mx-auto max-w-2xl space-y-6">
      <div>
        <Link to={`/admin/employees/${employeeId}`} className="text-sm text-slate-500 hover:underline">
          &larr; Back to {employee.fullName}
        </Link>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Salary history</h1>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        {structures.length === 0 ? (
          <p className="text-sm text-slate-500">No salary structures yet.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-slate-500">
                <th className="pb-2 pr-4 font-medium">Base pay</th>
                <th className="pb-2 pr-4 font-medium">Housing</th>
                <th className="pb-2 pr-4 font-medium">Transport</th>
                <th className="pb-2 pr-4 font-medium">Effective from</th>
                <th className="pb-2 font-medium">Effective to</th>
              </tr>
            </thead>
            <tbody>
              {structures.map((structure) => (
                <tr key={structure.id} className="border-b border-slate-100 text-slate-900">
                  <td className="py-2 pr-4">{structure.basePay}</td>
                  <td className="py-2 pr-4">{structure.housingAllowance}</td>
                  <td className="py-2 pr-4">{structure.transportAllowance}</td>
                  <td className="py-2 pr-4">{structure.effectiveFrom}</td>
                  <td className="py-2">{structure.effectiveTo ?? 'Present'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Create salary structure</h2>
        <form onSubmit={handleSubmit} className="mt-4 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="basePay" className="block text-sm font-medium text-slate-700">
                Base pay
              </label>
              <input
                id="basePay"
                type="number"
                step="0.01"
                min="0"
                required
                value={basePay}
                onChange={(event) => setBasePay(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
            <div>
              <label htmlFor="housingAllowance" className="block text-sm font-medium text-slate-700">
                Housing allowance
              </label>
              <input
                id="housingAllowance"
                type="number"
                step="0.01"
                min="0"
                value={housingAllowance}
                onChange={(event) => setHousingAllowance(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
            <div>
              <label htmlFor="transportAllowance" className="block text-sm font-medium text-slate-700">
                Transport allowance
              </label>
              <input
                id="transportAllowance"
                type="number"
                step="0.01"
                min="0"
                value={transportAllowance}
                onChange={(event) => setTransportAllowance(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
            <div>
              <label htmlFor="effectiveFrom" className="block text-sm font-medium text-slate-700">
                Effective from
              </label>
              <input
                id="effectiveFrom"
                type="date"
                required
                value={effectiveFrom}
                onChange={(event) => setEffectiveFrom(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
            <div>
              <label htmlFor="effectiveTo" className="block text-sm font-medium text-slate-700">
                Effective to (leave blank for open-ended)
              </label>
              <input
                id="effectiveTo"
                type="date"
                value={effectiveTo}
                onChange={(event) => setEffectiveTo(event.target.value)}
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
              />
            </div>
          </div>

          {submitError && (
            <p role="alert" className="text-sm text-red-600">
              {submitError}
            </p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {submitting ? 'Creating...' : 'Create salary structure'}
          </button>
        </form>
      </div>
    </section>
  )
}
