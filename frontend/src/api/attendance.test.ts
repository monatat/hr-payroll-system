import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import * as attendanceApi from './attendance'
import type { AttendanceRecord, AttendanceTodayResponse } from './attendance'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const FAKE_ATTENDANCE: AttendanceRecord = {
  id: 1,
  workDate: '2026-03-10',
  clockInAt: '2026-03-10T09:00:00',
  clockOutAt: null,
}

const FAKE_CLOSED_ATTENDANCE: AttendanceRecord = {
  id: 1,
  workDate: '2026-03-10',
  clockInAt: '2026-03-10T09:00:00',
  clockOutAt: '2026-03-10T18:00:00',
}

const FAKE_ATTENDANCE_PAGE = {
  content: [FAKE_ATTENDANCE],
  page: { size: 20, totalElements: 1, totalPages: 1, number: 0 },
}

describe('attendance API', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset()
    vi.mocked(apiClient.post).mockReset()
  })

  describe('clockIn', () => {
    it('sends POST with no business body and no identity data', async () => {
      vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_ATTENDANCE })

      await attendanceApi.clockIn()

      expect(apiClient.post).toHaveBeenCalledWith('/api/employees/me/attendance/clock-in')
      expect(apiClient.post).toHaveBeenCalledTimes(1)
    })

    it('returns the response data', async () => {
      vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_ATTENDANCE })

      const result = await attendanceApi.clockIn()

      expect(result).toBe(FAKE_ATTENDANCE)
    })
  })

  describe('clockOut', () => {
    it('sends POST with no business body and no identity data', async () => {
      vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_CLOSED_ATTENDANCE })

      await attendanceApi.clockOut()

      expect(apiClient.post).toHaveBeenCalledWith('/api/employees/me/attendance/clock-out')
      expect(apiClient.post).toHaveBeenCalledTimes(1)
    })

    it('returns the response data', async () => {
      vi.mocked(apiClient.post).mockResolvedValue({ data: FAKE_CLOSED_ATTENDANCE })

      const result = await attendanceApi.clockOut()

      expect(result).toBe(FAKE_CLOSED_ATTENDANCE)
    })
  })

  describe('getTodayAttendance', () => {
    it('sends GET to the exact today path', async () => {
      const fakeStatus: AttendanceTodayResponse = { state: 'CLOCKED_IN', attendance: FAKE_ATTENDANCE }
      vi.mocked(apiClient.get).mockResolvedValue({ data: fakeStatus })

      await attendanceApi.getTodayAttendance()

      expect(apiClient.get).toHaveBeenCalledWith('/api/employees/me/attendance/today')
      expect(apiClient.get).toHaveBeenCalledTimes(1)
    })

    it.each([
      ['NOT_CLOCKED_IN', null],
      ['CLOCKED_IN', FAKE_ATTENDANCE],
      ['CLOCKED_OUT', FAKE_CLOSED_ATTENDANCE],
    ] as const)('preserves state %s with its attendance value', async (state, attendance) => {
      const fakeStatus: AttendanceTodayResponse = { state, attendance }
      vi.mocked(apiClient.get).mockResolvedValue({ data: fakeStatus })

      const result = await attendanceApi.getTodayAttendance()

      expect(result.state).toBe(state)
      expect(result.attendance).toBe(attendance)
    })

    it('NOT_CLOCKED_IN preserves attendance: null exactly (not undefined or coerced)', async () => {
      const fakeStatus: AttendanceTodayResponse = { state: 'NOT_CLOCKED_IN', attendance: null }
      vi.mocked(apiClient.get).mockResolvedValue({ data: fakeStatus })

      const result = await attendanceApi.getTodayAttendance()

      expect(result.attendance).toBeNull()
    })
  })

  describe('listOwnAttendance', () => {
    it('defaults to page 0 and size 20', async () => {
      vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_ATTENDANCE_PAGE })

      await attendanceApi.listOwnAttendance()

      expect(apiClient.get).toHaveBeenCalledWith('/api/employees/me/attendance', {
        params: { page: 0, size: 20 },
      })
    })

    it('sends only page and size -- no sort, no employeeId', async () => {
      vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_ATTENDANCE_PAGE })

      await attendanceApi.listOwnAttendance()

      const call = vi.mocked(apiClient.get).mock.calls[0]
      const params = (call[1] as { params: Record<string, unknown> }).params
      expect(Object.keys(params).sort()).toEqual(['page', 'size'])
      expect(params).not.toHaveProperty('sort')
      expect(params).not.toHaveProperty('employeeId')
    })

    it('forwards custom page and size values', async () => {
      vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_ATTENDANCE_PAGE })

      await attendanceApi.listOwnAttendance(2, 5)

      expect(apiClient.get).toHaveBeenCalledWith('/api/employees/me/attendance', {
        params: { page: 2, size: 5 },
      })
    })

    it('returns the PagedModel response without client-side reordering, filtering, or mutation', async () => {
      vi.mocked(apiClient.get).mockResolvedValue({ data: FAKE_ATTENDANCE_PAGE })

      const result = await attendanceApi.listOwnAttendance()

      expect(result).toBe(FAKE_ATTENDANCE_PAGE)
      expect(result.content).toBe(FAKE_ATTENDANCE_PAGE.content)
    })
  })

  describe('error handling', () => {
    it('clockIn rejects through the existing boundary without a custom error format', async () => {
      const axiosError = { response: { status: 409, data: { code: 'ATTENDANCE_ALREADY_CLOCKED_IN' } } }
      vi.mocked(apiClient.post).mockRejectedValue(axiosError)

      await expect(attendanceApi.clockIn()).rejects.toBe(axiosError)
    })

    it('listOwnAttendance rejects through the existing boundary without a custom error format', async () => {
      const axiosError = { response: { status: 500 } }
      vi.mocked(apiClient.get).mockRejectedValue(axiosError)

      await expect(attendanceApi.listOwnAttendance()).rejects.toBe(axiosError)
    })
  })
})
