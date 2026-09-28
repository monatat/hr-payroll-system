# HR Payroll System

## Overview

A full-stack HR and payroll application built as a portfolio project. It pairs a React/TypeScript
single-page app with a secured Spring Boot REST API and a PostgreSQL database, and covers employee
records, salary structures, leave, attendance, payroll runs, payslip PDFs, reports, and role-specific
dashboards.

There are two roles:

- **HR_ADMIN** manages departments, employees, salaries, leave approvals, payroll, and reports.
- **EMPLOYEE** sees only their own profile, salary history, leave, payslips, and attendance.

It is intended for local demonstration and portfolio review. **It is not production payroll software**
and does not implement real statutory compliance (see [Disclaimer](#disclaimer)).

## Features

### HR Admin

- **Dashboard**: active employee total, headcount by department, pending leave total, latest payroll run,
  and quick actions.
- **Departments**: list and create.
- **Employees**: search and filter, create, edit, and activate/deactivate. Sensitive identity and bank
  fields are excluded from normal employee responses and read through a separate HR Admin-only endpoint.
- **Salary structures**: per-employee salary history and new salary structures.
- **Leave requests**: filter by status, review, approve, or reject.
- **Payroll**: calculate a payroll run for a period, view run detail and payslips, and retry a failed run.
- **Payslip PDFs**: generate PDFs for a run and download individual payslips.
- **Reports**: annual payroll cost with monthly totals, and current active headcount by department.

Audit events are recorded server-side for logins and data changes. There is no audit-log UI.

### Employee

- **Dashboard**: profile summary, today's attendance, leave balances, recent leave requests, latest
  payslip, and quick actions.
- **Profile**: view own profile and update own phone number (the only self-editable field).
- **Salary history**: own salary structures.
- **Leave**: own leave balances, submit leave requests, and view request history.
- **Payslips**: own payslips, with PDF download once HR has generated it.
- **Attendance**: clock in and out, today's attendance state, and attendance history.

## Technology Stack

| Area | Technologies |
|---|---|
| Frontend | React 19, TypeScript, Vite 8, Tailwind CSS 4, React Router 7, Axios |
| Backend | Java 21, Spring Boot 3.5.16 (Web, Validation, Security), JWT via JJWT 0.12.6, Spring Data JPA / Hibernate |
| Database | PostgreSQL 16, Flyway migrations |
| PDF | Apache PDFBox 3.0.8 |
| Backend tests | JUnit 5, Mockito, MockMvc |
| Frontend tests and linting | Vitest, Testing Library (React, user-event, jest-dom), jsdom, oxlint |
| Build and local infrastructure | Maven Wrapper 3.3.4 (Maven 3.9.16), npm, Docker Compose (PostgreSQL only) |

## Architecture and Repository Layout

```
React SPA (TypeScript, Vite)
    |  JSON over HTTP, JWT bearer token
    v
Spring Boot REST API (Java 21, modular monolith)
    |  JPA / Flyway
    v
PostgreSQL 16 (Docker Compose)
```

```
.
├── backend/                     Spring Boot API, domain logic, Flyway migrations, backend tests
├── frontend/                    React/Vite application and frontend tests
├── docs/
│   └── implementation-status.md Detailed implementation and verification history
├── docker-compose.yml           Local PostgreSQL service
└── .env.example                 Example values for Docker Compose and Vite
```

About `.env`: copying `.env.example` to `.env` is optional. Docker Compose reads the root `.env`, and
Vite reads its `VITE_*` variables from the repository root (its `envDir` is `../`). **Spring Boot does not
load `.env`.** The backend reads real environment variables, which the instructions below set in the
PowerShell session that starts it. If you change a `DB_*` value in `.env`, set the same value in that
session too.

## Prerequisites

- JDK 21 (for example, Eclipse Temurin 21). No global Maven install is needed; the Maven Wrapper is
  included.
- Node.js 22.12 or newer, and npm.
- Docker Desktop with Docker Compose v2.
- Git, if you are cloning the repository.

All commands below are for Windows PowerShell and assume you start in the repository root.

## Quick Start — Demo Mode

This is the quickest way to see both roles with realistic data.

> **Demo mode is local and opt-in only.** Never enable it in production. Use a fresh database: on first
> start the backend creates a small demo dataset. Later restarts detect the complete dataset and change
> nothing. If partial or conflicting demo data exists (including an existing payroll run for the previous
> month), startup stops safely and writes nothing.

**1. Start a fresh PostgreSQL database** (terminal 1, repository root):

```powershell
docker compose up -d postgres
docker compose ps
```

Wait until `docker compose ps` shows the `postgres` service as `healthy`. A fresh clone gets a new, empty
database volume. If you have used this project's database before, see
[Stopping and Resetting](#stopping-and-resetting) to start from an empty one.

**2. Start the backend in demo mode** (terminal 2):

```powershell
cd backend

# Fresh random JWT signing secret for this session (never reuse the .env.example placeholder)
$jwtBytes = New-Object byte[] 48
$jwtRng = [Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRng.GetBytes($jwtBytes)
$jwtRng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
Remove-Variable jwtBytes, jwtRng

# Opt in to the demo dataset
$env:SPRING_PROFILES_ACTIVE = "demo"
$env:DEMO_DATA_ENABLED = "true"

# Choose two passwords of at least 12 characters each
$demoAdminSecure = Read-Host "Demo HR Admin password (minimum 12 characters)" -AsSecureString
$demoAdminCredential = New-Object System.Net.NetworkCredential("", $demoAdminSecure)
$env:DEMO_ADMIN_PASSWORD = $demoAdminCredential.Password

$demoEmployeeSecure = Read-Host "Demo Employee password (minimum 12 characters)" -AsSecureString
$demoEmployeeCredential = New-Object System.Net.NetworkCredential("", $demoEmployeeSecure)
$env:DEMO_EMPLOYEE_PASSWORD = $demoEmployeeCredential.Password

Remove-Variable demoAdminSecure, demoAdminCredential
Remove-Variable demoEmployeeSecure, demoEmployeeCredential

.\mvnw.cmd spring-boot:run
```

The log reports `Demo dataset created` on first start and `Demo dataset already present` on later
starts. To check the API from another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

The expected result has `status` `UP` and `service` `hr-payroll-backend`.

**3. Start the frontend** (terminal 3):

```powershell
cd frontend
npm ci
npm run dev
```

**4. Sign in** at <http://localhost:5173/login>:

| Account | Email | Lands on |
|---|---|---|
| HR Admin | `demo.admin@demo.local` | `/admin` |
| Employee (DEMO-001) | `demo.employee@demo.local` | `/employee` |

The passwords are the ones you entered in step 2. They are applied only when the dataset is first
created. Later demo-mode starts still require both variables (12+ characters) but never change the stored
passwords.

**What the demo dataset contains:**

- 2 departments and 2 active employees.
- An HR Admin login and an Employee login linked to the first employee.
- Salary structures for both employees.
- Annual and Sick leave balances for the linked employee, with one approved and one pending Annual leave
  request.
- A calculated payroll run for the previous month, with 2 payslips.
- No attendance records and no generated PDFs; you can clock in or generate PDFs yourself.

## Normal Development Setup

Normal mode starts with an empty database and creates no demo data.

**1. Optional: create `.env`** (only needed if you want non-default Compose or Vite values):

```powershell
Copy-Item .env.example .env
```

**2. Start PostgreSQL:**

```powershell
docker compose up -d postgres
docker compose ps
```

**3. Start the backend** (separate terminal):

```powershell
cd backend

$jwtBytes = New-Object byte[] 48
$jwtRng = [Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRng.GetBytes($jwtBytes)
$jwtRng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
Remove-Variable jwtBytes, jwtRng

# Optional: create the first HR Admin account on startup
$env:BOOTSTRAP_ADMIN_EMAIL = "your-admin@example.com"

$bootstrapSecure = Read-Host "Bootstrap HR Admin password" -AsSecureString
$bootstrapCredential = New-Object System.Net.NetworkCredential("", $bootstrapSecure)
$env:BOOTSTRAP_ADMIN_PASSWORD = $bootstrapCredential.Password

Remove-Variable bootstrapSecure, bootstrapCredential

.\mvnw.cmd spring-boot:run
```

The bootstrap admin is the only way to create the first HR Admin in normal mode. It is created once and
skipped on later starts. Startup fails if that email already belongs to a non-admin account. If both
variables are unset, no admin is created.

**4. Start the frontend** (separate terminal):

```powershell
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173>. The layout shows an `API: UP` badge once the frontend can reach the
backend. The default CORS origin (`http://localhost:5173`) already matches the Vite dev server.

Normal mode has **no way to create EMPLOYEE login accounts or leave balances** through the UI or API. HR
can create employees, salaries, and payroll, but demonstrating the Employee side requires demo mode.

## Configuration Reference

| Variable | Read by | Default | Notes |
|---|---|---|---|
| `DB_HOST` | Backend | `localhost` | |
| `DB_PORT` | Compose, backend | `5433` | Host port, chosen to avoid clashing with a local PostgreSQL on 5432 |
| `DB_NAME` | Compose, backend | `hr_payroll` | |
| `DB_USERNAME` | Compose, backend | `hr_payroll_app` | |
| `DB_PASSWORD` | Compose, backend | `changeme_local_only` | Local-only default; change it for anything shared |
| `SERVER_PORT` | Backend | `8080` | |
| `JWT_SECRET` | Backend | none (required) | At least 32 bytes; the backend refuses to start without it |
| `JWT_EXPIRATION_MINUTES` | Backend | `30` | Access-token lifetime; there are no refresh tokens |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | Backend | unset | Optional first HR Admin account |
| `SPRING_PROFILES_ACTIVE` | Backend | unset | `demo` enables the demo profile |
| `DEMO_DATA_ENABLED` | Backend | `false` | Must be `true` together with the `demo` profile |
| `DEMO_ADMIN_PASSWORD` / `DEMO_EMPLOYEE_PASSWORD` | Backend | none | Required in demo mode; at least 12 characters |
| `CORS_ALLOWED_ORIGIN` | Backend | `http://localhost:5173` | Must match the frontend origin |
| `PAYROLL_PDF_STORAGE_DIR` | Backend | `./data/payroll-pdfs` | See [PDF Storage](#pdf-storage) |
| `VITE_API_BASE_URL` | Frontend (Vite) | `http://localhost:8080` | Backend base URL |

"Compose" and "Frontend (Vite)" values can come from the root `.env`. "Backend" values must be set in
the environment of the process that runs `mvnw.cmd`.

## PDF Storage

Payslip PDFs are written to local disk, under `PAYROLL_PDF_STORAGE_DIR`. The default,
`./data/payroll-pdfs`, is relative to the backend's working directory, so it resolves to
`backend/data/payroll-pdfs` when you start the backend from `backend/`. This directory is gitignored.
PDFs exist only after HR generates them for a payroll run.

## Running Tests

**Backend** (PostgreSQL must be running):

```powershell
cd backend
.\mvnw.cmd clean verify
```

> Backend integration tests run against the database configured by the `DB_*` settings, by default the
> same local Compose database the application uses, and they write to it (for example, audit rows). Run
> them only against a normal development or disposable database that holds no important data, and not
> while demo mode is running. No `JWT_SECRET` is needed; the test profile supplies its own.

**Frontend:**

```powershell
cd frontend
npm ci
npm test
npm run build
```

`npm test` runs the full Vitest suite once. On a busy machine,
`npx vitest run --no-file-parallelism` gives a slower but more stable run. `npm run lint` runs oxlint.

**Latest verified baseline (2026-09-28):** backend 612 tests, 0 failures, 0 errors, 3 skipped, with 13
Flyway migrations. Frontend 582 tests across 29 files, 0 failures, and a successful production build.

## Stopping and Resetting

**Stop without losing data:** press `Ctrl+C` in the backend and frontend terminals, then:

```powershell
docker compose stop
```

Run `docker compose up -d postgres` to start the database again; your data is still there.

The JWT secret and passwords live only in the environment of the PowerShell session that started the
backend. Closing that terminal discards them, or you can clear them explicitly:

```powershell
# Optional: clear sensitive values from this PowerShell session.
Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:DEMO_ADMIN_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:DEMO_EMPLOYEE_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
```

**Reset everything (destructive):**

> **Warning:** this permanently deletes the project's PostgreSQL volume, including all employees,
> payroll data, accounts, and any demo dataset. It cannot be undone.

```powershell
docker compose down -v
```

To also delete generated payslip PDFs (destructive; default location when started from `backend/`):

```powershell
Remove-Item -Recurse -Force .\backend\data\payroll-pdfs
```

## Security Notes

- Never commit `.env` or real credentials. `.env` is gitignored.
- Replace every example credential. Generate a fresh random `JWT_SECRET` as shown above; never reuse the
  placeholder in `.env.example`.
- Demo passwords are for local demonstration only, and the `demo` profile must never be enabled in
  production.
- Authorization is enforced by the backend on every request (`HR_ADMIN` / `EMPLOYEE`). Frontend route
  guards are only a UX convenience.
- Self-service endpoints take the employee's identity from the JWT, never from an ID in the URL, and
  employees can read only their own payslips and leave data.
- Passwords are stored as BCrypt hashes. Login failures return the same generic `401` whatever the cause.
- The browser keeps the access token in `sessionStorage`. There is no refresh token or server-side
  revocation; tokens expire after 30 minutes by default.
- PDFs are stored on local disk, and PostgreSQL data persists in a Docker volume until you delete it.
- No production deployment configuration is included.

## Project Status

The application's feature set is complete for local demonstration: every route in the UI is a working
page for its role. Detailed, phase-by-phase implementation and verification history is in
[docs/implementation-status.md](docs/implementation-status.md).

## Limitations and Non-Goals

- No account invitations, self-registration, or EMPLOYEE login creation. Accounts come only from the
  bootstrap admin or the demo dataset.
- No UI or API for creating leave balances; they come only from the demo dataset.
- No refresh tokens or token revocation.
- No charts and no CSV/report export (payslip PDFs are the only generated documents).
- No email, notifications, scheduled jobs, or external integrations.
- No deployment, hosting, or CI configuration; the project runs locally.
- Payroll uses simplified fixed rates, and business dates use the fixed `Asia/Kuala_Lumpur` timezone.

## Disclaimer

This project implements a **simplified, educational payroll engine** for portfolio purposes. It does
**not** represent real Malaysian statutory payroll compliance. EPF, SOCSO, EIS, and income tax are
calculated with fixed demonstration percentages of gross pay (11%, 0.5%, 0.2%, and 5%) and must never be
used for actual payroll processing.
