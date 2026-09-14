import { useEffect, useState } from 'react'
import { extractErrorMessage } from '../../api/errors'
import { getOwnSalaryHistory, type SalaryStructure } from '../../api/salaryStructures'

/**
 * EMPLOYEE-only, strictly read-only. No create/edit/delete controls are
 * rendered at all -- not just disabled -- and the data comes only from the
 * self-service endpoint, which derives identity from the JWT server-side.
 */
export function SalaryHistoryPage() {
  const [structures, setStructures] = useState<SalaryStructure[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    getOwnSalaryHistory()
      .then(setStructures)
      .catch((err) => setLoadError(extractErrorMessage(err, 'Could not load your salary history.')))
  }, [])

  if (loadError) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError}
      </p>
    )
  }

  if (!structures) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  return (
    <section className="mx-auto max-w-2xl">
      <h1 className="text-xl font-semibold text-slate-900">My salary history</h1>

      <div className="mt-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
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
    </section>
  )
}
