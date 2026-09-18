import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { AppLayout } from './layout/AppLayout'
import { AdminDashboardPage } from './routes/admin/AdminDashboardPage'
import { DepartmentsPage } from './routes/admin/DepartmentsPage'
import { EmployeeCreatePage } from './routes/admin/EmployeeCreatePage'
import { EmployeeDetailPage } from './routes/admin/EmployeeDetailPage'
import { EmployeeEditPage } from './routes/admin/EmployeeEditPage'
import { EmployeeListPage } from './routes/admin/EmployeeListPage'
import { LeaveRequestDetailPage } from './routes/admin/LeaveRequestDetailPage'
import { LeaveRequestListPage } from './routes/admin/LeaveRequestListPage'
import { PayrollRunDetailPage } from './routes/admin/PayrollRunDetailPage'
import { PayrollRunListPage } from './routes/admin/PayrollRunListPage'
import { SalaryHistoryPage as AdminSalaryHistoryPage } from './routes/admin/SalaryHistoryPage'
import { EmployeeProfilePage } from './routes/employee/EmployeeProfilePage'
import { LeaveDashboardPage } from './routes/employee/LeaveDashboardPage'
import { PayslipDetailPage } from './routes/employee/PayslipDetailPage'
import { PayslipListPage } from './routes/employee/PayslipListPage'
import { SalaryHistoryPage as EmployeeSalaryHistoryPage } from './routes/employee/SalaryHistoryPage'
import { LoginPage } from './routes/LoginPage'
import { NotFoundPage } from './routes/NotFoundPage'
import { PlaceholderPage } from './routes/PlaceholderPage'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Navigate to="/login" replace />} />
        <Route path="/login" element={<LoginPage />} />

        <Route
          path="/admin"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <AdminDashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/departments"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <DepartmentsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/employees"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <EmployeeListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/employees/new"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <EmployeeCreatePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/employees/:id"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <EmployeeDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/employees/:id/edit"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <EmployeeEditPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/employees/:id/salary"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <AdminSalaryHistoryPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/leave"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <LeaveRequestListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/leave/:id"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <LeaveRequestDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/payroll"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <PayrollRunListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/payroll/:id"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <PayrollRunDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/reports"
          element={
            <ProtectedRoute allowedRoles={['HR_ADMIN']}>
              <PlaceholderPage title="Reports" description="Payroll cost and headcount reports." />
            </ProtectedRoute>
          }
        />

        <Route
          path="/employee"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <PlaceholderPage title="Employee Dashboard" description="Your personal overview." />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/profile"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <EmployeeProfilePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/salary"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <EmployeeSalaryHistoryPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/attendance"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <PlaceholderPage title="My Attendance" description="Clock in/out and view history." />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/leave"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <LeaveDashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/payslips"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <PayslipListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/employee/payslips/:id"
          element={
            <ProtectedRoute allowedRoles={['EMPLOYEE']}>
              <PayslipDetailPage />
            </ProtectedRoute>
          }
        />

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
