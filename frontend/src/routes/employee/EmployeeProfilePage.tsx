import { useEffect, useState, type FormEvent } from 'react'
import { extractErrorMessage } from '../../api/errors'
import { getOwnProfile, updateOwnProfile, type Employee } from '../../api/employees'

export function EmployeeProfilePage() {
  const [profile, setProfile] = useState<Employee | null>(null)
  const [phoneNumber, setPhoneNumber] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    getOwnProfile()
      .then((data) => {
        setProfile(data)
        setPhoneNumber(data.phoneNumber ?? '')
      })
      .catch((err) => setLoadError(extractErrorMessage(err, 'Could not load your profile.')))
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSaved(false)
    setSaving(true)
    try {
      const updated = await updateOwnProfile(phoneNumber)
      setProfile(updated)
      setSaved(true)
    } catch (err) {
      setError(extractErrorMessage(err, 'Could not update your profile.'))
    } finally {
      setSaving(false)
    }
  }

  if (loadError) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {loadError}
      </p>
    )
  }

  if (!profile) {
    return <p className="text-sm text-slate-500">Loading...</p>
  }

  return (
    <section className="mx-auto max-w-lg rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <h1 className="text-xl font-semibold text-slate-900">My Profile</h1>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
        <dt className="text-slate-500">Employee code</dt>
        <dd className="text-slate-900">{profile.employeeCode}</dd>
        <dt className="text-slate-500">Full name</dt>
        <dd className="text-slate-900">{profile.fullName}</dd>
        <dt className="text-slate-500">Department</dt>
        <dd className="text-slate-900">{profile.departmentName}</dd>
        <dt className="text-slate-500">Job title</dt>
        <dd className="text-slate-900">{profile.jobTitle}</dd>
        <dt className="text-slate-500">Employment status</dt>
        <dd className="text-slate-900">{profile.employmentStatus}</dd>
        <dt className="text-slate-500">Hire date</dt>
        <dd className="text-slate-900">{profile.hireDate}</dd>
      </dl>
      <p className="mt-3 text-xs text-slate-400">
        The fields above are managed by HR. Only your phone number can be updated here.
      </p>

      <form onSubmit={handleSubmit} className="mt-4 space-y-4">
        <div>
          <label htmlFor="phoneNumber" className="block text-sm font-medium text-slate-700">
            Phone number
          </label>
          <input
            id="phoneNumber"
            value={phoneNumber}
            onChange={(event) => setPhoneNumber(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
          />
        </div>

        {error && (
          <p role="alert" className="text-sm text-red-600">
            {error}
          </p>
        )}
        {saved && !error && <p className="text-sm text-emerald-600">Saved.</p>}

        <button
          type="submit"
          disabled={saving}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
      </form>
    </section>
  )
}
