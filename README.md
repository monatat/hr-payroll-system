# HR Payroll Management System

A portfolio/demo HR Payroll Management System built to showcase full-stack
engineering: REST API design, Spring Boot, React/TypeScript, PostgreSQL
schema design, authentication and authorization, transaction and concurrency
handling, payroll business rules, automated testing, PDF generation, and
clean architecture.

**This is not a production payroll system.** See the disclaimer below.

## Architecture

```
React SPA (TypeScript)
    | HTTPS / JSON
    v
Spring Boot REST API (Java 21, modular monolith)
    v
PostgreSQL (Flyway-migrated)
```

Single deployable backend, single frontend SPA, single PostgreSQL database.
No microservices, message brokers, or distributed infrastructure — see
`docs/decisions/` (added in a later phase) for the architectural reasoning.

## Technology stack

**Frontend:** React, TypeScript, Vite, Tailwind CSS v4, React Router, Axios,
Vitest, React Testing Library.

**Backend:** Java 21, Spring Boot 3.5.16, Maven (via Maven Wrapper), Spring
Web, Spring Data JPA, Spring Validation, Spring Security, JJWT (JWT
issuing/parsing), PostgreSQL driver, Flyway, JUnit 5, Mockito, MockMvc.

**Infrastructure:** Docker Compose (PostgreSQL only), environment variables
for configuration.

## Prerequisites

- **Java 21** (e.g. [Eclipse Temurin 21](https://adoptium.net/)) — no global
  Maven install needed, the project uses the Maven Wrapper (`mvnw`/`mvnw.cmd`).
- **Node.js** (18+) and **npm**
- **Docker** and **Docker Compose**

## Environment setup

Copy the example environment file and adjust if needed:

```powershell
copy .env.example .env
```

Most defaults work out of the box for local development, **except**
`JWT_SECRET`: the backend refuses to start with it unset or too short (it
never falls back to a known/default signing secret). Set your own local
value in `.env`, e.g.:

```powershell
# any string at least 32 bytes long; this is NOT a production secret
JWT_SECRET=REPLACE_WITH_A_LONG_RANDOM_LOCAL_ONLY_SECRET_AT_LEAST_32_BYTES
```

`.env` is never committed (see `.gitignore`).

> Note: this project's PostgreSQL is mapped to host port **5433** by default
> (not 5432), to avoid clashing with any other local Postgres instance you
> may already have running. Change `DB_PORT` in `.env` if you need a
> different port.

## Running locally (Windows)

**1. Start PostgreSQL:**

```powershell
docker compose up -d postgres
```

Wait for it to report healthy:

```powershell
docker compose ps
```

**2. Start the backend** (from the `backend/` directory):

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The backend reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, and `SERVER_PORT` from the environment, with sensible local
defaults baked in if they're not set (matching `.env.example`). Once
started, verify:

```powershell
curl http://localhost:8080/api/health
```

Expected response:

```json
{ "status": "UP", "service": "hr-payroll-backend" }
```

**3. Start the frontend** (from the `frontend/` directory, in a separate
terminal):

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 — the placeholder navigation and an "API: UP"
badge (confirming the frontend successfully reached the backend) should be
visible.

## Authentication (Phase 4)

The backend issues stateless JWT (HS256) access tokens from
`POST /api/auth/login`. Key design decisions, all deliberate demo-scope
tradeoffs:

- **Access token only, no refresh token.** Default TTL is 30 minutes,
  configurable via `JWT_EXPIRATION_MINUTES`. There is no server-side
  revocation or blocklist -- if an account is disabled after a token was
  issued, that token remains valid until it naturally expires. The short
  TTL bounds this exposure; a production system would add revocation.
- **BCrypt (strength 10)** for password hashing.
- **JWT_SECRET has no usable default.** The app fails fast at startup if
  it's missing or shorter than 32 bytes, rather than silently signing
  tokens with a known secret.
- **sessionStorage** (not `localStorage`) holds the token in the browser --
  it clears on tab close and isn't shared across tabs, which is a
  reasonable tradeoff for a demo JSON API with no refresh token to protect.
- **Frontend route guards are UX only.** The backend independently enforces
  authentication and role-based authorization (`ROLE_HR_ADMIN` /
  `ROLE_EMPLOYEE`) on every request; the frontend's `ProtectedRoute` just
  avoids flashing pages the user isn't allowed to see.
- **Bootstrap admin:** set `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`
  to have an initial `HR_ADMIN` account created on startup (idempotent --
  safe to leave set across restarts). This is a demo-appropriate
  provisioning mechanism only, **not** a production secret-management
  system. If the configured email already exists as a non-admin account,
  startup fails loudly rather than silently promoting it.
- Login failures (nonexistent email, wrong password, disabled account) all
  return an identical generic `401` -- the response never reveals which
  case occurred.

## Departments and employees (Phase 5)

- `GET /api/departments` (any authenticated user) / `POST /api/departments`
  (HR_ADMIN only, rejects duplicate names with `409`).
- `GET /api/employees` (paginated, searchable by name/code, filterable by
  department/status), `GET /api/employees/{id}`, `POST /api/employees`,
  `PUT /api/employees/{id}`, `PATCH /api/employees/{id}/status` -- all
  HR_ADMIN only. Employee code is immutable after creation; there is no
  delete endpoint (deactivation via status is the only "removal").
- `GET /api/employees/me` / `PUT /api/employees/me` -- EMPLOYEE only. There
  is no `{id}` in these routes: the employee identity comes only from the
  authenticated JWT, so there's nothing for a client to spoof. The **only**
  field an employee can self-edit is `phoneNumber` (added via
  `V10__add_employee_phone_number.sql`, a single nullable column).
- **IC number, bank name, and bank account number are never returned by
  any of the endpoints above.** They can be supplied as input when an
  HR_ADMIN creates/updates an employee, but are only ever readable through
  the existing Phase 4 `GET /api/employees/{id}/sensitive` endpoint.
- Creating an employee **never** creates a login account. An `Employee` can
  exist with no linked `User` -- account provisioning (giving a new
  employee login credentials) is intentionally out of scope; this project
  does not implement invitations, password generation, or email delivery.

## Running tests

**Backend** (requires PostgreSQL running via `docker compose up -d postgres`,
since Flyway/JPA wiring is verified against a real database, not a mock):

```powershell
cd backend
.\mvnw.cmd test
```

**Frontend:**

```powershell
cd frontend
npm run test
```

## Current development phase

**Phase 5 — Department & employee management** is complete, on top of
Phase 4's authentication/authorization. Department and employee CRUD,
role-based authorization, EMPLOYEE self-service (phone number only), and
audit logging for all of the above are implemented (see "Departments and
employees" above).

**No other business functionality exists yet.** There is no salary, leave,
attendance, payroll processing, or reports. These are implemented in later
phases (see `docs/implementation-status.md`).

## Important disclaimer

This project implements a **simplified, educational payroll engine** for
portfolio purposes. It does **not** represent real Malaysian statutory
payroll compliance. EPF, SOCSO, EIS, and income tax calculations (once
implemented) use fixed demonstration percentages and must never be used for
actual payroll processing.
