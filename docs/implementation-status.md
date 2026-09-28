# Implementation status

## Phase 1 — Requirements and architecture
Status: **Complete.** Architecture, ERD, entity design, payroll/leave
lifecycles, authentication/authorization model, and API conventions
finalized (design only, no code).

## Phase 2 — Project foundation
Status: **Complete.**

- Spring Boot 3.5.16 / Java 21 backend scaffold, Maven Wrapper committed.
- PostgreSQL 16 via Docker Compose (host port 5433).
- Flyway wired and verified with one placeholder migration
  (`V1__create_flyway_verification_table.sql`) — no business schema yet.
- `GET /api/health` implemented.
- React + TypeScript + Vite frontend scaffold with Tailwind CSS v4, React
  Router placeholder routes, and a centralized Axios client.
- No authentication, no business modules — out of scope for this phase.

## Phase 3 — Database
Status: **Complete.**

- `V2__drop_flyway_verification_table.sql` removed the Phase 2 throwaway
  table. `V1` itself was never edited.
- `V3`–`V9` created all 11 business tables (`departments`, `employees`,
  `users`, `salary_structures`, `leave_types`, `leave_balances`,
  `leave_requests`, `attendance`, `payroll_runs`, `payslips`, `audit_logs`)
  with BIGINT identity PKs, FKs, UNIQUE/CHECK constraints, and indexes per
  the finalized Phase 1 design.
- No JPA entities, repositories, services, or controllers were added — the
  schema exists in the database only, verified via raw SQL/`psql`.
- PostgreSQL container timezone set to `Asia/Kuala_Lumpur` (`TZ`/`PGTZ` in
  `docker-compose.yml`) so `DEFAULT now()` timestamps stay consistent with
  the application's fixed timezone. All timestamp columns remain plain
  `TIMESTAMP` (no time zone) — no UTC conversion layer was introduced.
- Verified against a genuinely clean database (fresh volume): migrations
  apply in order, all constraints exist, and invalid inserts (bad
  role/employee_id combination, duplicate attendance day, duplicate payroll
  period, inverted date range, negative monetary value) are all rejected by
  PostgreSQL directly.

## Phase 4 — Authentication and authorization
Status: **Complete.**

- Spring Security added (`spring-boot-starter-security`), configured
  stateless (no sessions), CSRF disabled, form login/HTTP Basic disabled.
  CORS is now configured via a single `CorsConfigurationSource` inside
  `SecurityConfig`; the old standalone `WebMvcConfigurer`-based `CorsConfig`
  was removed.
- `POST /api/auth/login` issues HS256 JWT access tokens (JJWT library) with
  minimal claims only: `sub` (user id), `role`, `employeeId` (EMPLOYEE
  tokens only), `iat`, `exp`, `iss`. No refresh tokens; default TTL 30
  minutes via `JWT_EXPIRATION_MINUTES`. `JWT_SECRET` has no usable default
  -- the app fails fast at startup if it's missing or under 32 bytes.
- Login authenticates directly against `UserRepository` (not via Spring
  Security's `AuthenticationManager`) so the "identical response for every
  failure reason" guarantee lives in one place (`AuthService`). Nonexistent
  email, wrong password, and disabled account all produce the exact same
  `401` body; the specific reason is only ever recorded server-side in the
  audit log.
- `User` (maps existing V4 `users` table) and a minimal read-only
  `Employee` (maps only `id`/`ic_number`/`bank_name`/`bank_account_no` from
  the existing V3 `employees` table) JPA entities were added. **No new
  Flyway migration was needed or created** -- V4's schema already had
  everything Phase 4 requires; schema remains at V9.
- `JwtAuthenticationFilter` populates the `SecurityContext` directly from
  validated JWT claims -- no per-request database lookup.
- `GET /api/employees/{id}/sensitive` (HR_ADMIN only, via
  `@PreAuthorize("hasRole('HR_ADMIN')")`) returns unmasked
  `icNumber`/`bankName`/`bankAccountNo` and writes a `SENSITIVE_DATA_ACCESS`
  audit row on every successful access. No general employee CRUD, listing,
  detail, or self-service endpoint was added -- those belong to a later
  phase. No ownership-check helper was added either, per the approved scope
  (deferred until the first real employee self-service endpoint needs it).
- Thin synchronous `AuditService` (existing V9 `audit_logs` table) records
  `LOGIN_SUCCESS`, `LOGIN_FAILED`, and `SENSITIVE_DATA_ACCESS`. Audit
  failures are caught and logged, never allowed to affect authentication
  behavior. Audit `details` never contain passwords, hashes, JWTs, IC
  numbers, or bank values -- only non-sensitive metadata (e.g. attempted
  email, failure reason, or field *names* for sensitive-data access).
- `BootstrapAdminRunner` (`ApplicationRunner`) creates an initial
  `HR_ADMIN` from `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` if
  both are set and idempotently does nothing if that email already exists
  as HR_ADMIN. If it exists as a non-admin account, startup fails loudly
  instead of promoting/modifying it. Not a production secret-management
  mechanism -- documented as such in the README.
- Security errors use `ProblemDetail` (RFC 7807): `401` for
  unauthenticated/invalid credentials, `403` for authorized-but-forbidden,
  `400` for malformed login requests (bean validation).
- Frontend: `AuthContext` (sessionStorage-backed, decodes JWT claims
  client-side for UX only), a real login form, an Axios request
  interceptor attaching the Bearer token, a response interceptor clearing
  state on `401` (no retry/refresh logic), and a `ProtectedRoute` wrapper
  guarding `/admin/*` (HR_ADMIN) and `/employee/*` (EMPLOYEE) routes.
  Explicitly UX-only -- the backend is the real authorization boundary.
- Backend: 25 tests (MockMvc integration tests against the real local
  Postgres + pure unit tests for `JwtService` and `BootstrapAdminRunner`).
  Frontend: 14 tests (Vitest/RTL) covering login, auth-state persistence,
  role-aware route guarding, and the Axios Bearer-token interceptor.
  `./mvnw.cmd clean verify`, `npm run test`, and `npm run build` all pass.

## Phase 5 — Department and employee management
Status: **Complete.**

- `V10__add_employee_phone_number.sql` adds one nullable column,
  `phone_number VARCHAR(20)`, to `employees` -- the only schema change this
  phase needed. It exists solely so EMPLOYEE self-service has a genuine,
  non-sensitive, non-HR-controlled field to edit (see below). V1–V9 were
  not touched; schema is now at V10.
- `GET /api/departments` (any authenticated user, ordered by name) and
  `POST /api/departments` (HR_ADMIN only) -- new `Department` JPA entity
  mapping the previously-unmapped V3 `departments` table. Duplicate names
  are rejected with `409 DUPLICATE_DEPARTMENT_NAME`, mapped from the
  existing `uq_departments_name` DB constraint (never a racy pre-check).
- `Employee` (extended from Phase 4's minimal mapping) now carries the
  full business record: `employeeCode`, `fullName`, a `Department`
  association, `jobTitle`, `employmentStatus`, `hireDate`, `phoneNumber`,
  plus the existing sensitive fields. HR_ADMIN endpoints:
  `GET /api/employees` (paginated via `PagedModel`, deterministic sort with
  an `id` tiebreaker, optional `q`/`departmentId`/`status` filters via one
  JPQL query), `GET /api/employees/{id}`, `POST /api/employees`,
  `PUT /api/employees/{id}` (employee code immutable),
  `PATCH /api/employees/{id}/status` (idempotent ACTIVE/INACTIVE toggle, no
  delete endpoint). Duplicate `employee_code`/`ic_number` map to
  `409 DUPLICATE_EMPLOYEE_CODE`/`409 DUPLICATE_IC_NUMBER`; an unknown
  department to `404`.
- `icNumber`/`bankName`/`bankAccountNo` are accepted as write-only input on
  create (the authorized HR_ADMIN supplies them) but **never** appear in
  any Phase 5 response -- `EmployeeResponse` structurally excludes them.
  The Phase 4 `GET /api/employees/{id}/sensitive` endpoint remains the only
  read path for them, unchanged. Because general responses never echo
  these fields back, `PUT /api/employees/{id}` treats a null/omitted
  icNumber/bankName/bankAccountNo as "leave unchanged" (never "clear it"),
  so an unrelated edit (e.g. job title) can never accidentally blank out
  sensitive data the form never had access to.
- `GET /api/employees/me` / `PUT /api/employees/me` (EMPLOYEE only) --
  deliberately have no `{id}` path variable; the employee identity comes
  only from the authenticated JWT principal's `employeeId` claim, so there
  is no client-suppliable ID to spoof. No ownership-comparison helper was
  introduced, per the approved scope. `phoneNumber` is the **only**
  self-editable field; the update DTO structurally cannot carry any other
  field (department/status/hireDate/IC/bank/role/password are all absent
  from it, so they can never be set this way regardless of what a client
  sends).
- `POST /api/employees` never creates, modifies, or touches a `users` row.
  An `Employee` can exist with zero linked `User` -- account provisioning
  (login credentials for a newly created employee) remains explicitly out
  of scope, exactly as approved. No password generation, invitation, or
  email infrastructure was added.
- Audit (existing `AuditService`, unchanged): `DEPARTMENT_CREATED`,
  `EMPLOYEE_CREATED` (employeeCode + departmentId only),
  `EMPLOYEE_UPDATED` / `EMPLOYEE_SELF_PROFILE_UPDATED` (changed field
  *names* only, never values), `EMPLOYEE_STATUS_CHANGED` (from/to). Reads
  are not audited, consistent with Phase 4.
- Uniqueness (department name, employee code, IC number) is enforced
  solely by the existing DB constraints; a shared `ApiExceptionHandler`
  translates the resulting `DataIntegrityViolationException` into a clean
  `409` by inspecting the underlying constraint name. No pre-check-then-
  insert race exists anywhere.
- Discovered and fixed during implementation: Hibernate's
  `SqlExceptionHelper` logs the raw JDBC exception message (including
  Postgres's own duplicate-key detail, which contains the offending value)
  at ERROR level on any SQL exception. Since that could put an IC number in
  the application log on a conflict, that logger is now set to `OFF` in
  `application.yml` -- the exception itself is still handled correctly and
  mapped to a clean `409`; only the redundant framework-level log line
  (which is not application code) is suppressed.
- Frontend: `/admin` (dashboard with links), `/admin/departments`,
  `/admin/employees` (search/filter/pagination), `/admin/employees/new`,
  `/admin/employees/:id` (detail; sensitive info loads only on explicit
  click, never by default), `/admin/employees/:id/edit`,
  `/employee/profile` (EMPLOYEE self-service). All reuse the existing
  `AuthContext`, `ProtectedRoute`, and Axios client -- no new state
  management library.
- Backend: 25 new tests (75 total with Phase 4) covering department
  RBAC/validation/conflicts/ordering, employee pagination/sorting/search/
  filtering, sensitive-field masking, create/update/status-change
  behavior (including employee-code immutability and status-change
  idempotency), `/me` identity resolution and self-service field
  restriction, and audit correctness. Frontend: 15 new tests (29 total)
  covering the new pages and route guards. `./mvnw.cmd clean verify`,
  `npm run test`, and `npm run build` all pass.

## Phase 6 — Salary management
Status: **Complete.**

- **No migration required.** V5's `salary_structures` table already had
  everything Phase 6 needed: `NUMERIC(12,2)` on all three monetary columns,
  `NOT NULL DEFAULT 0` on both allowances, the `effective_to >= effective_from`
  and non-negativity CHECK constraints, the employee FK, and an
  `(employee_id, effective_from)` index. V5's own migration comment already
  documented the intended employee-row-lock overlap strategy. V1–V10 were
  not touched; schema remains at V10.
- New `com.hrpayroll.backend.salary` package: `SalaryStructure` (JPA entity
  mapping V5), `SalaryStructureRepository`, `SalaryStructureService`,
  `SalaryStructureResponse`/`CreateSalaryStructureRequest` (records),
  `SalaryStructureController` (HR_ADMIN), `SalaryStructureSelfServiceController`
  (EMPLOYEE), plus `SalaryStructureOverlapException`/`SalaryExceptionHandler`
  (mirrors the existing `AuthenticationFailedException`/`AuthExceptionHandler`
  pattern). `EmployeeRepository` gained one additive method,
  `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), used only by salary
  writes.
- Endpoints: `GET`/`POST /api/employees/{employeeId}/salary-structures`,
  `GET /api/salary-structures/{id}` (all HR_ADMIN), and
  `GET /api/employees/me/salary-structures` (EMPLOYEE, identity from the JWT
  `employeeId` claim only, no path id -- same no-ownership-helper pattern as
  `EmployeeSelfServiceController`). Deliberately no
  `GET /api/salary-structures` (all-employees list), no `PUT`, no `DELETE`.
- **Append-oriented history**: the only mutation of an existing row is one
  controlled succession transition -- creating a new structure that starts
  after the employee's current open-ended structure began automatically
  closes that row's `effective_to` to `newFrom - 1 day`, in the same
  transaction, under the same employee-row lock, before the new row is
  inserted. If the proposed range doesn't validate (including against the
  adjusted range), nothing is mutated or inserted. Historical corrections to
  old/closed/payrolled structures are out of API scope entirely.
- **Effective dates are inclusive, `effective_to = NULL` means open-ended.**
  Two ranges overlap iff `newFrom <= existingTo` AND `existingFrom <= newTo`,
  treating a `NULL` end as +infinity. Adjacent ranges (e.g.
  `2026-01-01..2026-06-30` then `2026-07-01..OPEN`) do not overlap; sharing
  any single day (including the same `effectiveFrom`) does. Gaps between
  structures are explicitly allowed. A private static
  `SalaryStructureService.covers(structure, periodStart, periodEnd)` helper
  implements the future Payroll Engine's coverage rule
  (`effectiveFrom <= periodStart AND (effectiveTo IS NULL OR effectiveTo >= periodEnd)`)
  and is unit-tested now; payroll calculation itself remains out of scope.
- **Concurrency**: `SalaryStructureService.create()` calls
  `EmployeeRepository.findByIdForUpdate(employeeId)` first (real
  `SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`), inside one
  `@Transactional` method that also does the succession check, overlap
  validation, and insert -- so the employee row lock is held for the whole
  operation and concurrent salary writes for the *same* employee are
  serialized by PostgreSQL itself, not by any Java-level lock.
  `SalaryStructureConcurrencyTest` proves this against the real local
  Postgres (no mocks, no `synchronized`, no sleeps): two threads race
  overlapping closed ranges for one employee via `CountDownLatch` and the
  real `SalaryStructureService`/`EmployeeRepository`/transaction manager --
  exactly one succeeds, the other gets `409 SALARY_STRUCTURE_OVERLAP`, and a
  direct `COUNT(*)` against `salary_structures` confirms exactly one row was
  written. A second test proves two concurrent *non-overlapping* adjacent
  requests for the same employee both succeed (the lock serializes, it
  doesn't over-reject).
- Validation: `basePay` required, `housingAllowance`/`transportAllowance`
  optional and normalized to `BigDecimal.ZERO` when omitted (mirrors the
  DB's own default), all three non-negative with
  `@Digits(integer = 10, fraction = 2)` matching `NUMERIC(12,2)` exactly.
  `effectiveFrom` required; `effectiveTo` before `effectiveFrom` is a `400`
  raised by the service (cross-field, not expressible as a single bean
  annotation). Overlap is `409 SALARY_STRUCTURE_OVERLAP`. Unknown
  employee/salary-structure ids are `404`.
- Audit (existing `AuditService`, unchanged): `SALARY_STRUCTURE_CREATED`
  only. `details` contains `employeeId`, `effectiveFrom`, `effectiveTo`
  (`"OPEN"` for null), and `closedPreviousStructureId` when succession
  occurred -- **never** `basePay`/`housingAllowance`/`transportAllowance`.
  Reads are not audited, consistent with every earlier phase.
- Frontend: `/admin/employees/:id/salary` (HR_ADMIN -- salary history table
  plus a create form with base pay/allowances/effective dates, open-ended
  shown as "Present", 400/409 errors surfaced from the API), linked from
  `EmployeeDetailPage`; `/employee/salary` (EMPLOYEE -- strictly read-only,
  renders no button/input/form elements at all). Both reuse the existing
  `AuthContext`/`ProtectedRoute`/Axios client; no new libraries.
- Backend: 51 new tests (152 total with Phases 1-5) covering create/read/list,
  authorization (401/403), 404s, all validation cases, open-ended/closed/
  future-dated/gap scenarios, every overlap/succession edge case from the
  approved spec (including same-day-boundary and same-`effectiveFrom`
  conflicts), the payroll coverage helper, audit content, and the two real
  Postgres concurrency tests. Frontend: 7 new tests (36 total) covering both
  pages' rendering, the create flow, 400/409 error display, and the
  read-only employee page's absence of interactive controls.
  `./mvnw.cmd clean verify`, `npm run test`, and `npm run build` all pass.
  A live-server smoke test (real Postgres, real JWT login) additionally
  confirmed create/read/adjacent-success/overlap-reject for HR_ADMIN,
  own-history read plus a `403` on the admin endpoint for EMPLOYEE, and
  audit rows free of raw salary amounts; all demo data created for that
  check was deleted afterward.

## Phase 7 — Leave management
Status: **Complete.** All sub-phases (7A, 7B-1, 7B-2, 7C-1, 7C-2, 7D-1, 7D-2,
7D-3) are complete, and final manual end-to-end verification across both
roles has now passed against the real Phase 7D backend and the confirmed
development database (see "Phase 7 final manual verification" below).

### Phase 7A — Leave reference data, balance reads, working-day foundation
Status: **Complete.**

- **`V11__seed_leave_types.sql` — data-only migration, no schema changes.**
  V6's `leave_types`/`leave_balances`/`leave_requests` tables (including the
  pre-provisioned `leave_requests.version` column for the optimistic
  locking Phase 7B will use) already had every column Phase 7 needs; V11
  only `INSERT`s two reference rows (`Annual Leave`, `Sick Leave`, both
  14.0 default annual entitlement days). "Unpaid Leave" is deliberately not
  seeded -- the balance-based approval model has no natural meaning for it
  without special-case behavior, which is out of scope. V1–V10 were not
  touched; schema is now at V11.
- New `com.hrpayroll.backend.leave` package: `LeaveType`/`LeaveBalance`
  (JPA entities mapping V6, read-only in 7A -- no setters beyond what JPA
  needs), `LeaveTypeRepository`/`LeaveBalanceRepository`,
  `LeaveTypeResponse`/`LeaveBalanceResponse` (records), `LeaveService`
  (read-only in 7A), `LeaveTypeController`, `LeaveBalanceSelfServiceController`,
  `LeaveBalanceController`, `LeaveRequestStatus` (enum, foundation only --
  no `LeaveRequest` entity/behavior exists yet), and `WorkingDayCalculator`
  (stateless, `countInclusive(start, end)`: Monday-Friday inclusive count,
  built directly from a `long` via `BigDecimal.valueOf` so no floating-point
  conversion is ever involved).
- Endpoints: `GET /api/leave-types` (any authenticated role, same pattern
  as `GET /api/departments`), `GET /api/leave-balances/me` (EMPLOYEE,
  identity from the JWT `employeeId` claim only), and
  `GET /api/employees/{employeeId}/leave-balances` (HR_ADMIN, approved
  addition beyond the original proposal, mirrors
  `GET /api/employees/{employeeId}/salary-structures`). Both balance
  endpoints share one `LeaveService.getBalancesForEmployee` method, exactly
  as Phase 6's `SalaryStructureService.listForEmployee` is shared by its
  two controllers, including the same defensive 404 on an unknown
  employeeId.
- `remainingDays` is computed in `LeaveBalanceResponse.from()`
  (`entitledDays.subtract(usedDays)`) and never stored -- both operands are
  `NUMERIC(5,1)`/scale-1 `BigDecimal`, so the result's scale is exact with
  no rounding or double conversion anywhere in the path.
- **Leave balances still require manual/test-fixture provisioning.**
  Balance management (create/update) remains entirely out of Phase 7 scope
  (v1 rule, unchanged) -- there is no API to create a `leave_balances` row.
  Tests insert whatever balance rows they need directly via `JdbcTemplate`
  (`LeaveTestFixtures.insertLeaveBalance`), matching the "explicitly create
  what you depend on, never rely on incidental data" rule.
- No `LeaveRequest` entity, creation, listing, approval, rejection,
  overlap checking, or concurrency handling existed yet at the end of 7A --
  all of that is now Phase 7B-1's scope (below) except approval/rejection,
  which remains Phase 7B-2. No frontend pages were added; the existing
  `/admin/leave` and `/employee/leave` placeholder routes are untouched.
- Backend: 15 new tests (116 total with Phases 1-6) -- 4 pure unit tests
  for `WorkingDayCalculator` (Mon-Fri = 5, single weekday = 1, weekend
  excluded inside a longer range, Sat-Sun = 0), 2 for `GET /api/leave-types`
  (401 unauthenticated, alphabetical listing verified for both seeded
  types), 5 for the HR_ADMIN balance endpoint (401, 403 for EMPLOYEE, 404
  for an unknown employee, correct `remainingDays` computation, empty list
  for an employee with no balance rows), and 4 for the EMPLOYEE self-service
  balance endpoint (401, 403 for HR_ADMIN -- consistent with existing
  project policy of strict `@PreAuthorize` role separation, own-balance
  read, and confirmed data isolation from another employee's balance).
  `./mvnw.cmd clean verify` passed (116/116, 0 failures, 0 errors) at the
  end of 7A.

### Phase 7A correction (applied at the start of Phase 7B-1)
- `LeaveBalanceRepository`'s query method was renamed from
  `findByEmployeeIdOrderByYearDesc` to
  `findByEmployeeIdOrderByYearDescLeaveType_NameAsc`, and `LeaveService`'s
  call site updated to match, so balances now sort year descending, then
  leave-type name ascending *within* a year (not just by year). One new
  integration test (`LeaveBalanceControllerTest.
  balancesAreOrderedByYearDescendingThenLeaveTypeNameAscendingWithinTheYear`)
  seeds Sick Leave 2025, Sick Leave 2026, and Annual Leave 2026 out of
  order and asserts the response comes back as 2026 Annual, 2026 Sick, 2025
  Sick. No schema change -- this is a query/entity-relationship change
  only.

### Phase 7B-1 — Leave request submission and read/list operations
Status: **Complete.** No schema migration was needed or created -- V6's
`leave_requests` table (including its pre-provisioned `version` column)
already had every column this phase needs.

- New `LeaveRequest` entity mapping V6 exactly: `Employee`/`LeaveType` as
  lazy `@ManyToOne`, `daysRequested` as `BigDecimal`, `status` as
  `LeaveRequestStatus` (`EnumType.STRING`), `reviewedByUserId` as a plain
  nullable `Long` (mirrors `AuditLog.actorId` -- no JPA relationship, since
  nothing needs to navigate to the reviewer's other fields),
  `reviewedAt`/`rejectionReason` nullable, `version` mapped with
  `@Version` onto the existing column, `createdAt`/`updatedAt` mapped the
  same way as every other entity. No setters exist yet for the
  review-related fields -- Phase 7B-2 adds those along with the behavior
  that writes them.
- `CreateLeaveRequestRequest` (`leaveTypeId`, `startDate`, `endDate`,
  optional `reason`, all bean-validated) and `LeaveRequestResponse` (the
  full approved field list, including denormalized `employeeCode`/
  `employeeFullName`/`leaveTypeName`) -- both records, entities never
  returned directly.
- `LeaveRequestRepository`: `findWithDetailsById` and the two paginated
  queries (`findByEmployeeId`, admin `search` with optional
  `status`/`employeeId` filters) all `JOIN FETCH` `employee`+`leaveType` so
  building a `LeaveRequestResponse` never triggers extra queries; `search`
  uses the same fetch-join-query-plus-explicit-`countQuery` shape as
  `EmployeeRepository.search`/`SalaryStructureRepository`. A separate
  `countOverlapping` query checks the interval directly in PostgreSQL
  (`existing.startDate <= :endDate AND existing.endDate >= :startDate`,
  `status IN (PENDING, APPROVED)`, no `leaveTypeId` filter -- overlap
  applies across all leave types) and returns a count rather than loading
  any rows, so overlap checking never pulls an employee's full history.
- `LeaveRequestService.create()`: reuses `EmployeeRepository.
  findByIdForUpdate` (the same lock method Phase 6 added) to hold the
  employee row lock for the whole method, then validates leave-type
  existence, reversed dates, cross-year dates, and zero working days
  (`WorkingDayCalculator.countInclusive(...).signum() == 0`), then runs
  `countOverlapping` inside that same lock/transaction, then saves the
  request as `PENDING` and records `LEAVE_REQUEST_CREATED` (`employeeId`,
  `leaveTypeId`, `startDate`, `endDate`, `daysRequested` -- the free-text
  `reason` is deliberately never audited) before returning `201`. No
  past-date restriction was added -- not an approved rule.
- Three narrowly-named exceptions (matching `SalaryStructureOverlapException`'s
  precedent) plus one `LeaveExceptionHandler`:
  `InvalidLeaveDatesException` -> `400 INVALID_LEAVE_DATES` (reversed or
  cross-year dates), `ZeroWorkingDaysRequestedException` ->
  `400 ZERO_WORKING_DAYS_REQUESTED`, `LeaveRequestOverlapException` ->
  `409 LEAVE_REQUEST_OVERLAP`. An unknown `leaveTypeId` uses the existing
  plain `ResponseStatusException(NOT_FOUND, ...)` convention, not a new
  exception type. The `ObjectOptimisticLockingFailureException` ->
  `409 LEAVE_REQUEST_ALREADY_PROCESSED` mapping is **not** in
  `LeaveExceptionHandler` yet -- there is nothing to trigger it until
  Phase 7B-2 adds approve/reject.
- Endpoints: `POST /api/leave-requests` and `GET /api/leave-requests`
  (HR_ADMIN, paginated, `status`/`employeeId` filters) share one
  `LeaveRequestController` at the same URL (distinguished by HTTP method,
  not by package, since REST convention puts create/list on one collection
  path) with per-method `@PreAuthorize`; `GET /api/leave-requests/{id}`
  lives in the same controller with no `@PreAuthorize` (any authenticated
  role may call it) and the service enforces that an EMPLOYEE only ever
  sees their own request, returning `404` (not `403`) for someone else's,
  so its existence is never disclosed. `GET /api/leave-requests/me`
  (EMPLOYEE, paginated own history) is a separate controller/path, matching
  every other self-service endpoint's convention. Default sort on both
  paginated endpoints is `createdAt DESC` with an `id DESC` tiebreaker
  (mirrors `EmployeeService.withIdTiebreaker`, descending instead of
  ascending).
- Backend: 25 new tests (141 total with Phases 1-6 + 7A) -- 1 added to
  `LeaveBalanceControllerTest` for the ordering correction above, 21 in
  `LeaveRequestControllerTest` (401/403 authorization, successful creation
  with server-computed `daysRequested`, `employeeId` un-spoofable from the
  request body, reversed/cross-year dates, weekend-only dates, unknown
  leave type, overlap against `PENDING` and against a directly-inserted
  `APPROVED` row, a `REJECTED` row not blocking, adjacent non-overlapping
  dates succeeding, `PENDING` status with null review fields verified
  against the database, an audit row proven free of the submitted reason
  text, admin listing with `status`/`employeeId` filters, the
  `createdAt DESC`/`id DESC` tiebreaker proven deterministic with two rows
  sharing an identical `created_at`, HR_ADMIN/EMPLOYEE-own/EMPLOYEE-other
  detail-by-id behavior, and an unknown id returning 404), and 3 in
  `LeaveRequestSelfServiceControllerTest` (401, 403 for HR_ADMIN, and
  confirmed isolation from another employee's requests).
  `./mvnw.cmd clean verify` passes (141/141, 0 failures, 0 errors).

### Phase 7B-2 — Approval/rejection, balance deduction, optimistic-lock handling, concurrency verification
Status: **Complete.** No schema migration needed or created.

- `LeaveRequest` gained two narrowly-scoped domain methods, `approve(reviewerUserId, reviewedAt)` and
  `reject(reviewerUserId, reviewedAt, rejectionReason)` -- each mutates status plus every review field together
  and defensively rejects a non-PENDING request (`IllegalStateException`, expected to be unreachable since the
  service checks status first). No generic public setter exists for `status` or any review field.
  `LeaveBalance` gained one method, `incrementUsedDays(BigDecimal)` -- no validation of its own, since
  `LeaveRequestService.approve` is required to have already verified sufficiency, under the balance row's lock,
  before ever calling it.
- `LeaveBalanceRepository.findForUpdate(employeeId, leaveTypeId, year)` -- `@Lock(PESSIMISTIC_WRITE)`, explicit
  JPQL, same pattern as `EmployeeRepository.findByIdForUpdate`. The year always comes from
  `leaveRequest.getStartDate().getYear()` (cross-year requests are already rejected at creation, so this is
  unambiguous).
- **Request-first optimistic claim, then balance pessimistic lock.**
  `LeaveRequestService.approve()`: loads the request, checks PENDING (else
  `LeaveRequestAlreadyProcessedException`), calls `leaveRequest.approve(...)`, then immediately
  `leaveRequestRepository.saveAndFlush(leaveRequest)` inside a local try/catch that converts any
  `ObjectOptimisticLockingFailureException` to `LeaveRequestAlreadyProcessedException`. This early flush is
  deliberate: it forces the version-checked `UPDATE` to run right there, so two concurrent approvals of the
  *same* request conflict on version before either can reach the balance check -- the loser gets a clean
  "already processed" instead of a misleading "insufficient balance". Only after that claim succeeds does the
  method call `LeaveBalanceRepository.findForUpdate` to lock the matching balance row, compute
  `remainingDays = entitledDays - usedDays` with exact `BigDecimal` arithmetic, throw
  `LeaveBalanceNotFoundException`/`InsufficientLeaveBalanceException` as needed, otherwise call
  `incrementUsedDays` and record `LEAVE_REQUEST_APPROVED`. `reject()` follows the same claim-then-flush
  pattern but never queries, locks, creates, or touches a balance row.
- **Rollback behavior.** If balance lookup, sufficiency validation, mutation, or audit recording fails after the
  early flush, the whole transaction -- including the already-flushed `APPROVED` status and version bump --
  rolls back at the transaction boundary (Spring's default unchecked-exception rollback; flush is not commit).
  Verified directly: `missingBalanceFailureRollsRequestBackToPending` and
  `insufficientBalanceFailureRollsBackAndLeavesUsedDaysUnchanged` both re-query the database after a failed
  approval and confirm the request is still `PENDING` with `reviewed_by` null, and that `used_days` is
  untouched.
- Three new exceptions plus one safety-net mapping, all in `LeaveExceptionHandler`:
  `LeaveRequestAlreadyProcessedException` -> `409 LEAVE_REQUEST_ALREADY_PROCESSED`,
  `LeaveBalanceNotFoundException` -> `404 LEAVE_BALANCE_NOT_FOUND`,
  `InsufficientLeaveBalanceException` -> `409 INSUFFICIENT_LEAVE_BALANCE`, and
  `ObjectOptimisticLockingFailureException` -> the same `409 LEAVE_REQUEST_ALREADY_PROCESSED` (a safety net for
  a lock conflict that surfaces at final transaction commit rather than at the service's own early flush --
  the two are complementary, not redundant). The safety-net handler deliberately builds a fixed detail message
  rather than reusing the exception's own `getMessage()`, which otherwise includes the entity class name and
  identifier; `LeaveExceptionHandlerTest` asserts the response body contains neither.
- `RejectLeaveRequestRequest` (`reason`, optional, `@Size(max = 500)`) stays a plain validated record; blank/
  whitespace normalization to `null` happens in the service, not the DTO, matching every other request DTO in
  the project. The reject endpoint uses `@RequestBody(required = false)`, so an omitted body, `{}`, or a body
  with `reason` are all accepted.
- Endpoints: `PUT /api/leave-requests/{id}/approve` and `PUT /api/leave-requests/{id}/reject` (both HR_ADMIN
  only, `200` with `LeaveRequestResponse`) added to the existing `LeaveRequestController`.
- Approval audit (`LEAVE_REQUEST_APPROVED`): `leaveRequestId`, `employeeId`, `daysRequested` -- no reason, no
  balance figures. Rejection audit (`LEAVE_REQUEST_REJECTED`): `leaveRequestId`, `employeeId`, `hasReason`
  (boolean) -- the raw rejection-reason text is never recorded, verified directly by a test that searches every
  `audit_logs.details` row for a unique reason token and asserts zero matches.
- **Real PostgreSQL concurrency verification (`LeaveRequestConcurrencyTest`, 3 tests, no mocks/H2/Testcontainers/
  synchronized/sleeps, not `@Transactional` so fixture rows are visible to worker threads' own connections):**
  1. Two threads approve the *same* `PENDING` request concurrently -- exactly one succeeds, the other gets
     `LEAVE_REQUEST_ALREADY_PROCESSED`; the database afterward shows `status = APPROVED`, `version = 1`
     (advanced exactly once), `used_days` increased exactly once, and exactly one `LEAVE_REQUEST_APPROVED`
     audit row.
  2. Two *different* non-overlapping `PENDING` requests for the same employee/leave-type/year, with a balance
     sized to satisfy only one, approved concurrently via the balance row's pessimistic lock -- exactly one
     succeeds, the other gets `INSUFFICIENT_LEAVE_BALANCE` and stays `PENDING` (not rejected); `used_days`
     never exceeds `entitled_days`; exactly one approval audit row exists.
  3. Two overlapping `create()` submissions for the same employee, exercising the existing (Phase 7B-1)
     `EmployeeRepository.findByIdForUpdate` lock -- exactly one succeeds, the other gets
     `LEAVE_REQUEST_OVERLAP`; the database contains exactly one active (`PENDING`/`APPROVED`) request covering
     the window.
- Backend: 30 new tests (171 total with Phases 1-6, 7A, 7B-1) -- 1 unit test simulating the optimistic-lock
  safety-net mapping directly (no Spring context), 26 in `LeaveRequestApprovalTest` (successful
  approval/rejection and their field/balance effects, unrelated-balance isolation, missing/insufficient
  balance and their rollback behavior, every already-processed re-processing combination, blank-reason
  normalization, 404s, 401/403, and audit content/absence checks), and 3 in `LeaveRequestConcurrencyTest`.
  `./mvnw.cmd clean verify` passes (171/171, 0 failures, 0 errors).

### Phase 7C-1 — Frontend leave API foundation and EMPLOYEE leave dashboard
Status: **Complete.** No backend or migration change -- frontend only.

- New `frontend/src/api/leave.ts`: `LeaveType`/`LeaveBalance`/`LeaveRequest`/`LeaveRequestStatus` types matching
  the backend response fields exactly, `CreateLeaveRequestPayload` (only `leaveTypeId`/`startDate`/`endDate`/
  optional `reason` -- structurally cannot carry `employeeId`, `daysRequested`, `status`, review fields, or
  `version`), and a generic `PagedResponse<T>` (the same shape as `EmployeePage`, shared here since this file
  already has two paginated endpoints and a third, HR_ADMIN-facing one, arrives in Phase 7C-2). Implemented:
  `listLeaveTypes()`, `getOwnLeaveBalances()`, `createLeaveRequest(payload)`, `listOwnLeaveRequests(page, size)`.
- New `frontend/src/routes/employee/LeaveDashboardPage.tsx` replaces the `/employee/leave` placeholder
  (`/admin/leave` is untouched, still the Phase 7C-2 placeholder). One responsive page, three sections,
  following the same list-plus-form shape as Phase 6's `SalaryHistoryPage`:
  - **Balance summary**: year, leave type, entitled/used/remaining days, in the backend's own order (year
    descending, then leave-type name ascending within a year -- never re-sorted or combined across years
    client-side). A `formatDays()` helper renders every day value to one decimal place (`toFixed(1)`) so
    `3.5` stays `3.5` and `14` reads as `14.0`, consistently, with no rounding. A one-line note explains that
    balances are set up and maintained by HR; an explicit empty state covers an employee with no balance rows.
  - **Apply for leave**: leave type select, start/end date, optional reason (`maxLength={500}`). Client
    validation (required fields, reversed dates, cross-year dates, and a weekend-only range) runs before any
    API call. The weekend check (`hasNoWeekdays`) and its `parseIsoDateAsUtc` helper deliberately never use
    `new Date(dateString)` -- they parse the `YYYY-MM-DD` parts explicitly and use `Date.UTC`/`getUTCDay`/
    `setUTCDate` throughout, so a browser in a timezone behind UTC can never silently shift a date to the
    previous calendar day. This client check is a UX convenience only; the backend remains authoritative.
    Submission disables the button, shows `extractErrorMessage`-derived errors inline, and on success resets
    the form, shows a confirmation message, resets to page 1, and reloads history -- it never touches balance
    state, so a newly-submitted (`PENDING`) request is never presented as already deducted.
  - **Request history**: leave type, dates, `daysRequested` (via the same `formatDays()`), a color-coded
    status badge (amber/emerald/red for PENDING/APPROVED/REJECTED, same badge shape as `EmployeeDetailPage`'s
    employment-status badge), the submitted reason when present, and the rejection reason (in its own inline
    element, distinct from the "Rejection reason:" label text) only when `status === 'REJECTED'` and one
    exists. Previous/Next pagination reuses `EmployeeListPage`'s exact control shape and disabled-state logic.
  - Leave types, balances, and history load and fail independently (three separate `useEffect`s/error states)
    -- a balance-fetch failure shows its own inline error without hiding an already-loaded history table, and
    vice versa. No HR_ADMIN approve/reject control exists anywhere on this page.
- Frontend: 19 new tests (55 total) -- 16 in `LeaveDashboardPage.test.tsx` (leave types loading, balance
  rendering/ordering/half-day values/empty state, all three status badges, rejection-reason visibility,
  pagination calling the correct page, absence of any approve/reject control, all four client validation
  rules, a valid submission's exact payload shape, form reset, history refresh, submit-button disabling during
  an in-flight request, and backend error display via `extractErrorMessage`), plus 3 new route-protection
  cases in `App.test.tsx` (`/employee/leave`: unauthenticated -> login, HR_ADMIN -> "Not authorized", EMPLOYEE
  -> the page). `npm run test` passes (55/55); `npm run build` succeeds with no type errors.
### Phase 7C-2 — HR_ADMIN leave-request list and review pages
Status: **Complete.** No backend or migration change -- frontend only, extends the Phase 7C-1 `frontend/src/api/leave.ts`
rather than duplicating any leave type.

- `frontend/src/api/leave.ts` gained five HR_ADMIN operations, all reusing the existing `LeaveType`/`LeaveBalance`/
  `LeaveRequest`/`LeaveRequestStatus`/`PagedResponse` types: `listLeaveRequests(params)` (`GET /api/leave-requests`,
  optional `status`/`employeeId`/`page`/`size`), `getLeaveRequest(id)` (`GET /api/leave-requests/{id}` -- any
  authenticated role may call this by id; the backend itself enforces that an EMPLOYEE only ever sees their own
  request), `getEmployeeLeaveBalances(employeeId)` (`GET /api/employees/{employeeId}/leave-balances`),
  `approveLeaveRequest(id)` (`PUT .../approve`, no body), and `rejectLeaveRequest(id, reason?)` (`PUT .../reject`,
  sends `{ reason }` when provided or `{}` otherwise -- the backend's `@RequestBody(required = false)` accepts
  both). `frontend/src/api/errors.ts` gained one matching helper, `extractErrorCode()`, reading the backend
  `ProblemDetail`'s `code` property (e.g. `LEAVE_REQUEST_ALREADY_PROCESSED`) so the detail page can branch on the
  specific failure reason without string-matching the human-readable `detail` text.
- New `frontend/src/routes/admin/LeaveRequestListPage.tsx` replaces the `/admin/leave` placeholder. Follows
  `EmployeeListPage`'s exact shape: a filters row, a table, and Previous/Next pagination.
  - **Status filter** (`Pending`/`All`/`Approved`/`Rejected`, defaulting to `Pending` since those are the requests
    needing HR action) and an **employee filter** populated from the existing `listEmployees()` endpoint (no new
    backend search endpoint was added) -- since employees are paginated with no small "all employees" endpoint
    (unlike departments), the filter fetches one page sized generously enough for a typical org
    (`EMPLOYEE_OPTIONS_PAGE_SIZE = 200`) rather than requiring the admin to know a numeric id. Each option reads
    `"{employeeCode} — {fullName}"`. **Known portfolio-scale limitation:** an org with more than 200 employees
    would need a real employee-search/autocomplete endpoint for this filter to see everyone -- acceptable for
    this project's scope, not something a production HR system with a larger headcount should ship as-is.
  - Table columns: employee code, full name, leave type, start/end date, days requested, a color-coded status
    badge (same amber/emerald/red shape used throughout the project), and a `Review`/`View` link to
    `/admin/leave/{id}`.
  - Changing either filter resets to page 0; changing only the page preserves both active filters (both live in
    the same `useEffect` dependency array feeding one `listLeaveRequests` call). Previous/Next disable at the
    first/last page exactly like every other paginated admin list.
  - Independent loading, inline `extractErrorMessage` error, and empty states -- the empty state reads
    "No pending requests." under the default `Pending` filter and "No leave requests found." otherwise.
- New `frontend/src/routes/admin/LeaveRequestDetailPage.tsx`, routed at `/admin/leave/:id` (HR_ADMIN only,
  `ProtectedRoute` same as every other admin route). Displays the full request record (employee code/name, leave
  type, dates, days requested, status badge, submitted reason, reviewed-by user id, reviewed-at timestamp,
  rejection reason -- each only when present) plus a **balance context** section: after the request loads, it
  fetches `getEmployeeLeaveBalances(employeeId)` and finds the one balance matching both `leaveTypeId` and the
  year derived from `startDate` (`Number(startDate.slice(0, 4))` -- no `Date` parsing needed for a year-only
  comparison). No match renders a clear amber warning that approval will fail until HR provisions one; the
  frontend never attempts to create a balance itself.
  - **Approve/Reject controls** exist only while `status === 'PENDING'`, share one `mode` state
    (`idle`/`approve-confirm`/`reject-form`) so only one is ever visible at a time -- clicking `Approve` reveals
    an inline "Approve this leave request?" confirmation with `Confirm approve`/`Cancel` (the project's first
    confirmation prompt; this inline reveal-then-confirm shape was chosen to match the reject flow's own
    optional-reason-then-confirm shape, rather than introducing `window.confirm` or a new modal library).
    Clicking `Reject` reveals a `maxLength={500}` optional-reason textarea with `Confirm reject`/`Cancel`. Both
    confirm buttons disable while their request is in flight and guard against a second call
    (`if (approving) return` / `if (rejecting) return`) so a disabled-button double-click can never fire twice.
  - On success, the returned `LeaveRequestResponse` fully replaces local request state (never assumed from the
    optimistic UI), the balance context is refetched (approval only -- rejection never touches balances), a
    success message appears, and the controls disappear on their own because they're gated on
    `status === 'PENDING'`, which is no longer true.
  - Approve/reject errors display inline via `extractErrorMessage`, independent of the `mode` reveal state (an
    error must survive a later mode change back to idle). A `LEAVE_REQUEST_ALREADY_PROCESSED` response
    (detected via the new `extractErrorCode`, covering both the immediate business-rule rejection and the
    optimistic-lock safety net) additionally resets `mode` to `idle`, refetches the request via `getLeaveRequest`,
    **and then explicitly refetches that same employee's balances via `getEmployeeLeaveBalances` using the
    refetched request's `employeeId`** -- a plain dependent `useEffect` keyed on `request?.employeeId` would not
    re-run here, since the employeeId is unchanged by this refetch, so the balance refresh could not be left
    implicit. The two refetches fail independently: if the request refetch succeeds but the balance refetch
    fails, the refreshed (now non-`PENDING`) request/status is kept, the balance section shows its own error, and
    the original already-processed message is never cleared or overwritten by the balance failure. Either way
    the screen ends up reflecting whichever admin's decision actually won -- never leaving a stale
    `Approve`/`Reject` button visible on an already-decided request. Because both the initial `Confirm
    approve`/`Confirm reject` click and this recovery refetch run inside the same disabled-button window
    (`approving`/`rejecting` only clears in the handler's `finally`), a repeated click during the whole sequence
    can never trigger a duplicate recovery request.
- Routing: `/admin/leave` -> `LeaveRequestListPage`, `/admin/leave/:id` -> `LeaveRequestDetailPage`, both
  `ProtectedRoute allowedRoles={['HR_ADMIN']}`. The completed `/employee/leave` route is untouched.
- Frontend: 35 new tests (90 total) -- 10 in `LeaveRequestListPage.test.tsx` (default `Pending` filter, row/badge
  rendering, employee options loaded from the existing employee API, status/employeeId filters sent correctly,
  page reset on filter change, pagination preserving active filters, both empty-state messages, API error via
  `extractErrorMessage`, and correct per-row detail links), 19 in `LeaveRequestDetailPage.test.tsx` (full field
  display including conditional review fields, balance-context matching by `leaveTypeId`+year, the
  no-balance-provisioned warning, PENDING-only controls, no controls on APPROVED/REJECTED, the approve
  confirm-then-call flow with a balance refresh, approve-button double-click prevention, opening/cancelling the
  reject form, a reject submission sending only the optional reason, the 500-character reason limit, inline
  review errors, both approve and reject `LEAVE_REQUEST_ALREADY_PROCESSED` paths now asserting the request
  *and* balance context both refresh with the winning admin's data, one test proving a failed post-conflict
  balance refetch still keeps the refreshed request visible and shows its own balance error without erasing the
  conflict message, and loading/error/empty balance states), and 6 new route-protection cases in `App.test.tsx`
  (`/admin/leave` and `/admin/leave/:id`, each: unauthenticated -> login, EMPLOYEE -> "Not authorized", HR_ADMIN
  -> the page). All pre-existing Phase 7C-1 tests continue passing unchanged. `npm run test` passes (90/90);
  `npm run build` succeeds with no type errors.
- **Resolved:** final manual end-to-end testing across both roles against a real running backend was completed
  after Phase 7D (see "Phase 7 final manual verification" at the end of this section).

### Manual-testing correction — authenticated header/navigation
Status: **Complete.** Frontend-only fix found during Phase 7 manual end-to-end testing; does not itself
constitute the manual verification still open above.

- `frontend/src/layout/AppLayout.tsx`'s header previously rendered a fixed `Login`/`Admin`/`Employee` nav
  regardless of auth state, and had no logout control -- so an authenticated EMPLOYEE or HR_ADMIN still saw all
  three static links with no way to sign out or reach their own leave pages from the header. The nav is now
  driven by `useAuth()`: logged out shows only `Login`; EMPLOYEE shows `Employee` (`/employee`) and `My Leave`
  (`/employee/leave`); HR_ADMIN shows `HR Admin` (`/admin`) and `Leave Requests` (`/admin/leave`); either
  authenticated role additionally shows a `Logout` button that calls the existing `AuthContext.logout()` and
  navigates to `/login`.
- Renamed the user-visible brand from "HR Payroll System" to "HappyMon" in both `AppLayout.tsx` and the
  `index.html` `<title>` -- no backend package, database object, folder, API path, or environment-variable name
  was touched.
- Frontend: 6 new tests in `App.test.tsx` (20 pre-existing + 6 new = 26 `App.test.tsx` tests; 90 + 6 = 96 total
  frontend tests -- corrected here from an earlier miscount of 7 new/97 total) covering the logged-out/EMPLOYEE/
  HR_ADMIN header contracts, both header links actually navigating to their target pages, and logout clearing
  the session/token and returning to the
  login page with protected content gone; one pre-existing test's brand-text assertion was updated for the
  rename. All pre-existing tests continue passing. `npm run test` passes (96/96); `npm run build` succeeds with
  no type errors.

### Phase 7D-1 — Migration and backend half-day leave support
Status: **Complete.** Backend only -- no frontend half-day controls or role-based sidebar (Phase 7D-2/7D-3)
implemented yet.

- **`V12__add_leave_request_duration_type.sql`** -- adds `duration_type VARCHAR(10) NOT NULL DEFAULT 'FULL_DAY'`
  to `leave_requests`, plus two CHECK constraints: `chk_leave_requests_duration_type` (`IN ('FULL_DAY',
  'HALF_DAY')`, mirrors the existing `chk_leave_requests_status` pattern) and
  `chk_leave_requests_half_day_single_date` (`duration_type <> 'HALF_DAY' OR start_date = end_date`) as a
  database-level backstop alongside the application-level check. The permanent `DEFAULT` backfills every
  pre-existing row to `FULL_DAY` in the same statement (verified directly: the two real rows that existed before
  this migration -- the Phase 7 demo employee's approved and rejected requests -- both read `duration_type =
  FULL_DAY` afterward with every other column, including `days_requested`/`status`/dates, unchanged) and
  continues to safely cover direct fixture/test inserts and any transitional older client, exactly like the
  existing `version BIGINT NOT NULL DEFAULT 0` column on this same table. V1-V11 were not touched; schema is now
  at V12. No new SQL constraint recalculates `days_requested` -- day-count math remains application-owned, per
  V6's original stated convention.
- New `LeaveDurationType` enum (`FULL_DAY`, `HALF_DAY`, no Morning/Afternoon distinction). `LeaveRequest` maps
  `duration_type` via `@Enumerated(EnumType.STRING)`, threaded through the constructor, immutable after creation
  (no generic setter, same pattern as every other field on this entity).
- `CreateLeaveRequestRequest` gained a trailing, deliberately **nullable** `durationType` field (an omitted
  value normalizes to `FULL_DAY` in the service, never rejected) plus a compatibility four-argument constructor
  matching the pre-Phase-7D signature exactly -- this preserves every existing positional call site (21+ in
  `LeaveRequestControllerTest` alone, plus the concurrency test's direct constructions) with zero edits, and
  keeps the current frontend (which doesn't send `durationType` yet) creating valid `FULL_DAY` requests
  unchanged. `LeaveRequestResponse` gained a matching trailing `durationType` field, populated in `from()`.
- **`LeaveRequestService.create()`** validation order: lock employee row (unchanged) -> resolve leave type
  (unchanged) -> normalize a null `durationType` to `FULL_DAY` -> reversed-date check (unchanged) -> **new**:
  `HALF_DAY` with `startDate != endDate` -> `InvalidLeaveDatesException` (reuses the existing `400
  INVALID_LEAVE_DATES` code, no new error code introduced) -> cross-year check (unchanged; structurally
  unreachable for a valid `HALF_DAY` since a same-date range can't span years) -> the existing
  `WorkingDayCalculator.countInclusive()` zero-check (reused as-is -- for a single-date `HALF_DAY` range this
  doubles as the Monday-Friday check, so a weekend `HALF_DAY` reuses the existing `400
  ZERO_WORKING_DAYS_REQUESTED` code with no new exception type) -> `daysRequested` is `new BigDecimal("0.5")` for
  `HALF_DAY` or the existing computed value for `FULL_DAY` -> the existing `countOverlapping` query (completely
  unchanged -- it compares date ranges only, never duration, so a `PENDING`/`APPROVED` request of either duration
  already blocks any other request of either duration covering that date, with zero code changes required) ->
  save -> audit.
- **Approval and rejection required no logic changes.** `approve()`/`reject()` already operated generically on
  `leaveRequest.getDaysRequested()` (a `BigDecimal`) and `balance.incrementUsedDays(BigDecimal)`, so a `0.5`
  amount flows through the existing optimistic-claim/pessimistic-balance-lock/rollback machinery unmodified.
  Only the audit `details` maps changed: `LEAVE_REQUEST_CREATED`, `LEAVE_REQUEST_APPROVED`, and
  `LEAVE_REQUEST_REJECTED` all now include `durationType`; the raw submitted reason and raw rejection reason
  remain excluded exactly as before (verified by tests searching audit content for unique secret tokens).
- Backend: 25 new tests (196 total, up from 171) -- 13 in `LeaveRequestControllerTest` (half-day creation on a
  weekday, mismatched half-day dates, weekend half-day, omitted-durationType defaulting, explicit `FULL_DAY`
  unchanged, an invalid JSON enum value, a client-supplied `daysRequested` unable to override the server-computed
  `0.5`, the CREATED audit containing `durationType`, and both overlap directions -- half-day blocking full-day,
  full-day blocking half-day, half-day blocking half-day, an `APPROVED` half-day blocking, and a `REJECTED`
  half-day not blocking), 7 in `LeaveRequestApprovalTest` (half-day approval incrementing `used_days` by exactly
  `0.5`, half-day rejection leaving it unchanged, missing-balance and insufficient-balance --0.4 remaining
  against a 0.5 request-- paths and their rollback, re-processing, and both approval/rejection audits containing
  `durationType` with rejection's raw reason still absent), 2 new real-PostgreSQL concurrency tests in
  `LeaveRequestConcurrencyTest` (concurrent `FULL_DAY` vs `HALF_DAY` creation for the same date -- exactly one
  succeeds, the other gets `LEAVE_REQUEST_OVERLAP`; and two concurrent `HALF_DAY` approvals racing a balance with
  exactly `0.5` remaining -- exactly one succeeds, the other gets `INSUFFICIENT_LEAVE_BALANCE`, `used_days` never
  exceeds `entitled_days`, the loser stays `PENDING`, exactly one approval audit row exists -- both using the
  project's existing no-mocks/no-H2/no-Testcontainers/no-`synchronized`/no-sleep latch-and-executor style, shut
  down in `finally`, not `@Transactional`), and 3 in new `LeaveRequestDurationTypeConstraintTest` (a raw-SQL,
  non-JPA real-Postgres check that a pre-V12-style insert omitting `duration_type` reads back as `FULL_DAY`, that
  an unsupported duration value is rejected by the database itself, and that a `HALF_DAY` row with mismatched
  dates is rejected by the database itself -- each via a single atomic `INSERT` so no invalid row is ever left
  behind).
- **Incidental fix required for a fully green suite:** two pre-existing `LeaveRequestControllerTest` tests
  (`hrAdminCanListAllRequestsAndFilterByStatusAndEmployeeId`, `paginationIsSortedByCreatedAtDescThenIdDescDeterministically`)
  assumed the shared real-Postgres database held no `leave_requests` rows outside their own fixtures -- an
  assumption that broke once the Phase 7 demo employee (`P7DEMO01`) accumulated real approved/rejected leave
  history from manual testing. Not caused by this phase's code changes (verified: neither touches `search`,
  sorting, or fixture cleanup). Fixed by scoping both tests to their own `employeeId` (already a supported query
  parameter) rather than relying on the table being otherwise empty -- no production code, migration, or demo
  data was touched to make this fix.
  `./mvnw.cmd clean verify` passes (196/196, 0 failures, 0 errors).
- **Phase 7D-2 (below) implements the frontend half-day controls and duration display.** Phase 7D-3
  (role-based sidebar) remains entirely unimplemented.

### Phase 7D-2 — Frontend half-day leave support
Status: **Complete.** Frontend only -- no backend, migration, or demo-data change. Extends the existing
`frontend/src/api/leave.ts` rather than duplicating any type.

- `frontend/src/api/leave.ts` gained `export type LeaveDurationType = 'FULL_DAY' | 'HALF_DAY'`. `LeaveRequest`
  gained a required `durationType: LeaveDurationType` field (the real response field, not optional -- every
  mocked `LeaveRequest` across the test suite was updated to include it, never hidden behind an unsafe cast).
  `CreateLeaveRequestPayload` also gained a required `durationType: LeaveDurationType` -- the frontend now always
  submits an explicit duration; `daysRequested` is deliberately still absent from the payload type, so it can
  never be sent (the backend alone computes it).
- **`frontend/src/routes/employee/LeaveDashboardPage.tsx`** -- the apply-for-leave form gained a `Leave duration`
  `<select>` (`Full Day` / `Half Day`, id `durationType`), defaulting to `FULL_DAY`, placed directly above
  Start/End date to match the field order a user fills in.
  - Selecting `Half Day` (`handleDurationChange`) immediately copies the current Start Date value into End Date
    (copying an empty string when Start Date is still unset, so both stay empty together) and disables the End
    Date input for the duration of the Half Day selection.
  - While `Half Day` is selected, changing Start Date (`handleStartDateChange`) immediately mirrors the new value
    into End Date as well, so the two dates can never drift apart while the input is disabled.
  - Switching back to `Full Day` re-enables End Date and leaves its current value untouched (normally equal to
    Start Date) -- no hidden "previous end date" state is kept; the employee can edit End Date freely afterward.
  - `validate()` gained one defensive check ahead of the existing reversed-date check --
    `durationType === 'HALF_DAY' && startDate !== endDate` -- as a backstop only, since the mirroring above
    should make this unreachable in normal use. The existing weekend/no-working-days check
    (`hasNoWeekdays(startDate, endDate)`) already covers a single-date `Half Day` range correctly (a Saturday or
    Sunday start/end has no weekdays in it) with zero changes needed. All pre-existing validation (required
    fields, reversed dates, cross-year dates, weekend-only full-day ranges, 500-character reason limit) is
    unchanged.
  - `handleSubmit` now sends `durationType` in the `createLeaveRequest` payload alongside the unchanged
    `leaveTypeId`/`startDate`/`endDate`/`reason` fields -- still never `daysRequested`. On success, `durationType`
    resets to `FULL_DAY` alongside the pre-existing reset of leave type/dates/reason, and history refresh is
    unchanged.
  - **Request history** table gained a `Duration` column (a `DURATION_LABELS` map rendering `Full Day`/`Half Day`
    from `request.durationType` -- never inferred from `daysRequested`), positioned between End date and Days.
    The existing `formatDays()` one-decimal formatter is unchanged, so a half-day row's Days cell still reads
    `0.5`.
- **`frontend/src/routes/admin/LeaveRequestListPage.tsx`** -- table gained a `Duration` column (same
  `DURATION_LABELS` mapping, positioned between End date and Days) showing `Full Day`/`Half Day` per row, with
  `0.5` continuing to render correctly in the existing Days column. The Pending-default filter, status filter,
  employee filter, pagination (including page-reset-on-filter-change and filter-preservation-across-pages),
  loading/error/empty states, and Review/View links are all unchanged. No duration filter was added, per scope.
- **`frontend/src/routes/admin/LeaveRequestDetailPage.tsx`** -- gained a `Duration: Full Day` / `Duration: Half
  Day` line (same `DURATION_LABELS` mapping) directly under the employee name/status header. Balance context,
  approve/reject controls (pending-only gating, confirm-then-call flow, double-click prevention), the
  `LEAVE_REQUEST_ALREADY_PROCESSED` conflict-recovery refetch of both the request and its balances, independent
  request/balance error and loading/empty states are all unchanged.
- Frontend: 13 new tests (109 total, up from 96) -- 10 in `LeaveDashboardPage.test.tsx` (duration defaults to
  Full Day, selecting Half Day mirrors Start Date into End Date, End Date disabled during Half Day, changing
  Start Date during Half Day updates End Date, selecting Half Day before any date keeps both empty, switching
  back to Full Day re-enables End Date and retains its value, a weekend Half Day rejected client-side, a Half Day
  submission sending identical dates/`durationType: 'HALF_DAY'`/no `daysRequested`, duration resetting to Full Day
  after a successful submission, and mixed Full Day/Half Day history rows including a `0.5` Half Day days value),
  1 in `LeaveRequestListPage.test.tsx` (mixed Full Day/Half Day rows with `0.5` displayed correctly, existing
  filters/pagination proven unaffected by the other pre-existing tests in the same file), and 2 in
  `LeaveRequestDetailPage.test.tsx` (`Duration: Full Day` and `Duration: Half Day` display, existing
  approve/reject/balance/conflict-recovery tests in the same file proven unaffected). All pre-existing tests
  continue passing unchanged. `npm run test` passes twice in a row (109/109 both times, confirming no flakiness
  from the async duration-mirroring logic); `npm run build` succeeds with no type errors.
- **Phase 7D-3 (below) implements the role-based responsive sidebar.** Final Phase 7 manual end-to-end
  verification across both roles remains open.

### Phase 7D-3 — Role-based responsive sidebar
Status: **Complete.** Frontend only -- no backend, migration, half-day business logic, leave API contract, or
demo-data change.

- **Header (`frontend/src/layout/AppLayout.tsx`)** now carries only branding and account controls, never
  feature navigation. Logged out: `HappyMon`, `Login`, the API status badge -- no sidebar, no Menu toggle, no
  role label, no Logout. Authenticated (either role): `HappyMon`, a plain-text/non-link role badge (`Employee` or
  `HR Admin`, from a `ROLE_LABELS` map keyed on the existing `AuthContext` `Role` type), `Logout` (unchanged --
  still calls `AuthContext.logout()` and navigates to `/login`), the API status badge, and (below the `md`
  breakpoint only) a `Menu` button. The old horizontal `Employee`/`My Leave`/`HR Admin`/`Leave Requests` header
  links are gone -- those destinations now live only in the sidebar.
- **New `frontend/src/layout/Sidebar.tsx`** -- one navigation structure (a single `<nav id="app-sidebar"
  aria-label="Sidebar navigation">`) serves both desktop and mobile; there is no second, independently
  maintained link list. Renders only when authenticated, driven by the same `Role` the header already reads.
  - **HR_ADMIN:** Dashboard (`/admin`), Manage Departments (`/admin/departments`), Manage Employees
    (`/admin/employees`), Leave Requests (`/admin/leave`).
  - **EMPLOYEE:** Dashboard (`/employee`), My Profile (`/employee/profile`), Salary History (`/employee/salary`),
    My Leave (`/employee/leave`).
  - Deliberately excludes the unfinished placeholder routes (`/admin/payroll`, `/admin/reports`,
    `/employee/attendance`, `/employee/payslips`) and every entity-specific route (employee create/detail/
    edit/salary, leave-request detail) -- those stay reachable only through their parent list pages, exactly as
    scoped.
  - Built on React Router `NavLink`, which sets `aria-current="page"` on the active link automatically -- no
    custom active-state logic was written. `end` is set only on the two `Dashboard` entries (`/admin`,
    `/employee`) so Dashboard doesn't stay highlighted on every nested route; every other item omits `end` by
    design, so `Manage Employees` stays active on `/admin/employees/:id`(`/edit`/`/salary`) and `Leave Requests`
    stays active on `/admin/leave/:id`, using React Router's own prefix-matching -- no bespoke `startsWith` logic
    was added.
- **Desktop (`md` and wider):** the sidebar is a fixed-width (`md:w-56`) column to the left of `<main>` inside a
  `flex md:flex-row` wrapper (`flex-col` below `md`, so a mobile-open sidebar stacks above the page content
  instead of overlapping it). `<main>` takes the remaining width; its inner content keeps the pre-existing
  `mx-auto max-w-5xl` readability constraint, now applied to a wrapper `div` around `<Outlet />` rather than to
  `<main>` itself (so `<main>` itself can flex to fill the remaining row width).
- **Narrow screens (below `md`):** the sidebar starts with Tailwind's `hidden` utility (overridden by `md:flex`
  at the breakpoint) and the header's `Menu` button (`aria-label="Menu"`, `aria-expanded={mobileNavOpen}`,
  `aria-controls="app-sidebar"`) toggles a `mobileNavOpen` boolean that swaps `hidden` for `flex`. Clicking any
  sidebar `NavLink` calls the same `onNavigate` callback that closes the mobile sidebar; logging out also resets
  `mobileNavOpen` to `false` as part of `handleLogout`, so no stale open sidebar can survive past authentication
  ending. No modal, overlay library, portal, or click-outside dependency was added -- purely conditional Tailwind
  classes. **Limitation, stated plainly:** jsdom does not apply real CSS media queries, so
  `AppLayout.test.tsx`'s mobile-toggle tests verify the `aria-expanded`/class-toggle *mechanism* only (button
  state flips, the `hidden` class is added/removed, closing on link-click and on logout both work) -- they
  cannot and do not claim to prove real visual breakpoint rendering; that still requires manual browser
  verification.
- **`frontend/src/routes/admin/AdminDashboardPage.tsx`** -- removed the `Manage departments`/`Manage employees`
  quick-action links now that the sidebar provides both destinations on every admin page, not just this one. The
  heading and overview paragraph are unchanged. No dedicated test file existed for this page, so none needed
  updating.
- Frontend: 19 new tests (128 total, up from 109) -- all 19 in new `frontend/src/layout/AppLayout.test.tsx`
  (logged-out: no sidebar/menu/logout/role-label, Login and API status shown; EMPLOYEE: exactly the four
  expected sidebar items with no HR_ADMIN items, `Employee` role label is not a link, old horizontal header
  links gone, sidebar `My Leave` navigates to `/employee/leave`; HR_ADMIN: exactly the four expected sidebar
  items with no EMPLOYEE items, `HR Admin` role label is not a link, old horizontal header links gone, sidebar
  `Leave Requests` navigates to `/admin/leave`; navigation: every sidebar item's exact href, only Dashboard
  active on the exact dashboard route via `aria-current="page"`, Dashboard not active on a nested route, Manage
  Employees active on an employee detail route, Leave Requests active on a leave detail route; mobile toggle:
  `aria-expanded="false"` initially with `aria-controls` correctly referencing the sidebar element,
  `aria-expanded`/class flip to open on click, closing plus `aria-expanded` reset on sidebar link click; logout:
  auth clears, login reappears, sidebar and Menu toggle both disappear). `frontend/src/App.test.tsx`'s existing
  "Header navigation and branding" tests were updated (not duplicated) to match the relocated links and the new
  non-link role label, using `within(screen.getByRole('banner'))` to scope header-only assertions; all
  pre-existing route-protection tests in that file are unchanged. `npm run test` passes twice in a row (128/128
  both times, no flakiness observed -- the previously-noted `EmployeeCreatePage.test.tsx` timeout did not recur
  in either run); `npm run build` succeeds with no type errors.
- **Resolved:** final Phase 7 manual end-to-end verification across both roles, including the real-browser
  responsive breakpoint behavior noted above, was completed successfully (see below).

### Phase 7 final manual verification
Status: **Complete.**

- **Stale-backend incident and recovery.** During this final round of manual testing, a leave request submitted
  as `HALF_DAY` was observed saving as `daysRequested = 1` (not `0.5`), approving it moved `usedDays` by a full
  `1.0`, and `durationType` was absent from every list/detail API response. Root cause: the JVM process still
  listening on port 8080 had been started before the Phase 7D-1 source changes (`LeaveDurationType`,
  `CreateLeaveRequestRequest.durationType`, `LeaveRequestResponse.durationType`, the `BigDecimal("0.5")`
  half-day calculation, and `V12__add_leave_request_duration_type.sql`) were written to disk, so it was running
  pre-Phase-7D-1 compiled code against a database schema one migration ahead of it. The stale process was
  stopped, and the backend was rebuilt and restarted from the current Phase 7D source; `GET /api/health` returned
  `UP`, Flyway confirmed schema version 12, and both demo accounts logged in successfully against the new
  session-local JWT secret.
- **Targeted cleanup of the two invalid demo requests the stale backend had produced** (`P7DEMO01`, employee_id
  1227): request 581 (2026-09-17, incorrectly `FULL_DAY`/`1.0`/REJECTED) and request 582 (2026-09-25, incorrectly
  `FULL_DAY`/`1.0`/APPROVED) were deleted in one transaction, together with their four associated
  `LEAVE_REQUEST_CREATED`/`LEAVE_REQUEST_APPROVED`/`LEAVE_REQUEST_REJECTED` audit rows (identified strictly by
  `entity_name = 'LEAVE_REQUEST'`, since an unrelated `EMPLOYEE`-entity audit row happened to share the numeric id
  582). Annual Leave 2026 balance (id 84) was corrected from `usedDays 2.0` back to `usedDays 1.0`
  (`remainingDays 13.0`), reversing only request 582's erroneous full-day deduction. Full pre-delete snapshots
  (row data plus ready-to-run restore `INSERT` statements) were saved outside the repository before the delete.
  Requests 157 and 158 (the employee's genuine pre-Phase-7-manual-testing history) were left untouched throughout.
- **Successful retest against the restarted Phase 7D backend.** The employee submitted `HALF_DAY` for
  2026-09-23 first (request id 583), then `HALF_DAY` for 2026-09-22 second (request id 584). Both displayed
  duration "Half Day" and `daysRequested 0.5`. HR_ADMIN approved request 584 (`usedDays` moved from `1.0` to
  `1.5`, `remainingDays` to `12.5`, an exact `+0.5`) and rejected request 583 without supplying a rejection
  reason -- valid per the implemented API contract, where the reason is optional (`rejection_reason` empty,
  audit `hasReason: false`); the rejection made no balance change. A `HALF_DAY` request for a weekend date was
  blocked by client-side working-day validation with no database row created (confirmed: no leave request exists
  for employee 1227 dated 2026-11-07, the specific weekend date first suggested for this check).
- **Reverse-date ordering proof.** Request 584 was submitted *after* request 583 (`created_at` later) despite
  having the *earlier* calendar leave date (2026-09-22 vs. 2026-09-23). Both the live `GET /api/leave-requests/me`
  (employee) and `GET /api/leave-requests` (HR_ADMIN, `employeeId=1227`, all statuses) returned request 584 before
  583, confirming newest-created-first ordering (`createdAt DESC` with `id DESC` as the deterministic tie-breaker,
  per `LeaveRequestService.withIdDescTiebreaker`) rather than any ordering by `startDate`/`endDate`.
- **Audit verification**, scoped strictly to request ids 583/584 (never a global count, since unrelated audit
  rows exist for other entities/requests): exactly one `LEAVE_REQUEST_CREATED` row each for 583 and 584, exactly
  one `LEAVE_REQUEST_REJECTED` row for 583 (`durationType: HALF_DAY`, `hasReason: false`), and exactly one
  `LEAVE_REQUEST_APPROVED` row for 584 (`durationType: HALF_DAY`, `daysRequested: 0.5`). No raw application or
  rejection reason text appears in any audit detail.
- **Sidebar/responsive manual verification.** Both HR_ADMIN and EMPLOYEE desktop sidebars showed exactly their
  correct four role-specific links with no cross-role links present, and active-route highlighting worked
  correctly. On a narrow screen, the Menu toggle opened and closed the sidebar, the sidebar closed automatically
  after navigating, no content overlap or horizontal overflow occurred, and Logout worked correctly.
- Final state verified directly against the database after all of the above: Annual Leave 2026 balance (id 84)
  `entitledDays=14.0`, `usedDays=1.5`, `remainingDays=12.5`; requests 583 (REJECTED) and 584 (APPROVED) both
  `HALF_DAY`/`0.5`; no leave request exists for employee 1227 dated 2026-11-07. `./mvnw.cmd clean verify` passes (196/196, 0
  failures, 0 errors); `npm run test` passes twice in a row (128/128 both times); `npm run build` succeeds with
  no TypeScript errors. All manual-test database records were re-queried after the automated test runs and
  confirmed unchanged.

## Phase 8 — Payroll processing and employee payslips
Status: **In progress.**

### Phase 8A-2 — Payroll schema foundation, entities, calculator, repositories
Status: **Complete.** Backend-only schema/domain foundation -- no orchestration service, no controller/API, no
audit events, no exception mapping, no concurrency handling, no PDF generation, and no frontend change. Manually
triggered monthly runs only; payroll remains salary-based with no attendance/leave/overtime/timesheet dependency.

- **`V13__add_payroll_rate_snapshot.sql`** -- additive only, V1-V12 untouched. Adds `epf_rate_snapshot`,
  `socso_rate_snapshot`, `eis_rate_snapshot`, `income_tax_rate_snapshot` (`NUMERIC(5,4)`, each `NOT NULL DEFAULT`
  the fixed demo rate below) and `version BIGINT NOT NULL DEFAULT 0` to `payroll_runs`. Rate snapshots live on
  `payroll_runs`, not `payslips` -- every payslip in a run shares that run's one rate snapshot via its existing FK,
  so nothing is duplicated per payslip. New CHECK constraints: `chk_payroll_runs_period_year_range`
  (`period_year BETWEEN 2000 AND 2100`), one range check per rate column (`0-1` inclusive), and
  `chk_payroll_runs_rate_snapshot_sum` (the four rates combined `<= 1`). Defaults are kept permanently, matching
  the V10/V12 convention. `payroll_runs` was empty before and after this migration -- nothing to backfill; no
  `triggered_by_user_id` column was added (an existing `audit_logs.actor_id`, once Phase 8B adds audit events,
  remains the authoritative "who triggered this," matching every other domain's convention).
- **Demonstration rates (fixed, flat, explicitly non-compliant):** EPF 11.00%, SOCSO 0.50%, EIS 0.20%, income tax
  5.00% -- educational-only figures, not legally accurate Malaysian statutory calculations.
- **Deductions apply to gross pay** (`basePay + housingAllowance + transportAllowance`), an approved demonstration
  policy. Each deduction is rounded independently (`grossPay.multiply(rate)`, `HALF_UP`, scale 2) immediately after
  its own multiplication; `totalDeductions` is the sum of the four already-rounded deductions, never gross pay
  times one combined rate; `netPay = grossPay - totalDeductions`, defensively rejected if negative.
- **`SalaryPeriodCoverage`** (new, `salary` package) -- a public, pure, Spring/JPA-free utility exposing the
  full-period coverage rule (`effectiveFrom <= periodStart AND (effectiveTo IS NULL OR effectiveTo >= periodEnd)`,
  no proration/combination across multiple structures) that previously lived as a package-private method on
  `SalaryStructureService`. `SalaryStructureService` no longer carries its own copy of this algorithm (the unused,
  untested `covers(SalaryStructure, ...)` overload was removed rather than kept as a dead wrapper).
  `SalaryCoverageAlgorithmTest` was refactored to exercise the utility directly with raw dates, preserving every
  original scenario and adding explicit null-rejection tests.
- New `com.hrpayroll.backend.payroll` package: `PayrollRunStatus` (`PROCESSING`/`CALCULATED`/`COMPLETED`/`FAILED`),
  `PdfStatus` (`PENDING`/`GENERATED`/`FAILED`), `PayrollRateSnapshot` (immutable rate value object, validates each
  rate in `[0,1]` and their sum `<= 1`, constructed from `BigDecimal` string literals only, never `double`),
  `PayrollCalculationResult` (immutable calculation output, all ten fields normalized to scale 2), `PayrollCalculator`
  (pure static calculator, no Spring/JPA/clock/filesystem/database dependency), `PayrollRun` and `Payslip` JPA
  entities mapping V8+V13 exactly (enums stored as strings, `PayrollRun.version` mapped `@Version`, no lifecycle
  mutation methods on either entity -- run orchestration is explicitly Phase 8B's scope, not this one), and
  `PayrollRunRepository`/`PayslipRepository` (month/year lookup, existence check, a `findByIdForUpdate`
  `PESSIMISTIC_WRITE` lock mirroring `EmployeeRepository`'s for the future retry path, and fetch-joined
  paginated/by-id lookups for `Payslip` mirroring `LeaveRequestRepository`'s shape -- no implicit ordering
  hardcoded into any method name).
- **Locked lifecycle semantics documented but not implemented here** (Phase 8B's scope): a run is created
  `PROCESSING`; successful calculation ends at `CALCULATED`; `COMPLETED` is reserved until every payslip's PDF is
  generated (Phase 8C); a calculation failure leaves zero payslips and records `FAILED` through a separate
  committed transaction (never the same rolled-back one); retry reuses the same `FAILED` row, since the period
  unique constraint prevents a replacement row ever being inserted for that period.
- Backend: 46 new/changed tests (242 total, up from 196) -- `SalaryCoverageAlgorithmTest` grew from 6 to 10 (all
  original scenarios preserved, now against `SalaryPeriodCoverage` directly, plus 4 new null-rejection cases), 21
  new in `PayrollCalculatorTest` (the worked example, gross-pay sum, one rounding-boundary test per deduction,
  proof that deductions are summed after independent rounding rather than from one combined rate, exact net-pay
  subtraction, zero-allowance and zero-rate cases, every null/negative/out-of-range/combined-rate rejection, a
  defensive negative-net-pay rejection, and scale-2 normalization of every result field), 13 new in
  `PayrollRunConstraintTest` (raw-SQL: V13 defaults plus `version = 0` on an insert omitting those columns, both
  year-range boundaries, every individual rate's both boundaries, the combined-rate-sum boundary rejected and
  accepted), 5 new in `PayrollRunRepositoryTest`, and 3 new in `PayslipRepositoryTest` (JPA entity/repository
  round-trip mapping and the fetch-joined paginated lookups -- the only layer available to test against with no
  service/controller yet). `./mvnw.cmd clean verify` passes (242/242, 0 failures, 0 errors); Flyway reports schema
  version 13. Frontend was not touched and its test suite was not re-run in this sub-phase.
- **Explicitly unimplemented, deferred to later sub-phases:** payroll-run orchestration service, create/retry/list/
  detail HTTP endpoints and DTOs, audit events, exception-handler/constraint-code mapping, concurrency handling,
  PDF generation (Apache PDFBox), and every frontend page/route/sidebar change.

### Phase 8B-1 — Transactional payroll creation and calculation
Status: **Complete.** Backend service workflow only -- no controller, no HTTP DTOs, no
`ApiExceptionHandler` mapping, no retry operation, no list/detail endpoints, no PDF generation, and
no frontend change. No new migration.

- **Three separately committed transactions**, deliberately split across two Spring beans (never a
  self-invoked method on one bean): a non-transactional `PayrollRunService` facade coordinates the
  workflow and catches calculation failure; `PayrollRunTransactionService` owns all three
  `@Transactional` methods, and **all three are explicit `@Transactional(propagation =
  Propagation.REQUIRES_NEW)`** -- correction applied after review: `createRun`/`calculate` were
  initially left on default `REQUIRED` propagation, which is indistinguishable from `REQUIRES_NEW`
  only as long as the facade itself is never called from inside another transaction. A dedicated
  `PayrollRunTransactionIsolationTest` (calling the real facade from inside an outer
  `TransactionTemplate` transaction that is deliberately rolled back afterward) proved this: under
  `REQUIRED`, both the successful and the failed run vanished entirely once the outer transaction
  rolled back, since they had silently joined it instead of committing independently. All three
  methods are now `REQUIRES_NEW` unconditionally, so each transaction's outcome is entirely its own
  regardless of what transaction context the caller happens to be in. Transaction 1 (`createRun`)
  validates the period, inserts one `PROCESSING` row with the default rate snapshot, flushes
  immediately (so `uq_payroll_runs_period` is evaluated before returning), and records
  `PAYROLL_RUN_STARTED` only after the insert survives -- a losing duplicate-period request never
  reaches this audit call. Transaction 2 (`calculate`) locks the run and every candidate, resolves
  coverage, calculates and persists every payslip together, marks the run `CALCULATED`, and records
  `PAYROLL_RUN_CALCULATED`; any failure inside it rolls the whole transaction back (zero payslips, no
  status change, no audit survive). Transaction 3 (`markFailed`) runs only after the facade catches
  Transaction 2's failure, independently locks the run, marks it `FAILED` with a sanitized reason,
  and records `PAYROLL_RUN_FAILED` -- it commits regardless of Transaction 2 having already rolled
  back. If Transaction 3 itself throws, the original failure is still thrown to the caller (with the
  `markFailed` exception attached via `addSuppressed`, never silently hidden).
- **Candidate contract**: `employmentStatus == ACTIVE AND hireDate <= periodStart` -- excludes
  inactive employees, mid-month hires, and post-month hires, with no proration. New
  `EmployeeRepository.findPayrollCandidatesForUpdate` loads and pessimistically locks
  (`PESSIMISTIC_WRITE`, mirroring `findByIdForUpdate`) every candidate in the required
  `employeeCode ASC, id ASC` order, without filtering on salary-structure existence -- candidate
  selection and coverage validation are separate rules, exactly as scoped. Locking the same
  employee row that `SalaryStructureService.create()` locks protects a payroll calculation from a
  concurrent salary-structure change to a candidate mid-run.
- **Coverage contract**: new `SalaryStructureRepository.findCoveringStructures` returns every
  structure individually satisfying `SalaryPeriodCoverage`'s full-period rule for one employee, as
  a collection (never a single "best match"), so the service can distinguish zero (`FAILED` with
  `SALARY_COVERAGE_MISSING`), exactly one (calculate), or more than one (`FAILED` with
  `SALARY_COVERAGE_AMBIGUOUS`, reachable only by bypassing `SalaryStructureService`'s own overlap
  check via direct SQL, since overlap is not DB-enforced -- proven with exactly that raw-SQL
  fixture in the test suite). Zero eligible candidates at all fails with `NO_ELIGIBLE_EMPLOYEES`,
  before ever attempting coverage resolution.
- New `PayrollFailureCode` (`NO_ELIGIBLE_EMPLOYEES`, `SALARY_COVERAGE_MISSING`,
  `SALARY_COVERAGE_AMBIGUOUS`, `PAYROLL_CALCULATION_FAILED`), `InvalidPayrollPeriodException`,
  `PayrollRunPeriodAlreadyExistsException` (mapped from the `uq_payroll_runs_period` constraint via
  the same Hibernate `ConstraintViolationException` cause-chain walk `ApiExceptionHandler` already
  uses, not a new PostgreSQL-driver dependency), and `PayrollRunFailedException` (carries the run
  id, the stable failure code, and a sanitized business-level message -- never a stack trace, raw
  SQL, or a monetary value). New pure `PayrollPeriod` value object (Spring/JPA-free month/year
  validation plus first/last calendar date derivation, correctly handling the 2000/2100 leap-year
  boundary) validates the period *before* any `payroll_runs` row is ever inserted.
- `PayrollRun` gained exactly two lifecycle methods, `markCalculated()` and `markFailed(String)`,
  both enforcing `PROCESSING` as the only valid starting state (same defensive-invariant pattern as
  `LeaveRequest.approve`/`reject`) -- no generic status setter, no retry/reset method, and no
  `COMPLETED` transition exists at all (enforced by absence; reserved for Phase 8C after PDF
  generation).
- Audit events: `PAYROLL_RUN_STARTED` (`payrollRunId`, `periodMonth`, `periodYear`),
  `PAYROLL_RUN_CALCULATED` (adds `payslipCount`), `PAYROLL_RUN_FAILED` (adds the stable
  `failureCode` only, never the raw `failure_reason` text). `audit_logs.actor_id` remains the sole
  "who triggered this" record; no `triggered_by_user_id` column was added.
- **Duplicate-period concurrency**: two simultaneous `createAndCalculate` calls for the same
  `(month, year)` -- exactly one succeeds, the other gets `PayrollRunPeriodAlreadyExistsException`
  from the `uq_payroll_runs_period` constraint (the sole correctness mechanism, never a racy
  pre-check); exactly one `payroll_runs` row, one payslip set, and one `PAYROLL_RUN_STARTED` audit
  exist afterward. Proven with a real-PostgreSQL concurrency test in the project's established style
  (no mocks/H2/Testcontainers/`synchronized`/sleeps, `CountDownLatch` + `ExecutorService`,
  not class-level `@Transactional`).
- **Shared-database test isolation**: read-only verified beforehand that the only employees in the
  shared database (`P7DEMO01`, `EMP001`) are hired in 2025/2026, so a reserved 2000-2005
  `period_year` band for these tests can never accidentally include an unrelated real employee as a
  *candidate*. That band is a collision-avoidance choice for candidate selection only, **not** the
  cleanup mechanism -- correction applied after review: cleanup originally deleted every
  `payroll_runs`/`audit_logs` row whose `period_year` fell in that band (or equaled 2099), which
  would have destroyed a genuine, unrelated business payroll run for any of those ordinary calendar
  years. `PayrollTestFixtures.cleanup` now identifies test-owned runs only by marker or explicit id:
  a `PAYROLL_RUN_STARTED` audit whose actor is the fixture admin user (every test going through the
  real `PayrollRunService` satisfies this), or an id explicitly passed in by a test that inserts a
  `payroll_runs` row directly without going through the audited service (the three affected Phase
  8A-2 schema/repository-mapping tests were updated to track and pass their own inserted ids). A
  dedicated `PayrollTestFixturesCleanupScopeTest` inserts an unrelated sentinel run inside the
  reserved band with no fixture-admin audit and no fixture employee, proves the fixture-owned rows
  around it are still removed, and proves the sentinel survives -- removing it explicitly in its own
  `@AfterEach` regardless of outcome. `PayrollTestFixtures` also gained a flexible employee-insert
  overload (explicit hire date/status), a raw-SQL `insertSalaryStructure` (needed to construct the
  ambiguous-coverage case, which the real service would reject), an admin-user fixture, and
  FK-respecting deletion order (audit rows ->
  payslips -> payroll runs -> salary structures -> users/employees/departments) that never touches
  a demo/manual record.
- Backend: 33 new tests (275 total, up from 242) -- 9 in `PayrollPeriodTest` (date derivation
  including both leap-year boundaries, every invalid/null month and year), 6 in
  `PayrollRunLifecycleTest` (both transitions, both only from `PROCESSING`, no way to reach
  `COMPLETED`), 14 in `PayrollRunServiceTest` (candidate eligibility rules, deterministic ordering,
  open-ended and exact-boundary coverage, exact `PayrollCalculator`-matching monetary results,
  historical snapshot accuracy, `PENDING`/null PDF fields, `CALCULATED` status with an advanced
  version, exactly-one `STARTED`/`CALCULATED` audits proven free of salary/rate values, the three
  distinct all-or-nothing failure paths -- zero candidates, missing coverage, ambiguous coverage --
  each proving zero payslips survive including for an earlier otherwise-valid candidate, a genuine
  `NUMERIC(12,2)`-overflow persistence failure proving an already-processed earlier candidate's
  payslip is rolled back too, and single-threaded duplicate-period rejection), 1 in
  `PayrollRunConcurrencyTest`, 2 in `PayrollRunTransactionIsolationTest` (the outer-transaction-
  rollback proof for both the successful and the failed path, described above), and 1 in
  `PayrollTestFixturesCleanupScopeTest` (the cleanup-scope sentinel proof, also described above).
  `./mvnw.cmd clean verify` passes (275/275, 0 failures, 0 errors); Flyway remains at schema version
  13 (no migration in this sub-phase). Demo/manual records
  (`P7DEMO01`, `EMP001`, existing leave/salary data) confirmed unchanged; `payroll_runs` confirmed
  empty again after the full suite run (all test fixtures cleaned up correctly). Frontend was not
  touched and its test suite/build were not re-run.
- **Explicitly unimplemented, deferred to Phase 8B-2 and later:** controllers, HTTP DTOs,
  `ApiExceptionHandler` constraint-code mapping, the retry operation, payroll-run list/detail
  endpoints, payslip list/detail/self-service endpoints, PDF generation (Apache PDFBox), the
  `COMPLETED` transition, and every frontend page/route/sidebar change.

### Phase 8B-2A — HR Admin payroll creation and read APIs
Status: **Complete.** HR_ADMIN-only HTTP surface over the existing Phase 8B-1 service -- no retry
endpoint, no employee/self-service payslip access, no PDF download, no `COMPLETED` transition, no
frontend change, and no new migration.

- **Endpoints**, all `@PreAuthorize("hasRole('HR_ADMIN')")`:
  - `POST /api/payroll-runs` -- body is `CreatePayrollRunRequest(month, year)` only (Bean
    Validation: both `@NotNull`, month `1-12`, year `2000-2100`); calls the existing
    `PayrollRunService.createAndCalculate(...)` synchronously and returns `201` with the final
    `CALCULATED` (or throws) state -- never a fake intermediate `PROCESSING` response. The actor id
    comes only from `AuthenticatedUser.userId()`; the request body cannot supply or override an
    actor, rate, status, period date, or any monetary/payslip value -- verified directly by a test
    that sends exactly those extra fields in the JSON body and confirms they have no effect.
  - `GET /api/payroll-runs` -- optional `status`/`year` query filters, paginated, default
    `createdAt DESC` with an `id DESC` tiebreaker always appended (mirrors
    `LeaveRequestService.withIdDescTiebreaker`) that filters can never remove; an invalid `status`
    or an out-of-range `year` (checked against the same 2000-2100 bound) returns `400`.
  - `GET /api/payroll-runs/{id}` -- `404 PAYROLL_RUN_NOT_FOUND` for an unknown id.
  - `GET /api/payroll-runs/{id}/payslips` -- verifies the run exists first (`404
    PAYROLL_RUN_NOT_FOUND`, never a silently-empty page for an unknown run id), paginated, default
    `employeeCodeSnapshot ASC` with an `id ASC` tiebreaker.
  - `GET /api/payslips/{id}` -- HR_ADMIN only in this sub-phase (`404 PAYSLIP_NOT_FOUND` for an
    unknown id); employee-ownership rules are explicitly deferred to Phase 8B-2B, and this endpoint
    does not yet grant EMPLOYEE access at all.
- **DTOs** (entities are never serialized directly): `PayrollRunResponse` includes every
  `PayrollRun` field including the four rate snapshots (included because they explain the
  historical calculation policy for that run -- still fixed, flat, demonstration-only figures, not
  real statutory rates) but never audit-actor information. `PayslipResponse` includes every
  `Payslip` field except `pdfPath` (no download link exists yet) -- built entirely from the
  entity's own stored snapshot/monetary columns, never by re-reading current Employee/Department/
  SalaryStructure state.
- New read-only `PayrollQueryService` (`@Transactional(readOnly = true)`), kept separate from
  `PayrollRunTransactionService`'s write role. `PayrollRunRepository` gained one `search`
  method (optional status/year filter, explicit `countQuery`, same shape as
  `LeaveRequestRepository.search`) -- no change was needed to `PayslipRepository`, since its
  existing `findByPayrollRunId`/`findWithDetailsById` (from Phase 8A-2) already fetch-join
  `payrollRun`+`employee` and accept an arbitrary `Pageable` sort.
- New `PayrollExceptionHandler` (`@RestControllerAdvice`, mirrors `LeaveExceptionHandler`'s flat
  `ProblemDetail` style): `INVALID_PAYROLL_PERIOD` (400), `PAYROLL_RUN_PERIOD_ALREADY_EXISTS` (409,
  with `periodMonth`/`periodYear` properties), `PAYROLL_RUN_FAILED` (422, with `payrollRunId` and
  the stable `failureCode`, detail text equal to the service's own already-sanitized message),
  `PAYROLL_RUN_NOT_FOUND` (404), `PAYSLIP_NOT_FOUND` (404). **Deliberately did not** add
  `uq_payroll_runs_period` to `ApiExceptionHandler`'s generic constraint-code map: since
  `PayrollRunTransactionService.createRun` already catches and translates that exact violation
  before it ever leaves the service layer, a second handler for the same constraint would be
  unreachable dead code, not genuine defence-in-depth, and would risk two divergent response shapes
  for one conceptual error. `PayrollRunNotFoundException`/`PayslipNotFoundException` are new, plain,
  message-only exceptions matching the existing `LeaveBalanceNotFoundException` shape.
- Every error response verified free of stack traces, SQL text, exception class names, and (for the
  422 calculation-failure case specifically) any salary/deduction/rate value -- the sanitized
  message and stable code are the only content.
- Backend: 33 new tests (308 total, up from 275) -- 29 in `PayrollRunControllerTest` (valid
  creation, request-body spoofing rejected, every month/year boundary and null-field 400, duplicate
  period 409, calculation-failure 422 with run id/code and a verified committed `FAILED` run with
  zero payslips, 401/403 on every endpoint, newest-created-first listing, the `createdAt`-tie `id
  DESC` tiebreaker, status/year filters individually and combined, filter preservation across
  pages, invalid status/year query values, full run-detail DTO including rate snapshots and a
  failure reason, unknown-run 404, run-scoped payslip listing with deterministic ordering and
  cross-run isolation, and unknown-run-for-payslips 404 rather than an empty page) and 4 in
  `PayslipControllerTest` (exact snapshot/monetary detail with no `pdfPath`, unknown-id 404,
  401/403). `./mvnw.cmd clean verify` passes (308/308, 0 failures, 0 errors); Flyway remains at
  schema version 13 (no migration in this sub-phase); demo/manual records (`P7DEMO01`, `EMP001`,
  existing leave/salary data) confirmed unchanged; no `payroll_runs`/`payslips`/`Phase8*` fixture
  rows leaked. Frontend was not touched and its test suite/build were not re-run.
- **Explicitly unimplemented, deferred to Phase 8B-2B and later:** the retry endpoint, employee
  self-service payslip access/ownership rules, PDF generation and download (Apache PDFBox), the
  `COMPLETED` transition, and every frontend page/route/sidebar change.

### Phase 8B-2B — Failed payroll-run retry and employee self-service payslip APIs
Status: **Complete.** Backend-only. No PDFBox/PDF endpoint, no `COMPLETED` transition, no frontend
change, no scheduler, and no new migration.

- **`POST /api/payroll-runs/{id}/retry`** (HR_ADMIN only, no request body): retries a `FAILED` run
  in place. `PayrollRun` gained exactly one new lifecycle method, `prepareForRetry()`
  (`FAILED -> PROCESSING`, clearing the previous `failureReason`, same defensive-invariant pattern
  as `markCalculated`/`markFailed`) -- still no generic status setter and no way to reach
  `COMPLETED`. A new `prepareRetry` transaction on `PayrollRunTransactionService` is explicit
  `@Transactional(propagation = REQUIRES_NEW)`, locks the run via the existing `findByIdForUpdate`,
  re-checks `FAILED` under that lock, transitions it, and records `PAYROLL_RUN_RETRIED`
  (`payrollRunId`/`periodMonth`/`periodYear` only) -- all before committing independently of the
  caller. `PayrollRunService.retryAndCalculate(...)` is a non-transactional facade exactly like
  `createAndCalculate`: `prepareRetry` then the *same* existing `calculate`/`markFailed`
  transactions and the same suppressed-exception failure handling -- refactored the shared
  calculate-then-record-outcome logic into one private `calculateOrMarkFailed` helper so the
  payroll algorithm and its failure handling are never duplicated between create and retry. The
  same row is always reused (a replacement is impossible: `uq_payroll_runs_period` would reject
  it). Unknown id -> `404 PAYROLL_RUN_NOT_FOUND`; a non-`FAILED` run -> new
  `PayrollRunNotFailedException` -> `409 PAYROLL_RUN_NOT_FAILED`.
- **Retry concurrency**: two simultaneous retries of the same `FAILED` run -- exactly one succeeds
  (`CALCULATED`), the other gets `PayrollRunNotFailedException`; still exactly one `payroll_runs`
  row, one payslip set, and one `PAYROLL_RUN_RETRIED` audit. The `findByIdForUpdate`
  pessimistic-lock-plus-status-recheck inside `prepareRetry` is the sole correctness mechanism (no
  new constraint needed), proven with a real-PostgreSQL concurrency test in the established style.
  A dedicated transaction-isolation test also proved retry's three transactions survive an outer
  caller transaction that is later rolled back, exactly like create's.
- **Employee self-service payslips**: new `GET /api/employees/me/payslips`
  (`PayslipSelfServiceController`, EMPLOYEE-only, mirrors
  `SalaryStructureSelfServiceController`'s no-path-variable pattern -- identity comes only from
  `AuthenticatedUser.employeeId()`, so there is nothing for a client to spoof), paginated via the
  existing `PayslipRepository.findByEmployeeId` (no new repository method needed), default
  `createdAt DESC` with the established `id DESC` tiebreaker.
- **`GET /api/payslips/{id}` ownership extended**: HR_ADMIN may still read any payslip; an EMPLOYEE
  may now read their own. A non-owning EMPLOYEE receives the exact same `404 PAYSLIP_NOT_FOUND` as
  an unknown id -- never `403`, which would reveal the record's existence (mirrors
  `LeaveRequestService.getById`'s exact "404, not 403" ownership pattern). The 8B-2A placeholder
  blanket-403-for-EMPLOYEE behavior on this endpoint is intentionally superseded here, exactly as
  8B-2A's own documentation flagged it would be; every other 8B-2A endpoint/error/DTO contract is
  unchanged.
- Error handling: new `PayrollRunNotFailedException` mapped in the existing
  `PayrollExceptionHandler` (same flat `ProblemDetail` style, `409 PAYROLL_RUN_NOT_FAILED`); no
  stack trace, SQL, exception class name, or monetary/rate value in any response, verified directly.
- Backend: 25 new tests (333 total, up from 308) -- 5 in `PayrollRunLifecycleTest`
  (`prepareForRetry`'s transition, reason-clearing, rejection from `PROCESSING`/`CALCULATED`, and a
  reflection-based proof that no method on the entity mentions "complete[d]"), 5 in
  `PayrollRunServiceTest` (successful retry reusing the same id/period/rate snapshot with exactly
  one new payslip set and the `STARTED/FAILED/RETRIED/CALCULATED` audit sequence, audit content
  free of a planted secret base-pay value, failed retry preserving the stable failure code with
  zero payslips, retrying a non-`FAILED` run, retrying an unknown id), 1 in
  `PayrollRunTransactionIsolationTest` (retry's outer-rollback survival), 1 in
  `PayrollRunConcurrencyTest` (the double-retry race), 5 in `PayrollRunControllerTest` (retry
  success/404/409/401/403), 3 new + 1 replaced in `PayslipControllerTest` (owning employee
  succeeds, a different employee gets the same 404 as unknown, HR_ADMIN unaffected -- replacing the
  now-superseded blanket-403 test), and 6 in the new `PayslipSelfServiceControllerTest` (own
  payslips only, newest-first with the `id DESC` tiebreaker, pagination, no `pdfPath`, 401, and
  `403` for HR_ADMIN). `./mvnw.cmd clean verify` passes (333/333, 0 failures, 0 errors); Flyway
  remains at schema version 13 (no migration in this sub-phase); demo/manual records (`P7DEMO01`,
  `EMP001`, existing leave/salary data) confirmed unchanged; no `payroll_runs`/`payslips`/`Phase8*`
  fixture rows leaked (marker/ID-scoped cleanup from Phase 8B-1 preserved and reused unchanged,
  including the cleanup-sentinel test). Frontend was not touched and its test suite/build were not
  re-run.
- **Incidental test-design note**: the first concurrency-test attempt incorrectly assumed a
  "no eligible employees" starting failure, overlooking that `PayrollRunConcurrencyTest`'s own
  `setUp()` fixture employee has an *open-ended* salary structure covering every period -- fixed by
  using a genuine `SALARY_COVERAGE_MISSING` failure (a second candidate deliberately missing
  coverage) instead; no production code was affected by this test-only correction.
- **Explicitly unimplemented, deferred to Phase 8C and later:** PDF generation and download (Apache
  PDFBox), `pdfPath` exposure, the `COMPLETED` transition, and every frontend page/route/sidebar
  change.

### Phase 8C-2A — Payslip PDF foundation
Status: **Complete.** Backend foundation only -- no orchestration service, no
`POST /api/payroll-runs/{id}/generate-pdfs` or `GET /api/payslips/{id}/pdf` endpoint, no HTTP error
mappings, no PDF-generation audit events, no generation concurrency, no `markCompleted()` caller,
and no frontend change. No new migration.

- **Apache PDFBox `3.0.8`** (Apache License 2.0) added to `backend/pom.xml` -- confirmed the
  current stable `3.0.x` release via the official `pdfbox.apache.org` download page and Maven
  Central before adding it. Only the single `pdfbox` artifact is declared; `fontbox` and
  `pdfbox-io` resolve transitively at the same version (verified via `dependency:tree`) -- no
  `pdfbox-app`/`pdfbox-tools`, no iText/OpenPDF, no font, templating, or reporting library was
  added.
- **Storage configuration**: `app.payroll.pdf-storage-dir` (`PAYROLL_PDF_STORAGE_DIR` env
  override, dev default `./data/payroll-pdfs`) added to `application.yml` and `.env.example`,
  following the existing `app.*`/`${ENV:default}` convention exactly. That relative default
  resolves differently depending on whether the backend starts from the repository root or from
  `backend/`, so `.gitignore` narrowly ignores both actual locations
  (`/data/payroll-pdfs/`, `/backend/data/payroll-pdfs/`) rather than the whole of `backend/data/`,
  which would have swept in an unrelated future backend data directory. Generated files are never
  written under `src/main/resources`.
- **New `com.hrpayroll.backend.payroll.pdf` package:**
  - `CurrencyFormatter` -- pure, Spring-free `BigDecimal` -> `String` formatter (e.g.
    `"RM 1,234.56"`, `"-RM 1,234.56"`). Never converts through `double`/`float`; normalizes via
    `setScale(2, HALF_UP)` and builds thousands separators directly from `toPlainString()`.
  - `PayslipPdfData` -- immutable record carrying exactly the fields a payslip PDF may render,
    deliberately separate from the `Payslip`/`PayrollRun` JPA entities so the generator never
    risks touching a lazy association and stays fully Spring/JPA-free.
  - `PayslipPdfGenerator` -- pure PDFBox 3.x renderer (`Loader`/`PDDocument`/`PDType1Font` with
    `Standard14Fonts.FontName`), single-page, compact layout: heading, payroll period, employee
    code/name/department/job-title snapshot, earnings section, deductions section, emphasized net
    pay, and the demonstration disclaimer. Uses a plain ASCII hyphen everywhere a dash is needed
    in fixed labels/disclaimer text (not an em dash) since Standard 14 fonts use WinAnsiEncoding.
    An unencodable character in a snapshot value (proven with a CJK employee-name fixture) fails
    loudly through `PayslipPdfGenerationException` with a sanitized message -- never silently
    dropped/altered, never a raw PDFBox class name or exception text in the message.
  - `PdfStorageService` -- plain, directly instantiable class (Spring wiring deferred to 8C-2B).
    Deterministic server-controlled relative paths
    (`payroll-run-{runId}/payslip-{payslipId}.pdf`, positive ids only); writes to a temp file in
    the same target directory, then `ATOMIC_MOVE` + `REPLACE_EXISTING` (falling back to a plain
    replace if atomic move is unsupported); deletes any surviving temp file in a `finally` block;
    verifies every resolved path (write and read) stays under the normalized root using
    `Path.startsWith` (not string-prefix comparison, which a sibling directory like
    `storage-evil` could otherwise defeat); rejects absolute/traversal stored paths; treats a
    directory found where a file is expected as a missing file. All exception messages are
    generic and never contain the absolute root, a temp path, or a raw `IOException` message.
    The storage root is canonicalized (`toRealPath()`) once at construction, and both `store`
    and `load` additionally reject any pre-existing symbolic link at the run-directory or final
    file position and re-verify the real (canonicalized) physical path still lies under that
    canonical root before any write or read -- lexical normalization alone cannot prove physical
    containment when a symlink is involved. This is defense against a stored-path/pre-existing-
    link escape, not against an attacker able to continuously mutate the filesystem during the
    call (no `SecureDirectoryStream` or platform-specific code was added). The two symlink-escape
    tests that exercise this create a real OS symbolic link and use a JUnit assumption to skip
    only themselves, with a clear reason, on an OS/environment that does not permit creating one
    (verified in this environment: Windows without the `SeCreateSymbolicLinkPrivilege`) --
    production behavior is never weakened to accommodate a test environment.
  - `PayrollPdfStorageException`, `PayslipPdfFileMissingException`, `PayslipPdfGenerationException`
    -- low-level, safe exceptions only; no HTTP status mapping added in this sub-phase.
- **Entity lifecycle (Phase 8C-2A only -- no orchestration caller yet):**
  - `Payslip.markPdfGenerated(String relativePath)` / `markPdfFailed()` -- valid only from PENDING
    or FAILED, rejected from GENERATED; `markPdfGenerated` requires a non-blank relative path and
    sets `pdfStatus`+`pdfPath` together; `markPdfFailed` sets `pdfStatus = FAILED` and clears
    `pdfPath` so a FAILED row never retains a usable-looking path. No generic setter for either
    field.
  - `PayrollRun.markCompleted()` -- valid only from CALCULATED, rejected from PROCESSING, FAILED,
    and an already-COMPLETED run. The entity only enforces its own status precondition; verifying
    that every payslip in the run is actually GENERATED first is explicitly the future Phase
    8C-2B orchestration service's responsibility, documented on the method itself, since the
    entity cannot query other rows. The prior Phase 8A-2/8B-1 reflection-based "no method reaches
    COMPLETED" test in `PayrollRunLifecycleTest` was replaced with assertions that `markCompleted`
    is the *only* declared method mentioning "complet", that it succeeds only from CALCULATED,
    that every other starting state is rejected, and that no generic `setStatus` method exists.
  - A code-level note on `PdfStorageService` documents the future 8C-2B orchestration
    requirements: move the file before marking GENERATED; catch only expected PDF-generation/
    filesystem failures per payslip, never broadly catching and suppressing a JPA/database
    persistence failure; let a persistence failure escape (Hibernate may already have marked the
    transaction rollback-only); accept that a DB rollback after a successful move leaves a
    harmless deterministic orphan a later retry overwrites; and never treat a filesystem write as
    participating in the PostgreSQL transaction.
- Backend: 63 new tests (396 total, up from 333; 2 skip on an OS/environment that cannot create
  symbolic links, 0 failures, 0 errors) -- 10 in `CurrencyFormatterTest`, 15 in
  `PayslipPdfGeneratorTest` (PDF signature/parseability/page count, every required label and
  exact formatted amount, month/year formatting, disclaimer text, net pay visibility, absence of
  planted IC/bank/email/rate/path values, sanitized unsupported-character failure, unaltered
  supported names, repeated-generation resource-closing, and no filesystem file created), 22 in
  `PdfStorageServiceTest` (deterministic path, positive-id validation, atomic creation, exact
  bytes preserved, replacement, nested directory creation, temp-file cleanup on success and on a
  deterministic injected pre-move failure via a package-private test-only hook, traversal/
  absolute/sibling-prefix-escape rejection, missing-file and directory-masquerading handling,
  relative-only return value, no absolute path in exception text, safe root-initialization
  failure, no write outside the root, the real dev directory never created, a read-side symlink
  escape rejected with no path leaked in the exception (skippable), a write-side symlink run-
  directory escape rejected with nothing written outside the root and no temp file left behind
  (skippable), and a normal non-symlink file still loading correctly after the hardening), 11 in
  the new `PayslipLifecycleTest`, and 5 net new in `PayrollRunLifecycleTest` (11 replaced by 16:
  the four `markCompleted` transition/rejection tests plus the two replacement reflection tests,
  minus the one retired "no COMPLETED" test). Focused payroll-package total (including the `pdf`
  subpackage): **195** (132 pre-existing + 63 new -- corrected from an initially reported 192,
  which undercounted the true pre-8C-2A payroll-package baseline: it was 132 `@Test` methods,
  verified directly against the git history of every payroll test file, not 133 as first assumed;
  192 was in fact arithmetically correct for the sub-phase as it stood before this correction,
  and no test or count was actually missing). Counts were verified two ways: parsing generated
  Surefire XML `<testcase>`/`tests=` totals, and counting `@Test` annotations directly in the
  affected source files. `./mvnw.cmd clean verify` passes (396 run, 0 failures, 0 errors, 2
  skipped); Flyway remains at schema version 13 (no migration in this sub-phase, per the design
  report's finding that V8+V13 already contain `pdf_path`/`pdf_status`/`COMPLETED` support);
  `payroll_runs`/`payslips` confirmed empty (0 rows) after the full suite run, since no test in
  this sub-phase persists either entity; demo/manual data (`P7DEMO01`/`EMP001` employees,
  departments, salary structures, leave requests/balances, users) confirmed unchanged; neither
  `data/payroll-pdfs` nor `backend/data/payroll-pdfs` was created or touched by tests. Frontend
  was not touched and its test suite/build were not re-run.
- **Explicitly unimplemented, deferred to Phase 8C-2B and later:** `PayrollPdfService`/
  orchestration, `POST /api/payroll-runs/{id}/generate-pdfs`, `GET /api/payslips/{id}/pdf`, HTTP
  error mappings for the PDF-generation/storage exceptions above, PDF-generation audit events,
  generation concurrency, actually completing a persisted payroll run through a service, and
  every frontend page/route/sidebar change.

### Phase 8C-2B1 — Payslip PDF orchestration, partial failure, auditing, concurrency, completion
Status: **Complete.** Backend service orchestration only -- no
`POST /api/payroll-runs/{id}/generate-pdfs` or `GET /api/payslips/{id}/pdf` endpoint, no HTTP
error mappings, no frontend change. No new migration.

- **Spring wiring** (`com.hrpayroll.backend.config.PdfStorageConfig`): registers `PdfStorageService`
  and a new `PayslipPdfRenderer` interface (implemented by the existing `PayslipPdfGenerator`,
  Phase 8C-2A's rendering seam) as beans. `PdfStorageService`'s `@Bean` method is `@Lazy`, and the
  new `PayrollPdfService` obtains it only through an `ObjectProvider<PdfStorageService>` resolved
  inside its transactional method -- **both** are required: `@Lazy` alone would still let an eager
  consumer force real construction during its own wiring, and `ObjectProvider` alone would not stop
  Spring's unconditional eager-singleton-preinstantiation pass from constructing it regardless of
  consumers. Verified empirically, not just by inspection: a dedicated `PayrollPdfWiringTest` (real,
  unoverridden `app.payroll.pdf-storage-dir` default) was run once with `@Lazy` deliberately removed
  and failed exactly as predicted (`./data/payroll-pdfs` existed after context boot), then passed
  once `@Lazy` was restored -- genuine RED before GREEN, not reasoning alone.
- **`PayslipRepository.findByPayrollRunIdOrderByIdAsc`** -- one new unpaginated, deterministic
  list query for the internal generation batch. No fetch join and no N+1 risk: the orchestration
  never touches a payslip's lazy `employee`/`payrollRun` associations (it already holds the locked
  `PayrollRun` directly, and PDF rendering uses only `Payslip`'s own stored snapshot/monetary
  columns).
- **`PayrollPdfGenerationResult`** (new, `payroll` package) -- immutable service result
  (`payrollRunId`, final `PayrollRunStatus`, `generatedCount`, `failedCount`, `skippedCount`); never
  an HTTP response, never a JPA entity, never exposes a path/compensation value/employee
  detail/exception text.
- **`PayrollPdfService`** (new, deliberately a distinct bean from `PayrollRunTransactionService`,
  since PDF generation supports partial success -- the opposite of calculation's all-or-nothing
  rollback): one `@Transactional(propagation = REQUIRES_NEW)` method, `generatePdfs(payrollRunId,
  actorUserId)`. Locks the run via the existing `findByIdForUpdate` before inspecting status, holds
  that lock for the whole batch, and re-derives period month/year from the locked run rather than
  reloading any other association. State behavior: unknown run -> `PayrollRunNotFoundException`;
  `PROCESSING`/`FAILED` -> new `PayrollRunNotCalculatedException` (no PDF/status/audit change);
  `CALCULATED` with zero payslips -> new `PayrollRunHasNoPayslipsException` (never vacuously
  completes); `COMPLETED` -> idempotent no-op returning every payslip counted as skipped, no
  renderer/storage call, no state/version/file change, no new audit row.
  - **Per-payslip loop**: an already-`GENERATED` payslip is skipped outright (no renderer/storage
    call, no mutation). A `PENDING`/`FAILED` payslip is rendered from a `PayslipPdfData` built
    strictly from its own stored snapshot fields plus the locked run's month/year, stored via
    `PdfStorageService`'s deterministic path, and only *after* `store()` succeeds is
    `markPdfGenerated(relativePath)` called. Only `PayslipPdfGenerationException`/
    `PayrollPdfStorageException` are caught per-payslip -- caught there, `markPdfFailed()` is
    called and the loop continues; every other exception type is never caught here.
  - **Persistence boundary**: no repository save/flush call exists inside the per-payslip catch
    block. Entity mutations (`markPdfGenerated`/`markPdfFailed`) happen in-memory during the loop;
    `payslipRepository.saveAll(...)`/`flush()` run once, together, after the loop, outside every
    per-payslip catch -- so a genuine JPA/database persistence failure at that point is never
    caught by this method and always escapes, rolling back the whole `REQUIRES_NEW` transaction.
    Verified directly: a package-private `setPreFlushHookForTesting` seam (mirroring
    `PdfStorageService`'s `preMoveHook` precedent, same narrow "no genuine constraint violation can
    be safely constructed at this exact point without weakening the schema" justification) lets a
    test inject a `DataIntegrityViolationException` immediately before the flush; the test proves
    it escapes `generatePdfs` uncaught and the run/payslip states are fully rolled back to their
    pre-call values, with zero audit rows written. The already-atomically-moved PDF file for that
    one payslip is explicitly documented (and deleted in the test's own cleanup) as a harmless,
    deterministic orphan -- Postgres cannot roll back a filesystem write; a later real retry would
    simply overwrite the same deterministic path.
  - **Completion**: after the flush succeeds, if every payslip is now `GENERATED`,
    `PayrollRun.markCompleted()` is called and saved/flushed; the run stays `CALCULATED` otherwise.
    A payroll run is never marked `FAILED` because a PDF failed.
- **Auditing** -- exactly two actions, both via the existing `AuditService`: `PAYROLL_RUN_PDF_
  GENERATION_FINISHED` (`payrollRunId`, `periodMonth`, `periodYear`, `generatedCount`,
  `failedCount`, `skippedCount`), recorded once per non-no-op `CALCULATED` invocation regardless of
  outcome; `PAYROLL_RUN_COMPLETED` (`payrollRunId`, `periodMonth`, `periodYear`, `payslipCount`),
  recorded exactly once on the `CALCULATED -> COMPLETED` transition. No per-payslip audit row, no
  separate `STARTED` event, no audit at all for a `COMPLETED` no-op call. Every detail key set was
  verified exactly (via Jackson-parsed `audit_logs.details`), and every detail payload verified
  free of employee identity, salary/allowance/deduction/net-pay/rate values, `pdfPath`, and
  exception/failure text.
- **Test-only rendering seam**: `PayslipPdfRenderer` (new interface;
  `PayslipPdfGenerator implements` it, no behavior change) lets tests substitute
  `ControllablePayslipPdfRenderer` (new, `support` package) -- a real delegating renderer that can
  be armed to throw `PayslipPdfGenerationException` deterministically once for one employee code,
  then clears itself so a second call succeeds normally, modeling a genuine retry without OS
  permission tricks or mutating any immutable production snapshot.
- **Fixture-hygiene finding, fixed in-session**: the stored-snapshot-correctness test originally
  renamed the shared "Phase8 Test Department" fixture row via raw SQL to prove the generated PDF
  reflects the historical snapshot rather than current data, but never restored the name --
  `PayrollTestFixtures.cleanup()` matches that row by its exact original name, so the renamed row
  silently survived cleanup and collided (`uq_departments_name`) the next time that same test ran.
  Caught by running the full payroll-package suite together (not just the new test class in
  isolation); fixed by restoring the department's name in a `finally` block. No production code was
  affected; no demo/manual data was ever at risk (the collision was between two runs of the same
  test's own fixture row).
- Backend: 15 new tests (411 total, up from 396) -- 1 in `PayslipRepositoryTest`
  (deterministic id-ASC ordering across two runs), 1 in the new `PayrollPdfWiringTest` (dev
  directory never created merely by booting the context -- RED-then-GREEN verified by temporarily
  removing `@Lazy`), 11 in the new `PayrollPdfServiceTest` (successful generation with exact file/
  audit/version verification, stored-snapshot correctness against later-changed current data,
  partial failure, retry regenerating only the previously-failed payslip while previously-generated
  files' bytes/mtimes stay byte-for-byte unchanged, all-already-generated run-state-only repair,
  `COMPLETED` idempotency, `PROCESSING`/`FAILED` rejection, unknown-run rejection, zero-payslip
  rejection, and the persistence-failure-escapes proof), 1 in the new
  `PayrollPdfServiceConcurrencyTest` (two real-PostgreSQL threads racing the same `CALCULATED` run;
  `CountDownLatch`/`ExecutorService`, no mocks/H2/Testcontainers/synchronized/sleeps; exactly one
  generates, the other observes the `COMPLETED` no-op; exactly one `FINISHED`/`COMPLETED` audit
  pair; no duplicated/corrupted file), and 1 in the new
  `PayrollPdfServiceTransactionIsolationTest` (generation survives an outer `TransactionTemplate`
  transaction that is deliberately rolled back afterward, proving `REQUIRES_NEW`). Every existing
  Phase 8C-2A test (including both OS-dependent symlink-escape tests and their assumption-based
  skip behavior) continues to pass unmodified. `./mvnw.cmd clean verify` passes (411 run, 0
  failures, 0 errors, 2 skipped); console and Surefire-XML-derived totals cross-checked and
  identical; Flyway remains at schema version 13 (no migration in this sub-phase); PDFBox remains
  `3.0.8` (`dependency:tree` re-verified); `payroll_runs`/`payslips` confirmed empty (0 rows) after
  the full suite run; demo/manual data (`P7DEMO01`/`EMP001` employees, department, salary
  structures, leave requests/balances, users) confirmed unchanged; neither `data/payroll-pdfs` nor
  `backend/data/payroll-pdfs` exists anywhere in the repository; no generated PDF or temp file
  remains anywhere in the repository. Frontend was not touched and its test suite/build were not
  re-run.
- **Explicitly unimplemented, deferred to Phase 8C-2B2 and later:**
  `POST /api/payroll-runs/{id}/generate-pdfs`, `GET /api/payslips/{id}/pdf`, HTTP exception
  mappings for the PDF orchestration exceptions, HTTP response DTOs, automatic missing-GENERATED-
  file repair, and every frontend page/route/sidebar change.

### Phase 8C-2B2 — Payroll PDF generation and secure download HTTP APIs
Status: **Complete.** HTTP layer only, over the existing Phase 8C-2B1 orchestration/download
services -- no frontend page/route/sidebar/client change, no new migration, no new PDF
rendering/layout behavior, no regeneration of already-GENERATED payslips, and no automatic repair
of a GENERATED payslip whose physical file is missing.

- **`POST /api/payroll-runs/{id}/generate-pdfs`** (added to the existing `PayrollRunController`,
  `@PreAuthorize("hasRole('HR_ADMIN')")`, no request body, actor id only from
  `AuthenticatedUser.userId()`): calls the existing `PayrollPdfService.generatePdfs(id,
  actorUserId)` synchronously and always returns **200** -- complete success, partial success
  (`failedCount > 0`), and a `COMPLETED` idempotent no-op are all real outcomes reflected in the
  response body, never a fake asynchronous/PROCESSING status. New `PayrollPdfGenerationResponse`
  (`payrollRunId`, `status`, `generatedCount`, `failedCount`, `skippedCount`) is a dedicated HTTP
  DTO, mapped from the existing `PayrollPdfGenerationResult` via a static `from(...)` -- the
  service's internal result type is never returned directly. Error behavior: unknown run -> `404
  PAYROLL_RUN_NOT_FOUND`; `PROCESSING`/`FAILED` -> new `409 PAYROLL_RUN_NOT_CALCULATED`;
  `CALCULATED` with zero payslips -> new `409 PAYROLL_RUN_HAS_NO_PAYSLIPS`; an unexpected
  storage-root initialization failure (thrown by `PdfStorageService`'s constructor on first
  `ObjectProvider.getObject()` call, before any per-payslip processing) -> `500
  PAYROLL_PDF_STORAGE_UNAVAILABLE`. Per-payslip render/storage failures remain internal to
  `PayrollPdfService` exactly as built in 8C-2B1 -- they are never HTTP exceptions; the endpoint
  still returns 200 with `failedCount > 0` and the run left `CALCULATED`.
- **`GET /api/payslips/{id}/pdf`** (added to the existing `PayslipController`, deliberately no
  `@PreAuthorize` -- same convention as the existing `GET /{id}`, since the real authorization
  rule is ownership-based, not role-gated): new `PayrollPdfDownloadService`, a dedicated read-only
  service kept separate from `PayrollPdfService`'s generation transaction. Loads the payslip via
  the existing `PayslipRepository.findWithDetailsById`, enforces the exact same ownership rule as
  `GET /api/payslips/{id}` (HR_ADMIN any, EMPLOYEE own-only, a non-owning EMPLOYEE gets the same
  `404 PAYSLIP_NOT_FOUND` as an unknown id, never `403`), checks `pdfStatus` (`PENDING`/`FAILED` ->
  new `409 PAYSLIP_PDF_NOT_AVAILABLE`), and treats a `GENERATED` payslip with a missing/blank
  `pdfPath` as a server-side integrity failure (`PayslipPdfFileMissingException` -> `500
  PAYSLIP_PDF_FILE_MISSING`), never a 409. Bytes are read only through the existing
  `PdfStorageService.load(pdfPath)`, obtained lazily through the same `ObjectProvider` pattern as
  `PayrollPdfService` -- the controller/service never resolves or reads the stored path directly,
  and no second filesystem-reading implementation was introduced anywhere. New immutable
  `PayslipPdfDownload` (`byte[] content`, `String filename`) defensively clones its byte array on
  both construction and every `content()` access. Any other storage-layer failure (invalid/
  traversal/symlink stored path, a read failure) surfaces as the existing `PayrollPdfStorageException`
  -> new `500 PAYROLL_PDF_STORAGE_UNAVAILABLE` mapping.
  - **Response**: `ResponseEntity<byte[]>` (small, bounded documents; no second filesystem
    implementation needed) with `Content-Type: application/pdf`, `Content-Length` set from the
    exact byte array length, `Content-Disposition: attachment; filename="..."`, and
    `Cache-Control: private, no-store` set explicitly (never relying on `CacheControl` builder
    chaining ambiguity) since this is personal financial data that must never sit in a shared/proxy
    cache.
  - **Filename**: `payslip-{year}-{twoDigitMonth}-{sanitizedEmployeeCode}.pdf`, built entirely
    server-side from the run's own period and a sanitized copy of the employee code snapshot --
    never the employee name, IC number, bank information, email, or any filesystem path, and never
    a database id beyond what the deterministic storage path already used internally (not exposed
    here at all). Sanitization replaces every character outside `A-Za-z0-9_-` with `_`, which by
    construction also eliminates any possibility of quote/CR/LF/semicolon/slash/backslash header
    injection through a planted unsafe employee-code value -- verified directly with a test that
    plants exactly those characters and asserts the resulting `Content-Disposition` is a single
    well-formed attachment-filename header.
- **New safe exceptions**: `PayslipPdfNotAvailableException` (`409 PAYSLIP_PDF_NOT_AVAILABLE`).
  `PayrollRunNotCalculatedException`/`PayrollRunHasNoPayslipsException` (from Phase 8C-2B1) and the
  existing `PayslipPdfFileMissingException`/`PayrollPdfStorageException` (from Phase 8C-2A) are
  mapped for the first time in `PayrollExceptionHandler`, which now also handles the two `pdf`
  storage failure types alongside its existing payroll-run/payslip handlers. Every response
  verified free of stack traces, SQL, exception class names, PDFBox internal text, salary/
  deduction/rate values, employee identity, and any relative or absolute filesystem path.
- **Test-fixture root-cause finding, fixed in-session**: the download controller test's shared
  "own employee" fixture was initially created `ACTIVE` with an explicit 1999 hire date (matching
  an established pattern from other test classes), which made it an accidental payroll candidate
  for *every* other test method's period in the same class/year, since only one test gave it
  salary coverage -- every other test's `createAndCalculate` call failed with
  `SALARY_COVERAGE_MISSING`. Root-caused (systematic-debugging) to the candidate contract
  (`ACTIVE AND hireDate <= periodStart`) matching regardless of which test "owned" the period;
  fixed by making the fixture employee `INACTIVE` by default (excluded from candidacy
  unconditionally) and flipping it to `ACTIVE` only inside the one test that needs it. A second,
  related bug in the same fixture setup (other "candidate" employees using the 4-arg
  `insertEmployee` overload, whose default `hire_date = CURRENT_DATE` is *after* the test's 2015
  period, making them ineligible too) was fixed by introducing an explicit `insertCandidate`
  helper mirroring `PayrollPdfServiceTest`'s established pattern. No production code was affected;
  no demo/manual data was ever at risk.
- Backend: 21 new tests (432 total, up from 411) -- 9 in the new
  `PayrollRunGeneratePdfsControllerTest` (HR_ADMIN success with exact counts/COMPLETED status/file
  existence/audit verification, partial failure returning 200 with no leakage, `COMPLETED`
  idempotency with no file rewrite, `PROCESSING`/`FAILED` -> 409, zero-payslip `CALCULATED` -> 409,
  unknown run -> 404, unauthenticated -> 401, EMPLOYEE -> 403) and 12 in the new
  `PayslipPdfDownloadControllerTest` (HR_ADMIN downloads any, exact `Content-Length`, owning
  EMPLOYEE downloads own, non-owning EMPLOYEE gets the same 404 as unknown, unknown payslip 404,
  `PENDING`/`FAILED` -> 409, missing physical file -> 500 with no path leaked, traversal stored
  path -> 500 with no path leaked, a symlink stored path -> 500 (skippable on an OS that cannot
  create symbolic links, same assumption-based convention as Phase 8C-2A -- 1 skipped in this
  environment), filename sanitization against a planted unsafe employee code, and unauthenticated
  -> 401). The existing `PayslipControllerTest` (`GET /api/payslips/{id}` JSON behavior) and
  `PayrollRunControllerTest` were re-run unmodified and continue to pass exactly as before,
  confirming no regression. `./mvnw.cmd clean verify` passes (432 run, 0 failures, 0 errors, 3
  skipped -- the 2 pre-existing Phase 8C-2A symlink tests plus this sub-phase's 1 new symlink
  test); console and Surefire-XML-derived totals cross-checked and identical; Flyway remains at
  schema version 13 (no migration in this sub-phase); PDFBox remains `3.0.8`; `payroll_runs`/
  `payslips` confirmed empty (0 rows) after the full suite run; demo/manual data (`P7DEMO01`/
  `EMP001` employees, department, leave requests/balances, users) confirmed unchanged; neither
  `data/payroll-pdfs` nor `backend/data/payroll-pdfs` exists anywhere in the repository; no
  generated PDF or temp file remains anywhere in the repository. Frontend was not touched and its
  test suite/build were not re-run.
- **Explicitly unimplemented, deferred to later:** frontend pages/buttons/routes/sidebar/API
  client, automatic repair of a GENERATED payslip whose physical file is missing, scheduler/Spring
  Batch, attendance/leave/overtime/proration integration, and V14/any migration.

### Phase 8D-2A1 — Backend browser-contract corrections for the payroll frontend
Status: **Complete.** Two narrow gaps found during the Phase 8D-1 frontend-integration audit,
fixed backend-only, before any frontend implementation begins. No new endpoint, no new database
column, no migration, no payroll-calculation or PDF-generation change.

- **`periodMonth`/`periodYear` added to `PayslipResponse`.** The response previously had no field
  identifying which payroll month/year a payslip belongs to. `createdAt` cannot substitute for
  this: a payroll run can be created well after (or before) the period it actually covers, so
  `createdAt` reflects *when the row was written*, not the payroll period. The Employee-facing
  `GET /api/employees/me/payslips` endpoint has no other way to learn the period either --
  `GET /api/payroll-runs/{id}` is HR_ADMIN-only (`@PreAuthorize`), so an authenticated employee's
  browser session cannot fetch the associated run directly to read its period. Both new fields are
  populated from the payslip's own associated `PayrollRun` snapshot
  (`payslip.getPayrollRun().getPeriodMonth()/getPeriodYear()`), never derived from `createdAt` and
  never constructed via `java.util.Date`. This is purely additive: every existing field is
  unchanged, `pdfPath` remains excluded, and the only construction site (`PayslipResponse.from`)
  was the sole call site verified via `grep`.
  - **Lazy-loading/N+1 check (no fix needed):** all three query paths that build a
    `PayslipResponse` -- `PayslipRepository.findWithDetailsById` (`GET /api/payslips/{id}`),
    `findByPayrollRunId` (`GET /api/payroll-runs/{id}/payslips`), and `findByEmployeeId`
    (`GET /api/employees/me/payslips`) -- already `JOIN FETCH p.payrollRun JOIN FETCH p.employee`.
    Confirmed by direct source inspection before writing any test or production code; no
    repository change was made, and no eager mapping was added elsewhere.
- **`Content-Disposition` exposed via CORS.** `SecurityConfig.corsConfigurationSource()` had no
  `setExposedHeaders(...)` call at all, so although the PDF-download endpoint
  (`GET /api/payslips/{id}/pdf`) already returned a server-generated safe `Content-Disposition`
  filename, browser JavaScript running on the configured frontend origin
  (`app.cors.allowed-origin`, default `http://localhost:5173`) could not read that header --
  browsers only expose a fixed "safe" header allowlist to script unless the server explicitly
  opts a header in via `Access-Control-Expose-Headers`. Fixed by adding exactly one entry:
  `configuration.setExposedHeaders(List.of("Content-Disposition"));`. No other CORS permission was
  touched or loosened: allowed origins, allowed methods (`GET, POST, PUT, PATCH, DELETE`), and
  allowed request headers (`Authorization, Content-Type`) are all unchanged; `Authorization` is
  still never exposed; no wildcard (`"*"`) is used anywhere in the exposed-header list; credential
  behavior is unchanged (this app never sets `setAllowCredentials`).
- **RED-first tests.** Group 1 (serialization) extended three existing real-PostgreSQL controller
  test methods across `PayrollRunControllerTest`, `PayslipControllerTest` (two methods), and one
  new test method in `PayslipSelfServiceControllerTest`
  (`responseIncludesPeriodMonthAndPeriodYearFromTheAssociatedRunNotCreatedAt`), each using a period
  month/year deliberately different from the row's `createdAt` timestamp, to prove the served
  values come from the `PayrollRun`, never `createdAt`. All four assertions were run against
  unmodified code first and failed for the expected reason (`PathNotFoundException` /
  `No value at JSON path "...periodMonth"`), confirming RED before any production change. Group 2
  (CORS) is a new test class, `PayslipPdfDownloadCorsTest` (3 tests), reusing the existing
  `@TempDir`/`@DynamicPropertySource`/`ControllablePayslipPdfRenderer` fixture pattern from
  `PayslipPdfDownloadControllerTest` to generate a real PDF, then asserting on
  `Access-Control-Allow-Origin`, `Access-Control-Expose-Headers`, and `Content-Disposition` for an
  `Origin` header equal to the actual configured `app.cors.allowed-origin` value (read via
  `@Value`, never hard-coded) and for a disallowed origin. Two of the three failed against
  unmodified code for the expected reason (missing `Access-Control-Expose-Headers`); the
  disallowed-origin test passed unmodified, confirming that existing origin-rejection behavior
  needed no change. After both production edits, all four Group 1 assertions and all three Group 2
  tests pass; every other test in the three edited files and in `PayslipPdfDownloadControllerTest`/
  `PayrollRunGeneratePdfsControllerTest` continues to pass unmodified, confirming no regression to
  payslip ownership (HR_ADMIN any, EMPLOYEE own-only, non-owner 404 not 403), PDF-download
  authentication, or the existing PDF response headers (`Content-Type: application/pdf`,
  `Content-Disposition: attachment`, `Content-Length`, `Cache-Control: private, no-store`).
- Backend: 4 new tests (436 total, up from 432) -- 1 new method in
  `PayslipSelfServiceControllerTest` and 3 in the new `PayslipPdfDownloadCorsTest`; the other three
  Group-1 assertions extended existing test methods without adding new test-method count.
  `./mvnw.cmd clean verify` passes (436 run, 0 failures, 0 errors, 3 skipped -- the same 3
  pre-existing symlink-unsupported-OS skips as the Phase 8C baseline, none added or removed);
  console and Surefire-XML-derived totals cross-checked and identical; Flyway remains at schema
  version 13 (no migration in this sub-phase); PDFBox remains `3.0.8`; `payroll_runs`/`payslips`
  confirmed empty (0 rows) after the full suite run; demo/manual data unchanged; neither
  `data/payroll-pdfs` nor `backend/data/payroll-pdfs` exists anywhere in the repository; no
  generated PDF or temp file remains anywhere in the repository; `git diff --check` reports no
  whitespace errors. Frontend was not touched (zero `frontend/` entries in `git status`) and its
  test suite/build were not re-run; frontend implementation has still not started.
- **Explicitly unimplemented, deferred to later:** every item unimplemented as of Phase 8C-2B2
  (frontend pages/buttons/routes/sidebar/API client, automatic PDF-file repair, scheduler/Spring
  Batch, attendance/leave/overtime/proration integration, V14/any migration) remains deferred; this
  sub-phase adds no new deferred item.

### Phase 8D-2A2 — Frontend payroll API, types, formatting, and secure PDF-download foundation
Status: **Complete.** Data/utility layer only, built on top of the Phase 8D-2A1 backend contract
fixes -- no payroll React pages, routes, sidebar items, status badges, forms, tables, or modals
yet. Backend, database, and dependencies were not touched.

- **`frontend/src/api/payroll.ts`** (new). Types matching the backend exactly: `PayrollRunStatus`,
  `PdfStatus`, `PayrollFailureCode` (mirrors the backend enum for documentation purposes only --
  `PayrollRun.failureReason` is typed `string | null`, since the backend actually serializes a
  free-text safe message, e.g. "No eligible employees for this period.", never the bare code),
  `CreatePayrollRunRequest`, `PayrollRun`, `Payslip` (including `periodMonth`/`periodYear` from
  Phase 8D-2A1; `pdfPath` deliberately absent), and `PayrollPdfGenerationResult`. Every
  `BigDecimal`-backed field is a plain `number`, matching the existing convention in
  `salaryStructures.ts`/`leave.ts`. `PagedResponse<T>` is a local duplicate (same shape as
  `leave.ts`'s), not a shared cross-module import -- this codebase has no shared paging type
  exported from any module yet (verified: nothing imports `leave.ts`'s `PagedResponse` elsewhere),
  so a domain-local copy follows the established per-module convention rather than introducing a
  new cross-module dependency.
  - **API functions**, all via the existing authenticated `apiClient` (no direct `sessionStorage`
    read, no second `Authorization` header, no client-side re-sorting or re-filtering): `create
    PayrollRun`, `listPayrollRuns` (status/year/page/size are omitted entirely -- not sent as
    empty/null -- when not provided, via a small `cleanParams` helper that preserves falsy-but-
    valid values like `page: 0`), `getPayrollRun`, `listPayrollRunPayslips`, `retryPayrollRun` and
    `generatePayrollPdfs` (both POST with no body, one argument only -- same convention as
    `leave.ts`'s `approveLeaveRequest`), `getPayslip`, and `listOwnPayslips` (no employee-id
    parameter anywhere, on the function signature or the request it sends -- identity comes only
    from the JWT, matching every other self-service endpoint in this project).
- **Authenticated PDF download.** Split into two independently-testable pieces plus one
  convenience wrapper, all in `payroll.ts`: `fetchPayslipPdf(id)` calls `GET /api/payslips/{id}/pdf`
  with `responseType: 'blob'` through the same `apiClient` (interceptor attaches the Bearer token;
  nothing here reads `pdfPath` or accepts a caller-supplied filename) and returns `{ blob,
  filename }`; `saveBlobAsFile(blob, filename)` performs the browser download mechanics
  (`URL.createObjectURL` -> temporary `<a>` -> `document.body.appendChild` -> `click()` ->
  `remove()` -> `URL.revokeObjectURL`); `downloadPayslipPdf(id)` composes both for page call sites.
  Splitting fetch from DOM-manipulation keeps each half cheaply unit-testable (network/parsing
  behavior vs. browser mechanics) and lets both HR Admin and Employee pages reuse the exact same
  function later, since ownership is enforced entirely server-side.
  - **Cleanup hardening (found and fixed during a same-day reconciliation pass, RED-first):** the
    object URL is now revoked in an outer `finally` that wraps `document.createElement('a')`
    itself, not just the click/append/remove sequence -- the original version created the object
    URL, then called `document.createElement('a')` *outside* any `try`, so a `createElement`
    failure would skip the `finally` entirely and leak the URL. Anchor removal now runs in its own
    nested `try/finally` so a failure there (e.g. `anchor.remove()` throwing) still lets the outer
    `finally` run and revoke the URL -- that removal failure is deliberately left to propagate
    rather than being swallowed. Two RED tests (`document.createElement('a')` throwing;
    `anchor.remove()` throwing) were written first, confirmed failing against the original
    implementation (`revokeObjectURL` called 0 times instead of 1) before this fix was made, and
    the pre-existing `click()`-throws test continues to pass unmodified.
- **Content-Disposition filename parsing** (`parsePayslipPdfFilename`, exported from
  `payroll.ts`): extracts a quoted or unquoted `filename=` value, rejects the raw value outright
  (falls back, rather than attempting to salvage it) if it contains any `/` or `\`, then sanitizes
  to `[A-Za-z0-9._-]` only, then rejects a result that is blank, exactly `.` or `..`, or does not
  end in `.pdf`. Any failure at any step falls back to `payslip-{id}.pdf`. This is defense in
  depth only -- the backend already sanitizes its own filename (Phase 8C-2B2) -- and guarantees no
  slash, backslash, quote, CR/LF, or semicolon can ever reach the `download` attribute.
- **`extractBlobProblemDetails`** added to the existing `frontend/src/api/errors.ts` (not a new
  file, since it is the same "parse the backend's ProblemDetail" concern as the existing
  `extractErrorMessage`/`extractErrorCode`, both left unchanged). A `responseType: 'blob'` request
  always gets a `Blob` error body from axios, even when the backend actually returned JSON, so this
  checks `response.data instanceof Blob`, accepts the body as JSON when *either* `Blob.type` or the
  response's `content-type` header indicates `application/json`/`application/problem+json`, reads
  the Blob text asynchronously, parses it inside its own try/catch, and validates that
  `code`/`detail` are strings before returning them -- returning `{ code: null, detail: null }` for
  every failure mode (non-Blob data, neither source indicating JSON, empty body, malformed JSON,
  missing or wrong-typed properties) without ever throwing a second error that could mask the
  original request failure.
  - **Dual-source check (found and fixed during the same reconciliation pass, RED-first):** the
    original version picked one source -- `Blob.type` if truthy, else the header -- so a generic
    `Blob.type` (e.g. `application/octet-stream`, which some proxies/adapters default to) would
    silently override an explicit `application/problem+json` response header, discarding a
    perfectly parseable ProblemDetail body. The two sources are now checked independently
    (`JSON_CONTENT_TYPE_PATTERN.test(blob.type) || JSON_CONTENT_TYPE_PATTERN.test(headerContentType(...))`)
    so either one alone is sufficient, and a generic value from one can never suppress an explicit
    JSON value from the other. A RED test (generic `Blob.type`, JSON header) was written first and
    confirmed failing against the original implementation before this fix; the symmetric case
    (JSON `Blob.type`, generic header) and the neither-indicates-JSON case already passed
    unmodified, confirming they needed no change.
- **`frontend/src/utils/currency.ts`** (new module; no `src/utils` directory existed before this
  sub-phase). `formatMYR(amount: number)` uses
  `Intl.NumberFormat('en-MY', { style: 'currency', currency: 'MYR' })` directly (no manual comma
  insertion, no string-based rounding) and throws for `NaN`/`Infinity` rather than silently
  rendering an invalid payroll figure. Its actual output in this environment inserts a U+00A0
  no-break space between "RM" and the digits, not a plain space -- confirmed by direct inspection
  and encoded into the tests as the `\u00A0` escape so they are deterministic rather than coincidentally
  passing.
- **`frontend/src/utils/payrollPeriod.ts`** (new). `formatPayrollPeriod(month, year)` uses a
  static 12-entry month-name array and string concatenation only -- it never constructs a `Date`,
  so it cannot be affected by the viewer's browser timezone (a `Date`-based approach can render the
  wrong local month near a day boundary depending on timezone). Both `month` (integer, 1-12) and
  `year` (integer) are validated with `Number.isInteger`, throwing clearly on a fractional month, a
  non-integer year, or a month outside 1-12, rather than silently displaying a wrong period.
- **RED-first tests**, one new test file per production file plus one extending the existing
  `errors.ts`: `payroll.test.ts` (15 tests: API functions, param omission, pagination, error
  propagation), `payrollPdf.test.ts` (21 tests: filename parsing including quoted/unquoted/missing/
  blank/malformed/traversal/"."/".."/no-extension/CR-LF/quote/semicolon cases, `fetchPayslipPdf`,
  `saveBlobAsFile` including the click()-throws, createElement()-throws, and remove()-throws
  cleanup cases, `downloadPayslipPdf`), `errors.test.ts` (17 tests: 10 for the original
  `extractBlobProblemDetails` cases, 3 for the dual-source content-type reconciliation above, plus
  4 confirming `extractErrorMessage`/`extractErrorCode` behavior is unchanged), `currency.test.ts`
  (7 tests), `payrollPeriod.test.ts` (8 tests). Every new test file was run against the
  pre-existing code first and failed for the expected reason (`Failed to resolve import` for a
  brand-new module, `TypeError: ... is not a function` for a new export added to an existing file,
  or an exact wrong-value assertion failure for the two reconciliation fixes) before any
  production code was written or changed; each behavior group was then implemented/fixed and
  re-run to green independently.
- **Test-baseline reconciliation.** Rather than trusting the previously-documented 128/63/191
  arithmetic outright, the exact HEAD (`e41dd5a6...`) baseline was re-measured directly: `git stash
  push -u` (stashing all Phase 8D-2A2 changes, tracked and untracked), `npx vitest run` against the
  now-HEAD-only tree (128 tests passed -- confirmed identical to the documented baseline), then
  `git stash pop` to restore. `git cat-file -e HEAD:frontend/src/api/errors.test.ts` confirmed that
  file did not exist at HEAD (exit 128, "exists on disk, but not in HEAD"), consistent with it
  being counted as a wholly new file rather than a modification. The original 63-new/191-total
  arithmetic was confirmed accurate; the two production fixes above then added 5 further tests (2
  URL-cleanup, 3 content-type), bringing the final count to 68 new tests.
- Frontend: 68 new tests (196 total, up from a confirmed 128 baseline), all passing; 0 failures; 0
  skipped. `npm run build` (`tsc -b && vite build`) succeeds. Backend was not touched (`git status`
  shows zero `backend/` entries), no database or running service was touched, no dependency or
  lockfile changed, and no generated or downloaded PDF file remains anywhere in the repository.
- **Explicitly unimplemented, deferred to later:** payroll/payslip React pages, admin and employee
  routes, sidebar entries, status badges, forms/tables/modals/dialogs, and any frontend
  authentication change -- all still to come in a later sub-phase.

Phase 8 remains **In progress** overall. The frontend now has its payroll data/API/formatting
foundation, but no payroll page, route, or sidebar entry exists yet.

### Phase 8D-2B — HR Admin payroll-run list and create-run UI
Status: **Complete.** First real payroll page, built entirely on the Phase 8D-2A2 API/formatting
foundation. The `/admin/payroll/{id}` detail route, retry, PDF generation, payslip tables/
downloads, and every employee-facing payroll UI remain unimplemented -- this sub-phase is list +
create only.

- **`frontend/src/routes/admin/PayrollRunListPage.tsx`** (new) replaces the `/admin/payroll`
  `PlaceholderPage` route in `App.tsx`. Uses only `listPayrollRuns`/`createPayrollRun` from
  `api/payroll.ts` -- the page never calls `apiClient` directly. Loads page 0/size 10 on mount
  with both filters omitted entirely from the request object (not sent as `undefined`), matching
  the exact same per-module `cleanParams`-style omission convention already established in
  `api/payroll.ts` itself. Selecting a status or entering a year filter resets to page 0.
  Previous/Next are disabled at the first/last page and while a request is in flight, and an
  early-return guard (`if (loading) return`) in the page-change handler additionally prevents a
  second in-flight request even if a disabled button were somehow still triggered. Backend
  ordering is trusted as-is -- rows render in the exact order `listPayrollRuns` returns them, with
  no client-side sort. Each row shows the period via `formatPayrollPeriod`, a status badge, and the
  creation timestamp reformatted with plain string slicing (`value.replace('T', ' ').slice(0,
  16)`) rather than a `Date` object -- consistent with `payrollPeriod.ts`'s timezone-safety
  rationale, even though `createdAt` itself has no timezone ambiguity to resolve. A "View" link
  points to `/admin/payroll/{id}`, a route intentionally left unimplemented in this sub-phase. The
  table exposes only period/status/created/actions -- no rate snapshot, monetary amount, payslip,
  or `pdfPath` ever appears in this list.
- **Status badges** cover all four `PayrollRunStatus` values (`PROCESSING`, `CALCULATED`,
  `COMPLETED`, `FAILED`) with distinct Tailwind background/text color pairs, each rendering the
  status name as visible text (never color-only) -- no new component library or icon dependency.
- **Inline create-run form** (no modal library): a "Create Payroll Run" button reveals a form with
  a 12-option named-month `<select>` and a year `<input type="number" min="2000" max="2100">`;
  "Cancel" hides it again and clears its fields/error. The form carries `noValidate` so the
  browser's native min/max constraint validation never silently blocks submission before this
  page's own JS validation runs (found during RED-first testing -- see below). Submission is
  guarded twice against duplicates: an early `if (creating) return` at the top of the handler, and
  `disabled` on every form control while submitting. Client-side validation (month integer 1-12,
  year integer 2000-2100) runs before any network call; an invalid value shows a `role="alert"`
  message and never calls `createPayrollRun`. On success: a `aria-live="polite"` success message
  appears, the form resets and collapses, the page resets to 0, and the list re-fetches via a
  `refreshToken` dependency bump (needed because `setPage(0)` alone is a no-op when the page is
  already 0 and would otherwise silently skip the refetch). The page never navigates to the detail
  route automatically. The actor/user is never derived in the browser -- `createPayrollRun` sends
  only `{ month, year }`, exactly as `api/payroll.ts` already enforces.
- **Stable error-code handling** via `extractErrorCode`/`extractErrorMessage`: for
  `PAYROLL_RUN_PERIOD_ALREADY_EXISTS` and `PAYROLL_RUN_FAILED` (both confirmed against the actual
  backend `PayrollExceptionHandler`, which guarantees `detail` is always a pre-sanitized safe
  message for these two codes), the backend's own `detail` is shown via `extractErrorMessage` with
  a sensible fallback string. For any other/unrecognized code, the backend's `detail` is
  deliberately **never** read at all -- a fixed generic fallback ("Could not create payroll run.
  Please try again.") is shown instead, since an unexpected code has no guarantee its detail is
  safe to display. The form remains fully usable after any failure (fields, selection, and the
  visible form all persist; only `creating` and the error message change).
- **Route and sidebar.** `App.tsx`'s existing HR_ADMIN-only `/admin/payroll` `ProtectedRoute`
  boundary is preserved unchanged -- only the rendered element changed from `PlaceholderPage` to
  `PayrollRunListPage`. `Sidebar.tsx` gains `{ label: 'Payroll', to: '/admin/payroll' }` in
  `HR_ADMIN_ITEMS` (no icon, no employee-side item), and the stale comment listing `/admin/payroll`
  as an unfinished placeholder was corrected. No employee route or sidebar item was touched.
- **RED-first evidence.** `PayrollRunListPage.test.tsx` was written first against a nonexistent
  file (import-resolution failure), then the page was implemented and iterated to green; three
  real defects were caught and fixed this way rather than being designed away in advance
  (systematic-debugging): (1) the year input's native `min`/`max` HTML5 constraint validation
  silently blocked the `submit` event before this page's own JS validation could run or set any
  error state -- fixed by adding `noValidate` to the `<form>`; (2) an extra "Select a month"
  placeholder option made the month `<select>` have 13 options instead of the required 12 -- fixed
  by removing the placeholder and defaulting `month` to `'1'`; (3) an initial "duplicate click"
  test raced against an instantly-resolving mock (each `await user.click` fully settles, including
  the mocked request, before the next click fires), so it was actually asserting on two
  legitimate, non-overlapping page loads rather than a real double-submission -- fixed by rewriting
  the test with a controllable pending Promise so the second click genuinely lands while the first
  request is still in flight and the button is disabled. `AppLayout.test.tsx`'s admin sidebar
  enumeration test and href-list test were extended (RED first: `Payroll` link not found) before
  `Sidebar.tsx` was changed. `App.test.tsx` gained three new `/admin/payroll` routing tests
  (unauthenticated -> redirected to login, EMPLOYEE -> blocked, HR_ADMIN -> sees "Payroll Runs"),
  the last of which was RED (heading still read "Payroll", the placeholder's title) before
  `App.tsx`'s route element was swapped.
- **Incidental full-suite flakiness (diagnosed, not a regression).** A full `vitest run` sometimes
  reported spurious timeouts across several unrelated, pre-existing test files (never in the files
  changed this sub-phase). Root-caused via `Get-CimInstance Win32_Process` inspection: several of
  the user's own legitimate dev-server processes (`npm run dev -w backend`, `npm run dev -w
  frontend`, plus an unrelated project's `tsx watch`/`vite` processes) were running concurrently on
  the same machine, competing for CPU with Vitest's ~19 spawned worker processes and inflating
  worker-startup time roughly 4-5x. A clean `vitest run --no-file-parallelism` (which does not
  touch or stop any other process) reproduced 229/229 green, confirming the failures were
  environmental contention, not a code defect -- no production code was changed in response to
  this false signal.
- Frontend: 33 new tests (229 total, up from 196) -- 29 in the new `PayrollRunListPage.test.tsx`,
  1 new sidebar-navigation test plus 2 existing tests extended (not counted as new) in
  `AppLayout.test.tsx`, and 3 new routing tests in `App.test.tsx`. All 229 pass; 0 failures; 0
  skipped. `npm run build` (`tsc -b && vite build`) succeeds. Backend was not touched (`git status`
  shows zero `backend/` entries), no database or service was touched, no dependency or lockfile
  changed, and no generated/downloaded PDF or temp file remains anywhere in the repository.
- **Explicitly unimplemented, deferred to later:** the `/admin/payroll/{id}` detail page, retry,
  PDF generation, payslip tables, PDF download buttons, every employee-facing payroll page, and any
  employee sidebar change -- all still to come in a later sub-phase.

Phase 8 remains **In progress** overall. HR Admin can now list and create payroll runs from the
UI; nothing else in the payroll frontend surface (detail, retry, generate, download, employee
payslips) exists yet.

### Phase 8D-2C — HR Admin payroll-run detail, retry, PDF generation, payslip table, and secure PDF download UI
Status: **Complete.** Second and final HR Admin payroll page for this phase, built entirely on the
existing `api/payroll.ts`/`api/errors.ts`/`utils/currency.ts`/`utils/payrollPeriod.ts` foundation.
Employee-facing payroll UI, regeneration of already-`GENERATED` payslips, and automatic repair of
a missing PDF file all remain unimplemented.

- **`frontend/src/routes/admin/PayrollRunDetailPage.tsx`** (new) at `/admin/payroll/:id`, inside
  the same `<ProtectedRoute allowedRoles={['HR_ADMIN']}>` boundary as every other admin route; the
  existing `/admin/payroll` list route is untouched. The id from `useParams` is validated as a
  positive integer client-side (`Number.isInteger(id) && id > 0`) before any network call --  an
  invalid id (non-numeric, zero, negative, fractional) shows the fixed string "Payroll run not
  found." and never calls `getPayrollRun`/`listPayrollRunPayslips`. A valid id fires both
  `getPayrollRun(id)` and `listPayrollRunPayslips(id, 0, 10)` from two independent `useEffect`s
  (genuine parallel requests, independent loading/error state per section) rather than one
  `Promise.all` -- so a payslip-list failure never hides an already-loaded run summary, and vice
  versa. A run-level 404 (`PAYROLL_RUN_NOT_FOUND`) is deliberately normalized to the same fixed
  "Payroll run not found." string as the invalid-id case, rather than trusting whatever text the
  backend's `detail` happens to contain for that code -- any other load failure falls back to
  `extractErrorMessage`. Neither page ever calls `apiClient` directly, and there is no polling --
  every backend mutation here is synchronous. A "&larr; Back to Payroll Runs" link returns to
  `/admin/payroll`.
- **Run summary** (semantic `dl`/`dt`/`dd`, matching `LeaveRequestDetailPage`'s convention): period
  via `formatPayrollPeriod(periodMonth, periodYear)` (never `createdAt`, never a constructed
  `Date`), a status badge for all four `PayrollRunStatus` values (visible text, not color-only,
  same badge-style map as `PayrollRunListPage`), `failureReason` rendered only when truthy,
  `createdAt`, `updatedAt`, `version`, and the four rate snapshots under an explicit "fixed
  demonstration values, not statutory advice" label. Nothing on this page is editable -- no input,
  textarea, or select renders in the summary at all -- and `pdfPath` is never referenced anywhere
  in this file.
- **State-dependent actions.** "Retry Payroll" renders only for `FAILED`; "Generate PDFs" only for
  `CALCULATED`; neither renders for `PROCESSING` or `COMPLETED` -- inapplicable buttons are omitted
  entirely, never rendered disabled. Both use an inline confirm/cancel state
  (`'idle' | 'retry-confirm' | 'generate-confirm'`), the same pattern as
  `LeaveRequestDetailPage`'s `ReviewMode`, with no modal library. Because the mode is a single
  enum, the two confirmations can never be open simultaneously, and the trigger buttons themselves
  are hidden whenever a confirmation is open. Each confirm action is guarded by an early-return
  (`if (retrying/generating) return`) in addition to disabling the confirm/cancel buttons while in
  flight. On success, both bump a single `refreshToken` state that both the run-summary effect and
  the payslip-list effect depend on -- refetching each from the server rather than merging the
  mutation's own response into local state, so the rendered status always reflects what the
  backend actually persisted.
  - **Retry**: recognized codes (`PAYROLL_RUN_NOT_FOUND`, `PAYROLL_RUN_NOT_FAILED`,
    `PAYROLL_RUN_FAILED`) show the backend's safe `detail`; anything else shows a fixed fallback.
    On an error, the confirmation stays open (mirroring `LeaveRequestDetailPage`'s
    approve/reject-on-error behavior) rather than silently reverting to the idle trigger button,
    so the user can retry the confirm click directly.
  - **Generate**: consumes the exact `PayrollPdfGenerationResult` shape (`payrollRunId`, `status`,
    `generatedCount`, `failedCount`, `skippedCount`). `failedCount === 0` renders a green
    (`bg-emerald-50`) full-success banner; `failedCount > 0` renders a visually and textually
    distinct amber (`bg-amber-50`) partial-success banner stating generated/failed/skipped counts
    and explicitly noting "Generate PDFs" can be used again while the run remains `CALCULATED` --
    the two banners never share a background class. Recognized codes
    (`PAYROLL_RUN_NOT_FOUND`, `PAYROLL_RUN_NOT_CALCULATED`, `PAYROLL_RUN_HAS_NO_PAYSLIPS`,
    `PAYROLL_PDF_STORAGE_UNAVAILABLE`) show the backend's safe detail; anything else shows a fixed
    fallback. Because the post-success refetch reflects the server's real status, a run that comes
    back `COMPLETED` naturally loses the "Generate PDFs" trigger through the same status-gated
    render as every other status -- no separate idempotency special-case was needed.
- **Payslip table**: `listPayrollRunPayslips(runId, page, 10)` starting at page 0, trusting backend
  order with no client-side re-sort. Previous/Next are disabled at the first/last page and while
  loading, with an `if (payslipLoading) return` early-return guard against duplicate in-flight
  requests (mirroring `PayrollRunListPage`'s pagination guard). A retry/generate refresh preserves
  the current payslip page; if the refreshed total page count makes that page invalid (e.g. the
  run shrank from 3 pages to 1), the effect detects `page >= totalPages` and corrects to the last
  valid page automatically rather than rendering an out-of-range empty page. Every monetary column
  (`basePay`, `housingAllowance`, `transportAllowance`, `grossPay`, `epfDeduction`,
  `socsoDeduction`, `eisDeduction`, `incomeTax`, `totalDeductions`, `netPay`) is formatted through
  `formatMYR`; no value is computed or edited client-side, no current employee/department/salary
  data is fetched, and `pdfPath` is never rendered or read.
- **Secure PDF download.** Availability derives only from `pdfStatus`: `GENERATED` renders an
  enabled "Download PDF" button; `PENDING`/`FAILED` render safe status text ("PDF not ready" /
  "PDF generation failed") and never call `downloadPayslipPdf` at all. The only function called is
  `downloadPayslipPdf(payslipId)` -- no `apiClient` call, no constructed path, no filename or
  employee id passed from this page, no `window.open`. In-flight state is tracked with a
  `Set<number>` of the currently-downloading payslip ids (plus a `Record<number, string>` of
  per-row errors), not a single scalar: starting row A's download adds only A's id to the set;
  starting row B's download while A is still pending adds B's id too, without ever displacing A's
  -- both rows independently show "Downloading..." and are disabled at the same time. Completing
  or failing A removes only A's id (`setDownloadingIds` always builds a new `Set` from the
  previous one rather than mutating it, and only removes an id already present), so B remains
  tracked, disabled, and showing "Downloading..." for exactly as long as its own request is
  in flight -- unaffected by A settling first or later. A same-row click while that row's id is
  still in the set is an early-return no-op, verified for both a single pending row and for a row
  whose sibling is concurrently downloading. This corrects an earlier version of this page that
  used a single `downloadingId: number | null` -- starting a second row's download overwrote that
  scalar, which mis-rendered the first row as idle/enabled while its request was still genuinely
  pending and reopened its early-return guard to a duplicate call; a RED-first test using two
  independently controlled pending Promises (one per row) reproduced this exact failure before the
  fix, and the pre-existing single-row duplicate-download test was kept unweakened alongside it.
  Every other row remains independently usable throughout -- no single-download-at-a-time policy
  was adopted. Download failures go
  through `extractBlobProblemDetails`: recognized codes (`PAYSLIP_NOT_FOUND`,
  `PAYSLIP_PDF_NOT_AVAILABLE`, `PAYSLIP_PDF_FILE_MISSING`, `PAYROLL_PDF_STORAGE_UNAVAILABLE`) show
  the parsed safe `detail` when present, else a fixed code-specific fallback; every other case
  (malformed JSON, non-JSON Blob, unrecognized code) shows "Could not download payslip PDF. Please
  try again." Since only `code`/`detail` are ever read out of the parsed Blob (verified by
  `extractBlobProblemDetails`'s own type-checked extraction), no other property a backend response
  might contain -- a path, a stack trace, PDFBox internals -- can ever reach the screen. Per-row
  errors render with `role="alert"`.
- **Accessibility/responsiveness**: semantic `h1`/`h2` headings, `dl`/`dt`/`dd` for the summary,
  semantic `<table>` with an `sr-only` caption for the payslip list, `overflow-x-auto` wrapper
  (the payslip table is wide -- 16 columns), `role="alert"` for every error, `aria-live="polite"`
  for retry success and both generate-result banners, explicit labelled confirm/cancel buttons,
  and disabled-while-in-flight state everywhere a mutation or download is pending. No new icon or
  UI-component library was added.
- **RED-first evidence.** `PayrollRunDetailPage.test.tsx` (59 tests) was run against a nonexistent
  file first (import-resolution failure), then the page was implemented and iterated to green;
  three real defects were caught this way (systematic-debugging) rather than assumed away: (1)
  React Testing Library's default text normalizer collapses the U+00A0 that `formatMYR` emits into
  a regular space before matching, so a test comparing rendered text against `formatMYR`'s raw
  (NBSP-containing) output could never match -- fixed by normalizing the expected string the same
  way in the test, not by changing `formatMYR` itself; (2) an initial "page remains usable after a
  retry error" test assumed the confirmation UI would revert to the idle trigger button on error,
  but the actual (and `LeaveRequestDetailPage`-consistent) behavior deliberately keeps the
  confirmation open so the user can retry directly -- the test was corrected to assert the confirm
  button re-enables and a second confirm click succeeds, rather than changing production code to
  match a wrong assumption; (3) a same-day correction pass found the `downloadingId: number | null`
  scalar could not represent two concurrent row downloads (see "Secure PDF download" above) -- a
  new test using two independently controlled pending Promises reproduced the exact failure
  (clicking row B's Download PDF made row A's button revert to enabled "Download PDF" while A's
  request was still genuinely pending) before the `Set<number>` fix was applied, and the
  pre-existing single-row duplicate-download test was verified to still pass unweakened alongside
  it. `App.test.tsx` gained three new `/admin/payroll/:id` routing tests (unauthenticated ->
  redirected to login, EMPLOYEE -> blocked, HR_ADMIN -> sees the loading state before the route
  existed, i.e. falls through to the 404 page), the HR_ADMIN case RED before the route was added to
  `App.tsx`; the pre-existing `/admin/payroll` list-route test was left unmodified and continues to
  pass, confirming no regression to Phase 8D-2B.
- **Incidental full-suite flakiness (diagnosed, not a regression; same root cause as Phase
  8D-2B).** A full `vitest run` can still report spurious timeouts across unrelated pre-existing
  test files when the user's own dev-server processes are running concurrently on the same
  machine. `vitest run --no-file-parallelism` (touches no other process) was used again to get a
  stable, contention-free read; no production code was changed in response to this environmental
  signal.
- Frontend: 62 new tests (291 total, up from 229) -- 59 in the new
  `PayrollRunDetailPage.test.tsx` (including the concurrent-download correction test) and 3 new
  routing tests in `App.test.tsx`. All 291 pass; 0 failures; 0 skipped. `npm run build`
  (`tsc -b && vite build`) succeeds. Backend was not touched
  (`git status` shows zero `backend/` entries), no database or service was touched, no dependency
  or lockfile changed, and no generated/downloaded PDF or temp file remains anywhere in the
  repository.
- **Explicitly unimplemented, deferred to later:** every employee-facing payroll page/route, a
  regeneration control for an already-`GENERATED` payslip, automatic repair of a payslip whose PDF
  file is missing, and any change to a payroll amount, rate, snapshot, status, or PDF path.

Phase 8 remains **In progress** overall. HR Admin now has the complete list -> detail -> retry ->
generate -> download workflow in the UI; only the employee-facing payslip pages remain before the
payroll frontend surface is complete.

### Phase 8D-2D1 — Employee self-service payslip list, route, and sidebar
Status: **Complete.** First employee-facing payroll page. A concise, read-only self-service
history: no PDF download, no deduction breakdown, and no detail route yet -- both arrive in Phase
8D-2D2.

- **`frontend/src/routes/employee/PayslipListPage.tsx`** (new) replaces the `/employee/payslips`
  `PlaceholderPage` route in `App.tsx`, inside the existing
  `<ProtectedRoute allowedRoles={['EMPLOYEE']}>` boundary. Uses only `listOwnPayslips(page, 10)`
  from `api/payroll.ts` -- the page never calls `apiClient` directly.
- **Self-service identity/security.** The page never accepts, derives, stores, or sends an
  employee id: there is no route or query parameter for one, `AuthContext` is never read for an
  id, and the JWT is never decoded on this page. `listOwnPayslips` calls exactly
  `GET /api/employees/me/payslips` with only `{ page, size }` -- identity comes solely from the
  backend's own JWT-derived authorization, confirmed by direct inspection of `api/payroll.ts`
  before writing this page (the existing employee `SalaryHistoryPage`/`getOwnSalaryHistory`
  established the same self-service pattern). No client-side ownership filtering exists because
  none is needed: the backend is the only authorization boundary, and there is nothing for this
  page to filter.
- **List presentation.** Loads page 0/size 10 on mount, trusts backend ordering with no
  client-side re-sort, and renders a semantic table (`sr-only` caption, `overflow-x-auto` wrapper)
  with columns Payroll Period (`formatPayrollPeriod(periodMonth, periodYear)` -- never derived
  from `createdAt`), Gross Pay/Total Deductions/Net Pay (all `formatMYR`), PDF Status (plain
  visible text for `PENDING`/`GENERATED`/`FAILED`, color never the only indicator), and a "View"
  link to the future `/employee/payslips/{id}` detail route (left intentionally unimplemented
  until Phase 8D-2D2 -- the link can point at it regardless). No download button, no employee
  code/name/department/job-title snapshot, no allowance/deduction breakdown, no rate snapshot, no
  `payrollRunId`, and no `pdfPath` are rendered anywhere on this page -- the full breakdown is
  deferred to the detail page by design.
- **Pagination**: Previous/Next disabled at the first/last page and while loading, with an
  `if (loading) return` early-return guard against duplicate in-flight requests (the same pattern
  as `PayrollRunListPage`/`PayrollRunDetailPage`). If a fetch reports the current page is out of
  range for the (possibly changed) dataset, the page clamps to the last valid page and re-fetches
  automatically rather than rendering an out-of-range empty page. Page size is fixed at 10; no
  page-size selector was added.
- **Error handling**: a single fixed fallback, "Could not load your payslips. Please try again.",
  is shown via `role="alert"` for every load failure -- the backend's own `detail` is deliberately
  never surfaced on this page (unlike `SalaryHistoryPage`'s convention of trusting
  `extractErrorMessage`), so a load failure can never reveal anything about the underlying data.
  `totalPages` is left untouched on error (rather than collapsed to 0) specifically so Previous/Next
  remain usable for retry based on the last known-good page count.
- **Route and sidebar.** `App.tsx`'s existing EMPLOYEE-only `/employee/payslips` `ProtectedRoute`
  boundary is preserved unchanged -- only the rendered element changed from `PlaceholderPage` to
  `PayslipListPage`. No other employee or HR Admin route was touched. `Sidebar.tsx` gains
  `{ label: 'My Payslips', to: '/employee/payslips' }` appended to `EMPLOYEE_ITEMS` (no icon,
  `HR_ADMIN_ITEMS`/the existing `Payroll` item untouched); the stale comment listing
  `/employee/payslips` as an unfinished placeholder was corrected.
- **RED-first evidence.** `PayslipListPage.test.tsx` (25 tests) was run against a nonexistent file
  first (import-resolution failure), then the page was implemented and iterated to green; one
  real defect was caught this way (systematic-debugging): the initial implementation used
  `extractErrorMessage` for load errors (following `SalaryHistoryPage`'s precedent), but the
  RED-first test explicitly asserted the backend's `detail` must never leak for this endpoint --
  the fixed-fallback requirement in this sub-phase's spec was more conservative than that
  precedent, so production code was corrected to always show the fixed string, never the
  backend's `detail`, rather than weakening the test to match the wrong assumption. Two other
  RED-first findings were purely in test design, caught before any assertion was trusted: the new
  `/employee/payslips` App-routing test initially checked only for an `h1` reading "My Payslips",
  which is also the placeholder's own title -- so it passed against the *old* placeholder without
  proving anything; the test was corrected to also assert the placeholder's specific description
  text is absent, which then failed correctly (RED) against the placeholder before the route was
  swapped. `AppLayout.test.tsx`'s employee sidebar enumeration test (extended to five items) and a
  new href/navigation test were RED before `Sidebar.tsx` was changed.
- Frontend: 30 new tests (321 total, up from 291) -- 25 in the new `PayslipListPage.test.tsx`, 3
  new routing tests in `App.test.tsx`, and 2 new sidebar tests in `AppLayout.test.tsx` (one
  existing enumeration test was extended, not counted as new). All 321 pass; 0 failures; 0
  skipped. `npm run build` (`tsc -b && vite build`) succeeds. Backend was not touched (`git status`
  shows zero `backend/` entries), no database or service was touched, no dependency or lockfile
  changed, and no generated/downloaded PDF or temp file remains anywhere in the repository.
- **Explicitly unimplemented, deferred to later:** the `/employee/payslips/{id}` detail page, PDF
  download for employees, the deduction/allowance breakdown, and any HR Admin payroll change --
  all still to come in Phase 8D-2D2 or later.

Phase 8 remains **In progress** overall. Both HR Admin and Employee now have a working payroll
list surface in the UI; only the employee payslip detail/download page remains before the payroll
frontend is feature-complete for this phase.

### Phase 8D-2D2 — Employee payslip detail and secure PDF download UI
Status: **Complete.** Final planned page of the Phase 8D frontend work: a read-only detail view
plus authenticated PDF download for the employee's own payslip. Manual browser testing (across
real roles/devices, not just the Vitest/RTL suite) remains deferred to Phase 8D-3.

- **`frontend/src/routes/employee/PayslipDetailPage.tsx`** (new) at `/employee/payslips/:id`,
  inside the existing `<ProtectedRoute allowedRoles={['EMPLOYEE']}>` boundary; the existing
  `/employee/payslips` list route and every HR Admin payroll route are untouched. The id is
  validated client-side as a positive integer (`Number.isInteger(id) && id > 0`) before any
  network call -- an invalid id (non-numeric, zero, negative, fractional) shows the fixed
  "Payslip not found." and calls neither `getPayslip` nor `downloadPayslipPdf`. A "&larr; Back to
  My Payslips" link returns to `/employee/payslips`.
- **Ownership enforcement is entirely backend-side.** This page never reads `AuthContext` for an
  identity, never decodes the JWT, and performs no client-side ownership check of any kind --
  confirmed against `PayslipController.java` before writing this page: neither
  `GET /api/payslips/{id}` nor `GET /api/payslips/{id}/pdf` carries a `@PreAuthorize`; both derive
  the caller from `@AuthenticationPrincipal AuthenticatedUser`, and a non-owning EMPLOYEE receives
  the identical `404 PAYSLIP_NOT_FOUND` as an unknown id. The page collapses both cases (and every
  invalid-id case) to the single fixed string "Payslip not found." -- the backend's own `detail`
  text is deliberately never displayed for this code, or for any other initial-load failure (which
  instead show a fixed "Could not load your payslip. Please try again."), so no error can ever
  reveal which of the two cases occurred, or any employee identity, salary value, path, or
  exception detail.
- **Detail presentation**: semantic `h1`/`h2` headings with `dl`/`dt`/`dd` groups for Summary
  (PDF Status, Created At -- period is the page's own `h1`, via `formatPayrollPeriod(periodMonth,
  periodYear)`, never derived from `createdAt`), Employee snapshot (Employee Code/Name,
  Department, Job Title), Earnings (Base Pay, Housing Allowance, Transport Allowance, Gross Pay),
  and Deductions (EPF/SOCSO/EIS Deduction, Income Tax, Total Deductions), all sourced only from
  the stored `Payslip` snapshot fields the backend already returns -- no current
  Employee/Department/SalaryStructure record is fetched. Every monetary field uses `formatMYR`;
  Net Pay is rendered from the payslip's own stored value (verified directly: a payslip whose
  `grossPay - totalDeductions` would arithmetically differ from its stored `netPay` still renders
  the stored value, proving no client-side recomputation) in a visually larger/bolder `dd` while
  remaining the same semantic definition-list value. `payrollRunId`, `employeeId`, and `pdfPath`
  are never rendered anywhere on the page, and no input/select/textarea exists.
- **PDF-status-gated availability**, derived only from `pdfStatus`: `GENERATED` renders an enabled
  "Download PDF" button; `PENDING` renders "Your payslip PDF is not ready yet." with no button;
  `FAILED` renders "Your payslip PDF could not be generated. Please contact HR." with no button.
  Neither `PENDING` nor `FAILED` ever calls `downloadPayslipPdf`.
- **Secure authenticated download** via `downloadPayslipPdf(payslipId)` only -- no `apiClient`
  call, no `window.open`, no constructed URL, no filename/path accepted from the page or user, and
  no employee/payroll-run id sent (the existing `apiClient` request interceptor supplies the
  Bearer token). Because this page shows exactly one payslip, in-flight state is a single
  `downloading` boolean (per this sub-phase's explicit instruction not to introduce the
  concurrent-row `Set` state `PayrollRunDetailPage` needed for its multi-row table) guarded by an
  early `if (downloading) return` plus a disabled button reading "Downloading..." while pending.
  Success restores "Download PDF" and clears any earlier error; failure also restores the button
  and leaves the page fully usable for another attempt -- verified directly with a failed attempt
  followed by an immediate successful retry.
- **Blob ProblemDetail handling** via `extractBlobProblemDetails`, with per-code fallbacks used
  only when the parsed `detail` is absent or blank (`detail && detail.trim() !== '' ? detail :
  fallback`, a stricter empty-string check than `PayrollRunDetailPage`'s `?? fallback`, matching
  this sub-phase's explicit "present and non-empty" requirement): `PAYSLIP_NOT_FOUND` -> "Payslip
  not found."; `PAYSLIP_PDF_NOT_AVAILABLE` -> "Your payslip PDF is not available.";
  `PAYSLIP_PDF_FILE_MISSING` -> "The payslip PDF file is unavailable. Please contact HR.";
  `PAYROLL_PDF_STORAGE_UNAVAILABLE` -> "The payslip PDF service is unavailable. Please try again
  later." Any unrecognized code, malformed JSON, non-JSON Blob, empty Blob, or unexpected
  (non-Blob) error shape shows exactly "Could not download payslip PDF. Please try again." Since
  `extractBlobProblemDetails` only ever extracts type-checked `code`/`detail` strings, no other
  property a response might carry (a path, a stack trace, PDFBox internal text) can reach the
  screen -- verified directly with a Blob containing a planted `path` field alongside a legitimate
  `detail`, confirming only the `detail` renders.
- **Accessibility/responsiveness**: `role="alert"` for every load and download error, visible text
  for all three PDF statuses (never color-only), disabled-while-downloading plus the early-return
  guard, an accessible "Download PDF"/"Downloading..." button label, and the existing responsive
  Tailwind conventions (`mx-auto max-w-2xl`, stacked `dl` grids) already used by
  `LeaveRequestDetailPage`/`PayrollRunDetailPage` -- no new icon or UI-component dependency.
- **RED-first evidence.** `PayslipDetailPage.test.tsx` (48 tests) was run against a nonexistent
  file first (import-resolution failure); the implementation then passed all 48 on the first
  attempt once written against the already-confirmed `getPayslip`/`downloadPayslipPdf`/
  `extractBlobProblemDetails`/`extractErrorCode` contracts and the `PayrollRunDetailPage`/
  `LeaveRequestDetailPage` conventions established in earlier sub-phases. `App.test.tsx` gained
  three new `/employee/payslips/:id` routing tests (unauthenticated -> redirected to login,
  HR_ADMIN -> blocked, EMPLOYEE -> sees the real page's loading state rather than the 404 page),
  all three RED (falling through to the catch-all `NotFoundPage`) before the route was added to
  `App.tsx`; the pre-existing `/employee/payslips` list-route test and every HR Admin payroll
  routing test were left unmodified and continue to pass, confirming no regression.
- Frontend: 51 new tests (372 total, up from 321) -- 48 in the new `PayslipDetailPage.test.tsx`
  and 3 new routing tests in `App.test.tsx`. All 372 pass; 0 failures; 0 skipped. `npm run build`
  (`tsc -b && vite build`) succeeds. Backend was not touched (`git status` shows zero `backend/`
  entries), no database or service was touched, no dependency or lockfile changed, and no
  generated/downloaded PDF or temp file remains anywhere in the repository.
- **Explicitly unimplemented, deferred to later:** manual cross-browser/cross-device testing of
  the full payroll frontend surface (Phase 8D-3), any HR Admin payroll change, and any
  regeneration/automatic-repair control for a payslip's PDF.

Phase 8 remains **In progress** overall. Both HR Admin and Employee now have a complete
list/detail/action UI surface for payroll; only manual end-to-end browser verification (Phase
8D-3) remains before this phase can be marked fully complete.

### Phase 8D-3 — Manual end-to-end browser verification of the payroll frontend
Status: **Complete.** Full manual browser verification of the Phase 8D-2A2 through 8D-2D2 payroll
frontend, performed by the user across real roles, browsers, and devices against a live
backend/frontend/PostgreSQL stack. All fixtures were temporary (`MANUAL_8D3_20260918`-prefixed
users, employees, salary structures, payroll runs, and their auto-generated payslips), tracked
in an external cleanup ledger outside the repository, and fully removed after verification; no
production source, test, migration, or configuration file was touched during this phase.

The user manually confirmed **PASS** for:

- Logged-out route protection (unauthenticated access redirected to login for every payroll
  route, admin and employee).
- HR_ADMIN/EMPLOYEE role-authorization boundaries (each role blocked from the other's payroll
  routes).
- Admin payroll-run list: filtering, year selection, and pagination behavior.
- Create-run validation, including duplicate-period safe error handling (re-submitting an
  existing period produced the expected conflict error with no duplicate row created).
- FAILED run Retry Payroll: confirmation flow and successful recovery once salary coverage was
  repaired.
- CALCULATED run Generate PDFs: successful full-run PDF generation.
- Controlled external-storage failure (via a reversible, OS-level, deny-only ACL change on the
  dedicated external PDF storage directory -- never via source, config, or database edits): the
  partial-warning presentation displayed, generated/failed/skipped counts displayed, affected
  rows showed the PDF-generation failure, and Generate PDFs remained available for retry.
- Storage-permission restoration and successful regeneration: after the deny rule was removed,
  the same run reached `COMPLETED` with both payslips `GENERATED` and real PDF files on disk
  (verified directly: run 2006 reached `COMPLETED` at version 2, payslips 1140/1141 both reached
  `GENERATED` with non-empty `pdf_path` values, and both PDF files existed inside the dedicated
  external storage directory with non-zero size).
- Admin PDF download, opening, and content verification (downloaded PDF content matched the
  expected employee/period).
- Employee A self-service payslip list, detail, and download.
- Employee B self-service payslip list, detail, and download.
- Unknown and non-owned payslip ids showing the identical fixed "Payslip not found." message
  (no distinguishing information leaked between the two cases).
- Invalid payslip ids (non-numeric, zero, negative, fractional) handled client-side with no
  network call.
- Responsive behavior at desktop, tablet, and mobile widths, and at 200% browser zoom.
- Keyboard/accessibility smoke testing.
- Chrome and Firefox smoke testing.
- No filesystem path, `pdfPath`, stack trace, SQL text, employee-ownership information, or other
  internal exception detail was observed in any UI-facing error at any point.

Qualifications recorded accurately, per direct confirmation against the implementation rather
than assumption:

- `PROCESSING` was not manually observable, because payroll run creation and retry are
  synchronous by design (the run reaches a terminal `CALCULATED`/`FAILED` state within the same
  request/response cycle) -- this was confirmed from the implementation, not forced via a
  database edit.
- The concurrent two-row "Downloading..." state on the admin payslip table is recorded as
  **AUTOMATED-COVERAGE-ONLY**: the user did not explicitly observe both download buttons pending
  simultaneously in the browser, but this behavior is covered by the existing controlled-Promise
  test for `PayrollRunDetailPage`'s per-row `Set<number>` download-state tracking (Phase 8D-2C).
- Safari is unavailable on the Windows manual-test environment and is recorded as **not
  applicable**, not failed.
- The automated baseline (372 frontend tests, 0 failures, 0 skipped; `npm run build` succeeded)
  was already verified before this manual-testing phase began and was not rerun as part of it.
- **`updated_at` timestamp observation**: during manual verification, `payroll_runs.updated_at`
  and `payslips.updated_at` were observed to remain equal to each row's `created_at` even after a
  genuine state change (confirmed via the independent `version` column incrementing). Root-cause
  investigation (migrations, entity mappings, JPA auditing configuration, the PDF-generation
  service, and comparable entities across the whole schema) confirmed this is **expected,
  insert-only behavior applied consistently project-wide**: every audited entity maps
  `updated_at` as `insertable = false, updatable = false`, with no JPA auditing infrastructure
  anywhere in the project: the `@Version` column is the intended state-change indicator instead.
  This has no impact on payroll or PDF correctness. The admin "Updated" label on
  `PayrollRunDetailPage` displaying this permanently creation-stable value is a low-priority
  UI-labeling backlog note, not a Phase 8D-3 blocker.

All temporary fixtures, their auto-generated payslips, and the dedicated external PDF-storage
directory used for this phase have been fully removed; audit-log rows generated by this testing
were preserved (append-only) per the same authorization that governed fixture creation.

**Phase 8D-3: Complete.**

**Phase 8: Complete.** All sub-phases (8A through 8D-3) are done: payroll schema and calculation
(8A-2), transactional run creation/calculation (8B-1), HR Admin creation/read APIs (8B-2A),
failed-run retry and employee self-service read APIs (8B-2B), payslip PDF rendering (8C-2A), PDF
generation orchestration and secure download (8C-2B1/8C-2B2), the full HR Admin and Employee
payroll frontend (8D-2A1 through 8D-2D2), and manual end-to-end browser verification (8D-3).

## Phase 9 — Employee self-service attendance
Phase 9 is employee self-service attendance: clock-in, clock-out, and personal attendance
history, for the EMPLOYEE role only. HR Admin attendance management is not part of Phase 9.
Phase 10 (HR Admin reports) is a separate, later phase.

### Phase 9A — Time strategy, entity, repository foundation
Status: **Complete.** Foundation only -- no service, controller, DTO, exception, security
annotation, audit event, or frontend code exists yet. The existing V7 `attendance` table (from
early in the project, predating payroll) is reused completely unchanged; the audit proved every
Phase 9A/9B requirement is satisfiable against it as-is, so no new migration was added.

- **Explicit business-timezone `Clock` bean** -- `backend/src/main/java/.../config/TimeConfig.java`
  provides `Clock.system(ZoneId.of("Asia/Kuala_Lumpur"))` as an ordinary (non-`@Primary`) bean, so
  a later test can override it with `Clock.fixed(...)` the same way `PayrollPdfServiceTest`
  overrides `PayslipPdfRenderer`. This matches the project's existing Asia/Kuala_Lumpur convention
  (`application.yml`'s `spring.jackson.time-zone` and `HrPayrollBackendApplication`'s
  `TimeZone.setDefault`) rather than introducing a new timezone decision, and is deliberately not
  injected into any existing service -- leave/payroll/salary/employee/auth/PDF code is untouched.
- **`Attendance` entity** (`backend/src/main/java/.../attendance/Attendance.java`) maps V7 exactly:
  `id`, `employee` (`@ManyToOne` to the existing `Employee` entity via `employee_id`), `workDate`,
  `clockInAt` (not-null), `clockOutAt` (nullable). No `createdAt`/`updatedAt`/`version` field --
  V7 has none of those columns, unlike most other tables in this project. No setters; clock-in/
  clock-out lifecycle methods belong to the future Phase 9B service.
- **`AttendanceRepository`** provides only the two foundation queries later phases need:
  `findByEmployeeIdAndWorkDate` (single lookup) and `findByEmployeeIdOrderByWorkDateDescIdDesc`
  (paginated history, newest work date first with `id` as a deterministic tie-breaker baked into
  the method name -- a deliberate deviation from `PayslipRepository`/`LeaveRequestRepository`'s
  plain `findByEmployeeId` + caller-supplied `Sort`, since attendance history has exactly one
  correct order and no reason for a future caller to override it). No conditional clock-out
  update yet -- that belongs to Phase 9B alongside its own concurrency tests.
- **Database-enforced invariants confirmed by real-PostgreSQL tests, not assumed**:
  `uq_attendance_employee_work_date` rejects a duplicate employee/work-date pair (asserted by
  exact constraint name), `chk_attendance_clock_out_after_clock_in` rejects a clock-out before
  clock-in (asserted by exact constraint name, and a clock-out equal to clock-in is accepted),
  `fk_attendance_employee` rejects both an unknown employee id on insert and deleting an employee
  who still has attendance rows (`ON DELETE RESTRICT`).
- **RED-first evidence.** `AttendanceRepositoryTest` and `TimeConfigTest` were run first against
  the not-yet-existing `Attendance`/`AttendanceRepository`/`TimeConfig` classes (compile failure);
  the entity/repository/config were then implemented to make all 13 focused tests pass on the
  first attempt. History/pagination/isolation tests use `entityManager.clear()` before re-querying
  to prove results come from PostgreSQL, not the first-level cache.
- Backend: 13 new tests (449 total, up from 436) -- 11 in `AttendanceRepositoryTest`, 2 in
  `TimeConfigTest`. `./mvnw.cmd clean verify` passes (449 run, 0 failures, 0 errors, 3 skipped --
  the same 3 pre-existing symlink-unsupported-OS skips as the Phase 8 baseline, none added or
  removed). No migration added (Flyway remains at schema version 13); no dependency changed;
  frontend untouched (`git status` shows zero `frontend/` entries); the local development database
  has zero leftover Phase 9 fixture rows after the test run's own cleanup.
- **Explicitly unimplemented, deferred to Phase 9B and later:** `AttendanceService`, clock-in/
  clock-out business logic (including the conditional clock-out update and its concurrency
  tests), controllers/endpoints, DTOs, exceptions/exception handlers, security annotations, audit
  events, the frontend API/page/route/sidebar, HR Admin attendance, and Phase 10 reports.

Phase 9 remains **In progress** overall; only the non-HTTP entity/repository/time foundation
exists so far.

### Phase 9B-1 — Attendance service, concurrency, DTO, domain exception, and audit foundation
Status: **Complete.** Service/domain layer only -- no controller, `@PreAuthorize`, HTTP mapping,
`AttendanceExceptionHandler`, or frontend code exists yet. Built entirely on the unchanged Phase
9A foundation (V7, `Attendance`, `AttendanceRepository`, the Asia/Kuala_Lumpur `Clock` bean).

- **`AttendanceService`** (`clockIn`, `clockOut`, `getTodayStatus`, `listHistory`) takes
  `employeeId`/`actorUserId` as trusted internal parameters -- Phase 9B-2's controller will derive
  both from `AuthenticatedUser`, never from a request body/path/query field. Every business date
  (`LocalDate.now(clock)`) and event timestamp (`LocalDateTime.now(clock)`) comes from the
  injected `Clock`; no method anywhere in the service calls the no-arg `LocalDate.now()`/
  `LocalDateTime.now()`, and no other module's timezone behavior was touched.
- **Clock-in**: inserts one row and calls `saveAndFlush` inside the transaction so
  `uq_attendance_employee_work_date` is evaluated before the method returns -- the database
  constraint remains the sole correctness mechanism against a duplicate or concurrent clock-in
  (no pessimistic lock, no `@Version`, no check-then-insert). The resulting
  `DataIntegrityViolationException` deliberately propagates uncaught in this sub-phase; Phase
  9B-2 will extend `ApiExceptionHandler`'s existing constraint-name maps (the same mechanism
  already used for `uq_departments_name`/`uq_employees_employee_code`) to translate it into a
  stable `ATTENDANCE_ALREADY_CLOCKED_IN` 409. The audit event is recorded only after the insert
  has survived the flush, so a losing duplicate/concurrent attempt never gets a success audit.
- **Clock-out**: looks up only today's business-date row (never an arbitrary older open record,
  so a stale prior-day row is never touched and never blocks today's clock-in), then performs a
  new conditional `AttendanceRepository.updateClockOutIfOpen` (`@Modifying`, JPQL
  `UPDATE ... WHERE id = :id AND clockOutAt IS NULL`, `clearAutomatically`/`flushAutomatically`
  so a subsequent read is never served from a stale first-level-cache entry). PostgreSQL's own
  re-evaluation of that `WHERE` clause against the current committed row at write time is the
  sole correctness mechanism against a concurrent double clock-out: of two concurrent calls for
  the same row, exactly one affects a row and the other affects none -- proven directly (see
  concurrency evidence below), not assumed. `AttendanceNoOpenRecordException`/
  `AttendanceAlreadyClockedOutException` (both new, no HTTP handler yet) cover "no row for today"
  and "already closed" (including the losing side of the race), with exact safe messages "You
  have not clocked in today."/"You have already clocked out today."
- **Today-status**: always one explicit `AttendanceTodayStatus` (`NOT_CLOCKED_IN`/`CLOCKED_IN`/
  `CLOCKED_OUT`) plus a nullable `AttendanceResponse`, from a dedicated `getTodayStatus` query --
  never derived from page 1 of history, which would be ambiguous the moment a stale prior-day
  open row exists.
- **History**: `listHistory` returns `PagedModel<AttendanceResponse>` (matching
  `PayrollQueryService.listOwnPayslips`'s existing service-layer convention) via the Phase 9A
  `findByEmployeeIdOrderByWorkDateDescIdDesc` query -- verified to return only the requested
  employee's own rows, in newest-work-date-first / id-DESC-tiebreaker order, with correct
  pagination metadata.
- **DTOs**: `AttendanceResponse` (`id`, `workDate`, `clockInAt`, `clockOutAt` only -- no
  `employeeId`, no `Employee`, no user id, no department/salary/payroll data; verified directly by
  reflecting over the record's components) and `AttendanceTodayResponse` (`state`, nullable
  `attendance`).
- **Audit**: reuses the existing `AuditService` unchanged. `ATTENDANCE_CLOCKED_IN`/
  `ATTENDANCE_CLOCKED_OUT` actions, entity name `ATTENDANCE`, with only `workDate`/`clockInAt` (or
  `clockOutAt`) as safe string-valued details -- no salary/payroll data, JWT/token, password,
  IP/device data, employee name, or raw exception/SQL/constraint text ever recorded.
- **Genuine concurrency evidence**, mirroring `PayrollRunConcurrencyTest`/
  `SalaryStructureConcurrencyTest`'s established real-thread/real-PostgreSQL style exactly (no
  mocks, no H2, no synchronized blocks, no sleeps): two real concurrent clock-ins for the same
  employee/business-date resolve to exactly one success and one `DataIntegrityViolationException`,
  exactly one attendance row, and exactly one clock-in audit row; two real concurrent clock-outs
  for the same open row resolve to exactly one success and one `AttendanceAlreadyClockedOutException`,
  exactly one closed row, and exactly one clock-out audit row.
- **Boundary/host-independence evidence**: a fixed test `Clock` proves the business date rolls at
  Asia/Kuala_Lumpur midnight (23:59:59 vs. 00:00:01), and a dedicated test temporarily sets the
  JVM default timezone to Asia/Tokyo while the injected Clock stays fixed in Kuala Lumpur time,
  confirming the computed business date still matches Kuala Lumpur, not the host default.
- **RED-first evidence.** `AttendanceServiceTest`/`AttendanceConcurrencyTest` were run first
  against the not-yet-existing `AttendanceService`/DTOs/exceptions (compile failure), then
  implemented to green. One genuine root-cause diagnosis along the way: the first concurrency-test
  run failed application-context startup with `BeanDefinitionOverrideException` because the test's
  `@Primary` `Clock` override reused the same bean method name as `TimeConfig`'s real bean;
  renaming the test bean method (mirroring `PayrollPdfServiceTest`'s `testPayslipPdfRenderer()`
  convention exactly) fixed it without touching production code. A second, unrelated issue was a
  test-isolation bug (an audit-count assertion read the whole `audit_logs` table instead of a
  before/after delta, so an earlier test's real audit rows inflated it) -- fixed in the test only.
- Backend: 27 new tests (476 total, up from 449) -- 25 in `AttendanceServiceTest`, 2 in
  `AttendanceConcurrencyTest`. `./mvnw.cmd clean verify` passes (476 run, 0 failures, 0 errors, 3
  skipped -- the same 3 pre-existing symlink-unsupported-OS skips as the Phase 8/9A baseline, none
  added or removed). No migration added (Flyway remains at schema version 13); no dependency
  changed; frontend untouched; the local development database has zero leftover Phase 9 fixture
  rows after the test run's own cleanup.
- **Explicitly unimplemented, deferred to Phase 9B-2 and later:** controller, HTTP endpoints,
  `@PreAuthorize`, `AttendanceExceptionHandler`, the `ApiExceptionHandler` constraint-map
  extension for `ATTENDANCE_ALREADY_CLOCKED_IN`, the frontend API/page/route/sidebar, HR Admin
  attendance, manual correction/backdating, and Phase 10 reports.

Phase 9 remains **In progress** overall; the service/domain layer is complete but nothing is
reachable over HTTP yet.

### Phase 9B-2 — Attendance HTTP API, security, and stable error handling
Status: **Complete.** HTTP layer only, over the existing Phase 9B-1 service/DTOs -- no new service
abstraction, request DTO, migration, dependency, or frontend code. HR Admin attendance still does
not exist, and no attendance correction/backdating endpoint was added.

- **`AttendanceSelfServiceController`** at `/api/employees/me/attendance`,
  `@PreAuthorize("hasRole('EMPLOYEE')")` at the class level, mirroring
  `PayslipSelfServiceController`/`LeaveRequestSelfServiceController`'s exact pattern:
  - `POST /clock-in` -- no request body, 201 with `AttendanceResponse`.
  - `POST /clock-out` -- no request body, 200 with `AttendanceResponse`.
  - `GET /today` -- 200 with `AttendanceTodayResponse` for all three states; "no record yet" is
    `NOT_CLOCKED_IN` with a null `attendance`, never an HTTP error.
  - `GET /` (history) -- `PagedModel<AttendanceResponse>`, defaults to page 0 / size 20.
  - Every endpoint derives `employeeId`/`actorUserId` only from `@AuthenticationPrincipal
    AuthenticatedUser` via the same `requireEmployeeId(principal)` helper duplicated verbatim
    across every self-service controller in this project (no generic ownership helper introduced,
    matching that established convention exactly) -- no `{employeeId}` path/query/body field
    exists anywhere, the JWT is never decoded manually, and an unlinked-employee principal gets
    the identical safe `Employee profile not found` 404 as every other self-service endpoint.
- **Fixed history ordering, not client-overridable.** `history` accepts `@PageableDefault(size =
  20) Pageable pageable` (for its established page/size defaulting and negative-input safety
  behavior, matching every other paginated endpoint) but immediately discards its `Sort` --
  rebuilding a plain `PageRequest.of(pageNumber, pageSize)` before calling the service -- so a
  client-supplied `sort` query parameter has no code path to reach the query at all. The Phase 9A
  repository's baked-in `workDate DESC, id DESC` ordering remains the sole, non-overridable order;
  proven directly by a test that supplies `sort=workDate,asc` and asserts the response is still
  newest-first.
- **Duplicate clock-in**: `ApiExceptionHandler`'s existing constraint-name map (already used for
  `uq_departments_name`/`uq_employees_employee_code`/`uq_employees_ic_number`) gained one entry --
  `uq_attendance_employee_work_date` -> `ATTENDANCE_ALREADY_CLOCKED_IN` (409, "You have already
  clocked in today.") -- reusing the exact existing detection mechanism, never
  `ex.getMessage()`, never a second error-response shape.
- **New `AttendanceExceptionHandler`**, mirroring `LeaveExceptionHandler`/`SalaryExceptionHandler`'s
  flat `ProblemDetail`-building style exactly: `AttendanceNoOpenRecordException` -> 404
  `ATTENDANCE_NO_OPEN_RECORD` ("You have not clocked in today."), `AttendanceAlreadyClockedOutException`
  -> 409 `ATTENDANCE_ALREADY_CLOCKED_OUT` ("You have already clocked out today.") -- both using the
  exception's own already-safe, non-interpolated message.
- **Ownership isolation and redaction**, verified directly: employee A's clock-in/history/today
  are invisible to employee B's token (a `POST /clock-out` from B against A's open row correctly
  fails with `ATTENDANCE_NO_OPEN_RECORD`, never touching A's row); every response (`clock-in`,
  `today`, `history`) was checked via `jsonPath` to contain none of `employeeId`, `userId`,
  `employee`, `department`, `salary`, or `payroll`; conflict/error responses were checked to
  contain no SQL/constraint-name text.
- **RED-first evidence.** Because the new tests only hit HTTP paths (no test references a
  not-yet-existing controller class by name), RED was confirmed by running the new
  `AttendanceSelfServiceControllerTest` suite against the unmodified Phase 9B-1 code: 24 of 29
  tests failed with `404`/routing errors (the 5 that already passed -- four unauthenticated-401
  checks plus the id-in-path routing check -- are pre-existing Spring Security perimeter behavior
  that does not depend on the controller's existence, not false-positive RED). The controller,
  exception handler, and constraint-map entry were then implemented together and all 29 tests
  passed on the first run.
- Backend: 29 new tests (505 total, up from 476), all in `AttendanceSelfServiceControllerTest`.
  `./mvnw.cmd clean verify` passes (505 run, 0 failures, 0 errors, 3 skipped -- the same 3
  pre-existing symlink-unsupported-OS skips as the Phase 8/9A/9B-1 baseline, none added or
  removed). No migration added (Flyway remains at schema version 13); no dependency changed;
  frontend untouched; the local development database has zero leftover Phase 9 fixture rows after
  the test run's own cleanup.
- **Explicitly unimplemented, deferred to later:** the frontend API/page/route/sidebar (Phase
  9C/9D), focused manual browser verification (Phase 9E, after the frontend exists), HR Admin
  attendance, manual correction/backdating, and Phase 10 reports.

Phase 9 remains **In progress** overall; the full backend (entity through HTTP API) is complete,
but no frontend surface exists yet and no manual browser testing has been performed.

### Phase 9C — Frontend attendance API, types, and formatting foundation
Status: **Complete.** Data/utility layer only, built on top of the Phase 9B-2 backend contract --
no page, route, sidebar entry, component, hook, or state-management abstraction exists yet.
`App.tsx`, `App.test.tsx`, `Sidebar.tsx`, and every `AppLayout` test are untouched.

- **`frontend/src/api/attendance.ts`** -- typed client for all four Phase 9B-2 endpoints:
  - `clockIn()` -> `POST /api/employees/me/attendance/clock-in`, no request body, returns
    `AttendanceRecord`.
  - `clockOut()` -> `POST /api/employees/me/attendance/clock-out`, no request body, returns
    `AttendanceRecord`.
  - `getTodayAttendance()` -> `GET /api/employees/me/attendance/today`, returns
    `AttendanceTodayResponse`, preserving `attendance: null` for `NOT_CLOCKED_IN` exactly (verified
    directly, not just typed).
  - `listOwnAttendance(page = 0, size = 20)` -> `GET /api/employees/me/attendance` with `{ page,
    size }` as the only query parameters -- no `sort`, no `employeeId`. The backend's fixed
    `workDate DESC, id DESC` ordering is trusted as-is; this module never reorders, filters, or
    mutates the returned page.
  - Every function uses only the existing shared `apiClient` (Bearer token attached by its
    existing request interceptor) -- no direct `axios` call, no manual JWT decoding, and no
    employee or actor id appears in any request path/query/body anywhere in this module.
  - Errors are never caught or rewritten here -- a rejected request propagates through the
    existing `apiClient` boundary unchanged, matching every other API module's convention.
- **Types**: `AttendanceTodayStatus` as the exact union `'NOT_CLOCKED_IN' | 'CLOCKED_IN' |
  'CLOCKED_OUT'` (not a broad `string`); `AttendanceRecord` (`id`, `workDate`, `clockInAt`,
  `clockOutAt` only -- named "Record" rather than "Response" to match this project's established
  convention of dropping the backend DTO's "Response" suffix on the frontend, e.g. `PayslipResponse`
  -> `Payslip`); `AttendanceTodayResponse` (`state`, `attendance`); a `PagedResponse<T>` matching
  the exact shape already used in `payroll.ts`/`leave.ts`, duplicated locally per this codebase's
  existing established per-module pagination-type convention rather than imported cross-module.
  None of `employeeId`, `userId`, `employee`, `department`, `salary`, `payroll`, or any editable
  field was added to any type.
- **`frontend/src/utils/attendanceDateTime.ts`** -- `formatAttendanceDate`/`formatAttendanceTime`,
  following `formatPayrollPeriod`'s existing "never construct a `Date`" convention exactly (a
  regex-based string parse instead), since `Date`-based parsing of a bare `LocalDate`/`LocalDateTime`
  string is timezone-sensitive and could silently corrupt the backend's Asia/Kuala_Lumpur business
  date/time. Chosen display format, recorded exactly: dates render as `"10 March 2026"` (day,
  full month name, year); times render 12-hour with an AM/PM suffix and always drop
  seconds/fractional seconds (e.g. `"9:00 AM"`, `"2:30 PM"`, `"12:00 AM"` for midnight) -- shown to
  minute precision only. A `null` `clockOutAt` and any malformed/invalid/out-of-range input (bad
  month, bad hour, missing time portion, empty string) all render the same safe placeholder,
  `"—"` (an em dash), matching the placeholder already used elsewhere in this project (e.g.
  `EmployeeDetailPage`'s `phoneNumber ?? '—'`) -- never `Invalid Date`, `NaN`, or a thrown
  exception. Verified directly that changing the test process's `TZ` (via `vi.stubEnv`) between
  two timezones on opposite sides of the globe never changes either formatter's output, proving
  the host/browser timezone cannot influence the result.
- **RED-first evidence.** `attendance.test.ts`/`attendanceDateTime.test.ts` were run first against
  the not-yet-existing `attendance.ts`/`attendanceDateTime.ts` modules (Vite import-resolution
  failure); the implementation then passed all 34 tests on the first attempt. One genuine
  root-cause diagnosis along the way: `npm run build` initially failed `tsc -b` with "Cannot find
  name 'process'" in the new timezone test, because this frontend's `tsconfig` deliberately has no
  Node type definitions (a pure browser app) -- fixed by using Vitest's own typed `vi.stubEnv`/
  `vi.unstubAllEnvs()` instead of touching `process.env` directly, in the test file only; no
  dependency was added and no tsconfig was changed.
- Frontend: 34 new tests (406 total, up from 372) -- 15 in `attendance.test.ts`, 19 in
  `attendanceDateTime.test.ts`. All 406 pass across 24 test files; 0 failures; 0 skipped. `npm run
  build` (`tsc -b && vite build`) succeeds with no warnings. Backend was not touched (`git status`
  shows zero `backend/` entries), no migration or dependency/lockfile changed, and no route, page,
  component, or sidebar entry was added.
- **Explicitly unimplemented, deferred to Phase 9D and later:** the `AttendancePage` component,
  its route in `App.tsx`, the `Sidebar.tsx` EMPLOYEE entry, and any other UI. Focused manual
  browser verification remains deferred until that UI exists.

Phase 9 remains **In progress** overall; the backend and the frontend data/formatting foundation
are both complete, but no employee-facing attendance page exists yet.

### Phase 9D — Employee attendance page, protected route, and sidebar entry
Status: **Complete.** UI only, built entirely on the existing Phase 9C `attendance`/
`attendanceDateTime` modules -- no new API function, type, or formatting behavior was added, and
neither module was modified. HR Admin attendance, correction/backdating, and Phase 10 reports
remain unimplemented.

- **Route**: `/employee/attendance` now renders `AttendancePage` (heading `My Attendance`) inside
  the existing `<ProtectedRoute allowedRoles={['EMPLOYEE']}>`, replacing the previous
  `PlaceholderPage`. No new route, no HR Admin route, and no employee-id path/query parameter was
  added.
- **Sidebar**: exactly one new EMPLOYEE-only item, `{ label: 'My Attendance', to:
  '/employee/attendance' }`, appended after the five existing employee items (order preserved).
  The stale "unfinished placeholder" comment's attendance reference was removed (the comment now
  only calls out `/admin/reports`, the one remaining unfinished placeholder). No HR Admin item was
  added.
- **Today status is always backend-authoritative** -- read only from `getTodayAttendance()`,
  never derived from history (a stale prior-day open row would make that ambiguous). Three
  explicit states, each communicated through visible text (never color alone): `NOT_CLOCKED_IN`
  ("Not clocked in today", only a Clock In button), `CLOCKED_IN` ("Clocked in" plus formatted
  date/clock-in time and the `—` placeholder for the still-open clock-out, only a Clock Out
  button), `CLOCKED_OUT` ("Clocked out" plus both formatted times, no active mutation button).
  Attendance id, employee id, user id, actor id, raw ISO timestamp strings, department, and
  salary/payroll data are never rendered anywhere on this page.
- **Clock-in/clock-out confirmation flow**, mirroring `PayrollRunDetailPage`'s established
  `MutationMode` (`'idle' | 'clockin-confirm' | 'clockout-confirm'`) pattern exactly rather than
  its multi-row `Set` pattern, since this page only ever has one current-day action in flight:
  clicking Clock In/Clock Out opens an inline confirmation ("Clock in now?"/"Clock out now?")
  with `Confirm clock in`/`Confirm clock out` and `Cancel` buttons; Cancel makes zero API calls;
  each confirm button calls `clockIn()`/`clockOut()` with no argument and is guarded by an
  early-return boolean check (`if (clockingIn) return`/`if (clockingOut) return`) that also
  disables both buttons and shows `Clocking in...`/`Clocking out...` while pending -- verified
  directly that two rapid confirm clicks while pending produce exactly one request. Only the
  action valid for the current backend state is ever rendered.
- **Authoritative refresh after mutation**: success shows fixed visible feedback ("Clocked in
  successfully."/"Clocked out successfully."), closes the confirmation, and increments a
  `refreshToken` that both the today-status and history effects depend on, so both sections
  re-fetch from the backend rather than the page guessing the new state locally; the current
  history page is preserved across this refresh when it remains valid, and clamps to the last
  valid page (reusing `PayslipListPage`'s established out-of-range clamping logic exactly) if
  refreshed metadata reports it is not. Failure restores the button/controls, keeps the page
  retryable, and never clears already-loaded today/history data.
- **Stable recognized-error mapping**, using `extractErrorCode` from the existing `api/errors.ts`
  boundary: `ATTENDANCE_ALREADY_CLOCKED_IN` / `ATTENDANCE_NO_OPEN_RECORD` /
  `ATTENDANCE_ALREADY_CLOCKED_OUT` each display the backend's own `detail` when present and
  non-blank, otherwise the matching fixed fallback text. Any unrecognized code, missing code, or
  malformed error renders exactly `"Could not update attendance. Please try again."` -- verified
  directly with a planted unknown code carrying a raw internal-looking detail string, confirming
  only the fixed message renders.
- **Independent load sections**: today-status and history load via two separate effects/error
  states, each with its own fixed fallback (`"Could not load today's attendance. Please try
  again."` / `"Could not load attendance history. Please try again."`) -- verified directly that
  a today-load failure does not prevent history from rendering, and vice versa.
- **History table**: semantic `<table>` with an `sr-only` caption, Date/Clock In/Clock Out/Status
  columns via `formatAttendanceDate`/`formatAttendanceTime`, `Status` as visible text (`Open` for
  a null `clockOutAt`, `Completed` otherwise), rows rendered in exactly the backend-provided order
  (verified directly with an out-of-order fixture), inside an `overflow-x-auto` container, with a
  `"No attendance history yet."` empty state. No attendance id, employee id, department, or
  salary/payroll data is ever rendered.
- **Pagination**: fixed page size 20, `Previous`/`Page X of Y`/`Next`, Previous disabled on the
  first page, Next disabled on the last page, both disabled while loading, duplicate in-flight
  page requests blocked -- all mirroring `PayslipListPage`'s established pattern exactly. Only
  `page`/`size` are ever sent; no `sort`, no employee id.
- **Accessibility**: semantic `h1`("My Attendance")/`h2` section headings, `role="alert"` for
  every load/action error, visible (never color-only) status and success text, ordinary button
  semantics for every confirmation control (no custom widget), keyboard-operable throughout,
  existing Tailwind conventions reused, no new icon/UI-component dependency.
- **RED-first evidence, by group.** Page: `AttendancePage.test.tsx` (47 tests) was run first
  against the not-yet-existing `AttendancePage` (Vite import-resolution failure), then the
  component was implemented and all 47 passed on the first attempt. Routing: 3 new tests were
  added to `App.test.tsx` and run against the still-placeholder-backed route first -- the
  "real page, not the old placeholder" assertion genuinely failed (the placeholder's `Clock
  in/out and view history.` text was still present), confirming real RED before `App.tsx` was
  changed; the other two (unauthenticated-401/HR_ADMIN-403 style) redirect/block assertions don't
  depend on which component renders, so they passed immediately, which is expected, not a
  false-positive RED. Sidebar: 2 new tests plus one updated five-to-six-item enumeration
  assertion were added to `AppLayout.test.tsx` and run against the unmodified `Sidebar.tsx` first
  -- all 3 genuinely failed (no `My Attendance` link existed yet), confirming real RED before
  `Sidebar.tsx` was changed.
- Frontend: 52 new tests (458 total, up from 406) -- 47 in the new `AttendancePage.test.tsx`, 3
  new routing tests in `App.test.tsx` (41 total, up from 38), 2 new sidebar tests in
  `AppLayout.test.tsx` (24 total, up from 22; the existing five-item enumeration test was updated
  to six items, not counted as a new test). All 458 pass across 25 test files; 0 failures; 0
  skipped. `npm run build` (`tsc -b && vite build`) succeeds with no warnings. Backend was not
  touched (`git status` shows zero `backend/` entries), no migration or dependency/lockfile
  changed, and the Phase 9C `attendance.ts`/`attendanceDateTime.ts` modules were not modified.
- **Explicitly unimplemented, deferred to Phase 9E:** focused manual cross-browser/cross-device
  verification of the real attendance UI, now that it exists for the first time.

### Phase 9E — Employee attendance manual browser verification
Status: **Complete.** Full manual browser verification of the Phase 9A through 9D employee
attendance feature, performed against a live backend/frontend/PostgreSQL stack with temporary
fixtures (`MANUAL_9E_20260923`-prefixed users, employees, and attendance rows), tracked in an
external cleanup ledger outside the repository, and fully removed after verification.

- **Authorization**: logged-out route protection passed; HR_ADMIN had no `My Attendance` sidebar
  item and direct navigation to `/employee/attendance` was blocked; no employee attendance data
  was exposed to HR_ADMIN.
- **Employee attendance**: sidebar/route access passed; the initial `NOT_CLOCKED_IN` state passed;
  history formatting, newest-first ordering, pagination (across two pages), and the stale
  prior-day open-row behavior (2026-09-01 remaining open without affecting today's state) all
  passed; confirmation/cancel/pending/success flows all passed; Employee A's clock-in and
  clock-out persisted correctly through refresh and logout/login, reaching a final `COMPLETED`
  state with no duplicate current-day row.
- **Ownership isolation**: Employee B saw only their own records and never Employee A's
  identity/history. Employee B also performed one valid clock-in during testing (producing a
  temporary attendance row deliberately left open) -- confirmed as an additional valid test
  action, not an application defect.
- **Responsive/accessibility/browser verification**: desktop (1440x900), tablet (768x1024),
  mobile (390x844), and 200% zoom all passed; the history table scrolled within its own
  container with no whole-page horizontal overflow; keyboard/focus/confirmation accessibility,
  semantic headings/table structure, and text-based (never color-only) status/error presentation
  all passed; Chrome and Firefox both passed.
- **Network/security checks**: NETWORK-01 through NETWORK-04 are recorded as
  **SOURCE-AND-AUTOMATED-COVERAGE** (resolved by direct inspection of the committed
  `attendance.ts` source, its existing Phase 9C test assertions, and the backend controller's
  identity derivation), not manual browser observation -- confirming the today/history/clock-in/
  clock-out requests carry no client-supplied employee/actor/user identity, history sends only
  `page`/`size`, both mutation POSTs carry no request body, and the backend derives identity
  solely from the authenticated principal.
- **Qualifications**: duplicate-click concurrency protection remains
  **AUTOMATED-COVERAGE-ONLY**; Asia/Kuala_Lumpur midnight rollover remains
  **AUTOMATED-COVERAGE-ONLY**; unknown/malformed-error redaction remains
  **AUTOMATED-COVERAGE-ONLY**; Safari is unavailable on this Windows environment and is recorded
  as **not applicable**, not failed.
- **Verification evidence**: direct read-only database/audit reconciliation (not just browser
  observation) confirmed attendance ownership and the absence of any duplicate current-day row;
  every relevant audit row was exactly one-to-one with a successful action (one
  `ATTENDANCE_CLOCKED_IN`/`ATTENDANCE_CLOCKED_OUT` pair for Employee A's row, one
  `ATTENDANCE_CLOCKED_IN` for Employee B's still-open row) with safe, redacted detail fields only;
  at this point (before the cleanup below removed the temporary fixtures), audit logs remained
  append-only, with no row deleted or edited.
- **Cleanup**: all 26 temporary attendance rows, 3 temporary users, and 2 temporary employees
  were deleted by exact recorded id in a single transaction, verified by exact `RETURNING` sets;
  the shared department (id 332, "IT") was reused read-only and remains unchanged. Every relevant
  audit record (ids 5650, 5651, 5653) was retained: deleting the temporary users caused
  PostgreSQL to set those audit rows' `actor_id` values to NULL through the schema's existing
  `ON DELETE SET NULL` foreign key. No audit row was deleted or manually edited, and `action`,
  `entity_name`, `entity_id`, `details`, and `created_at` remained unchanged. Core table counts
  returned exactly to the original baseline (users 3, employees 2, departments 1, attendance 0).
- **Automated baselines were not rerun during Phase 9E**: backend remains the previously
  documented 505 tests, 0 failures, 0 errors, 3 pre-existing skips; frontend remains the
  previously documented 458 tests across 25 files, 0 failures, 0 skipped, with the previously
  documented successful build. No production source, test, migration, dependency, or
  configuration file changed during Phase 9E.

**Phase 9E: Complete.**

**Phase 9: Complete.** All Phase 9 sub-phases (9A, 9B-1, 9B-2, 9C, 9D, and 9E) are complete:
attendance persistence/time foundation, concurrency-safe backend service, secured employee
self-service API, frontend API/formatting foundation, employee attendance UI, and focused manual
browser verification.

## Phase 10 — HR Admin reports

**Approved scope** (product decisions resolved prior to implementation):
- **Payroll cost**: defined as `SUM(Payslip.grossPay)`, BigDecimal precision preserved, reported
  for a selected year as an authoritative annual total and authoritative monthly totals. Only
  payslips belonging to payroll runs with status **CALCULATED** or **COMPLETED** contribute;
  **PROCESSING** and **FAILED** runs do not. No department-level payroll-cost breakdown (employees'
  department is mutable; payslips carry no historical department snapshot suitable for this). No
  base-pay or net-pay interpretation. No frontend calculation of totals.
- **Headcount**: current employees whose `employmentStatus` is **ACTIVE**, reported as an
  authoritative total active count and a current ACTIVE breakdown by department. Department
  breakdown represents current state only; **INACTIVE** employees do not contribute; no
  historical/as-of-date headcount; no status filter at the API layer (ACTIVE is fixed into the
  query, not parameterized); no employee-level rows.
- **Explicit exclusions for all of Phase 10**: no historical headcount, no department payroll-cost
  breakdown, no attendance/leave reporting, no employee-facing reports, no charts, no
  export/download, no forecasting, no scheduled/email reports, no dashboard changes.

### Phase 10A — Backend report-query foundation

**Status: Complete.**

- **Scope**: persistence/query-layer foundation only. No service, controller, endpoint,
  `@PreAuthorize`/security annotation, exception handler, frontend code, route, sidebar item,
  chart, export, migration, or dependency was added.
- **New package** `com.hrpayroll.backend.reports` with two immutable projection records:
  - `MonthlyPayrollCostSummary(int periodMonth, BigDecimal totalGrossPay)`.
  - `DepartmentHeadcountSummary(Long departmentId, String departmentName, long activeEmployeeCount)`.
- **`PayslipRepository`** gained two aggregate query methods:
  - `findMonthlyGrossPayTotals(int year)` — JPQL `SELECT NEW` constructor expression against
    `MonthlyPayrollCostSummary`, `SUM(p.grossPay)` grouped by `pr.periodMonth`, filtered to
    `pr.periodYear = :year` and `pr.status IN (CALCULATED, COMPLETED)` (fully-qualified enum
    literals), ordered by `periodMonth` ascending. Only months with at least one qualifying
    payslip are returned.
  - `findAnnualGrossPayTotal(int year)` — same status/year filter, `COALESCE(SUM(p.grossPay), 0)`
    so an empty year returns a real `BigDecimal` zero rather than SQL `NULL`.
- **`EmployeeRepository`** gained two methods:
  - `countByEmploymentStatus(EmploymentStatus employmentStatus)` — plain Spring Data derived count
    (kept generically parameterized as a reusable primitive; not itself an API contract).
  - `findActiveHeadcountByDepartment()` — JPQL `SELECT NEW` constructor expression against
    `DepartmentHeadcountSummary`, `COUNT(e)` grouped by `d.id, d.name`, with
    `e.employmentStatus = EmploymentStatus.ACTIVE` fixed directly into the query (not a parameter),
    ordered by `d.name ASC, d.id ASC`. Departments with zero current ACTIVE employees are never
    returned.
- **Payroll-status invariant**: `PayrollRunTransactionService.calculate()` is the only code path
  that ever inserts a `Payslip` row, and it is exactly what transitions a run
  `PROCESSING → CALCULATED`, so `FAILED`/stuck-`PROCESSING` runs have zero payslip rows by
  construction. The explicit `IN (CALCULATED, COMPLETED)` filter is a defensive, testable statement
  of that invariant rather than the sole mechanism — proven with deliberately-anomalous fixtures
  that insert a payslip under a `PROCESSING`/`FAILED` run directly via SQL, bypassing the service.
- **Tests**: new real-PostgreSQL repository test class
  `backend/src/test/java/com/hrpayroll/backend/reports/ReportQueryRepositoryTest.java` (22 tests)
  and a new raw-SQL fixtures class `backend/src/test/java/com/hrpayroll/backend/support/ReportTestFixtures.java`,
  following this project's established real-database-only testing convention (never H2, never
  mocked repositories). Covers: multi-payslip/multi-month summation, chronological ordering,
  annual-total exactness, CALCULATED/COMPLETED inclusion, PROCESSING/FAILED exclusion (with
  anomalous payslip rows), cross-year exclusion, empty-year zero/non-null behavior, exact
  BigDecimal cent precision, basePay/netPay never substituted for grossPay, no double-counting
  across employees in one run, ACTIVE/INACTIVE headcount inclusion/exclusion, per-department
  aggregation and ordering, multi-department separation, departments with only INACTIVE employees
  never appearing, a cross-check of the department-summed count against a raw SQL count, and a
  reflection-based assertion that confirms the projection records expose no employee identity or
  employee-level compensation fields. `MonthlyPayrollCostSummary` exposes only the approved
  aggregate `totalGrossPay`, while `DepartmentHeadcountSummary` exposes only department identity
  and the aggregate ACTIVE headcount.
- **RED confirmation**: genuine compile-time "cannot find symbol" failures for the not-yet-existing
  projection records and repository methods, confirmed before any production code was written.
- **GREEN verification**: all 22 focused tests pass
  (`./mvnw.cmd test -Dtest=ReportQueryRepositoryTest`). One test-fixture defect was found and fixed
  during GREEN (not a production-code issue): the test class's `tearDown()` originally attempted to
  delete `payroll_runs` rows before deleting the `payslips` referencing them; PostgreSQL correctly
  rejected the parent-row deletion via the `fk_payslips_payroll_run` foreign key, which left stale
  Phase 10 test-fixture rows behind from that failed run. `tearDown()` was corrected to delete
  dependent rows (payslips and other fixture rows) before payroll runs, and only those stale Phase
  10 test-fixture rows were then manually removed. No production or non-test row was touched. The
  focused test and full suite passed cleanly afterward.
- **Full backend verification**: `./mvnw.cmd clean verify` — **527 tests, 0 failures, 0 errors, 3
  skipped** (previous baseline 505 tests, 0 failures, 0 errors, 3 pre-existing skips; the increase
  is exactly the 22 new tests, and the same 3 pre-existing skips remain). Flyway validated the same
  13 migrations with no new migration added. `backend/pom.xml` and the entire `frontend/` tree are
  unchanged — no frontend test or build was run, since no frontend code changed.
- **Design choices empirically confirmed** under this project's exact Hibernate 6.6.53/PostgreSQL
  16 configuration: fully-qualified enum literals inside JPQL `IN (...)` and `=` comparisons; JPQL
  `SELECT NEW` constructor expressions against Java `record` types; `COALESCE(SUM(...), 0)`
  resolving to a proper `BigDecimal`.

**Phase 10A: Complete.** Phase 10 remains In progress overall; only the backend aggregate-query
foundation exists.

### Phase 10B — Secured backend reports service and API

**Status: Complete.**

- **Scope**: secured service/HTTP layer only, built entirely on the committed Phase 10A query
  foundation. No frontend code, route, sidebar item, chart, export, migration, or dependency was
  added, and no manual browser verification was performed (deferred, see below).
- **Endpoints**, both under `/api/reports`, gated by a single class-level
  `@PreAuthorize("hasRole('HR_ADMIN')")` on `ReportController` (mirroring `LeaveBalanceController`'s
  exact precedent) so an `EMPLOYEE` or unauthenticated caller is rejected before either method body
  ever runs:
  - `GET /api/reports/payroll-cost?year=YYYY` — required `year` query parameter, no request body.
  - `GET /api/reports/headcount` — no parameters, no request body.
  - Neither endpoint accepts an `employeeId`/`userId`/`actorId`/`departmentId`/status/sort/page/size
    parameter; any such extra query parameter sent by a caller is simply ignored by Spring MVC and
    has no effect on the response.
- **New package contents** (`com.hrpayroll.backend.reports`), all separate from the Phase 10A
  repository projections:
  - `MonthlyPayrollCostResponse(int periodMonth, BigDecimal totalGrossPay)` and
    `DepartmentHeadcountResponse(Long departmentId, String departmentName, long activeEmployeeCount)`
    — each with a `from(...)` mapping factory from its Phase 10A projection, mirroring
    `PayrollRunResponse.from`'s exact convention.
  - `PayrollCostReportResponse(int year, BigDecimal annualTotalGrossPay, List<MonthlyPayrollCostResponse> monthlyTotals)`
    and `HeadcountReportResponse(long totalActiveEmployees, List<DepartmentHeadcountResponse> departments)`
    — both defensively copy their list component via `List.copyOf(...)` in a compact constructor,
    guaranteeing a non-null, immutable snapshot regardless of caller.
  - `ReportService` (`@Service`, `@Transactional(readOnly = true)`, mirroring `PayrollQueryService`):
    `getPayrollCostReport(int year)` validates `year` against the exact existing 2000-2100 range
    already enforced by `PayrollPeriod.of`/`PayrollQueryService.listRuns` (reused, not reinvented),
    then maps `PayslipRepository.findMonthlyGrossPayTotals`/`findAnnualGrossPayTotal` directly with
    no aggregation performed in Java; `getHeadcountReport()` maps
    `EmployeeRepository.findActiveHeadcountByDepartment`/`countByEmploymentStatus(ACTIVE)` the same
    way. Neither method injects or calls `AuditService`, logs a payload, uses `double`/`float`, or
    accepts an authenticated-user/employee/department/status/identity argument.
  - `ReportController` (`@RestController`) delegates only to `ReportService` — no repository access,
    no manual JWT decoding, no audit-record creation, and no `@AuthenticationPrincipal` parameter on
    either method (no report needs the caller's identity).
- **Year validation/error behavior**: an out-of-range year reuses the existing
  `InvalidPayrollPeriodException` → `PayrollExceptionHandler` → `400 Bad Request` /
  `INVALID_PAYROLL_PERIOD` path unchanged (no new exception or handler was created for this case).
  A missing or non-numeric `year` is handled entirely by Spring Boot's existing
  `spring.mvc.problemdetails.enabled=true` default `400` behavior, confirmed empirically to contain
  no SQL, JPQL, stack trace, or internal class name — so no additional exception handler was needed
  for either case.
- **RED confirmation**: `ReportServiceTest`/`ReportControllerTest` initially failed to compile with
  genuine "cannot find symbol" errors for `ReportService`, `PayrollCostReportResponse`, and
  `HeadcountReportResponse` -- confirmed before any production code was written.
- **Tests**: two new focused classes, 44 tests total, no fixture helper duplicated (the existing
  Phase 10A `ReportTestFixtures` department/employee/payroll-run/payslip helpers were reused
  unchanged; no persisted `users` row is needed for either test principal, since
  `JwtAuthenticationFilter` resolves authentication entirely from the validated JWT's claims with no
  per-request database lookup, and neither report endpoint ever calls `AuditService` -- the only
  path that would require an existing `users` row for a foreign key).
  - `ReportControllerTest` (36 tests, `@SpringBootTest` + `@AutoConfigureMockMvc`, real PostgreSQL,
    real Spring Security filter chain, real JWT tokens): authorization (401/403/200 for both
    endpoints), exact annual/monthly payroll-cost values, ascending month ordering,
    CALCULATED/COMPLETED inclusion and PROCESSING/FAILED exclusion (with anomalous fixture rows),
    cross-year exclusion, empty-year zero/empty-list behavior, exact BigDecimal cent precision, no
    employee-identity/compensation field in either response body, exact/delta-based ACTIVE headcount
    correctness, department grouping/ordering/omission-when-INACTIVE-only, missing/non-numeric/
    out-of-range year all returning a safe 400 (body asserted free of `Exception`/SQL/stack-trace/
    `java.lang` text), a fixed `ProblemDetail` shape (`status`/`title`/`detail`) on the out-of-range
    case, no audit-log row on success or on a forbidden/unauthorized/invalid request, no mutation of
    any `payroll_runs`/`payslips`/`employees`/`departments`/`users` row, both response lists
    serializing as a JSON array (never null), no repository projection class name in either response
    body, and extra client-supplied `employeeId`/`userId`/`departmentId` query parameters being
    silently ignored.
  - `ReportServiceTest` (8 tests, pure Mockito unit test mirroring `BootstrapAdminRunnerTest`'s
    precedent, no Spring context): proves two behaviors the real-database integration test cannot
    observe -- that `annualTotalGrossPay`/`totalActiveEmployees` come from their own dedicated
    repository queries and are never resummed/recounted in Java (by mocking the two sources to
    deliberately disagree and asserting the authoritative one wins), and the exact empty-repository
    mapping to a zero total and an empty (never null) list for both reports, since headcount has no
    scoping dimension like payroll's `year` and so can never be forced to zero against the shared
    integration database's real ACTIVE employees. Also confirms an out-of-range year is rejected
    before either repository is ever touched.
- **Focused/combined/full test totals**: 44 new Phase 10B tests; combined with the 22 existing Phase
  10A repository tests, 66 report tests total; full backend suite
  (`./mvnw.cmd clean verify`) — **571 tests, 0 failures, 0 errors, 3 skipped** (previous baseline 527
  tests, 0 failures, 0 errors, 3 pre-existing skips; the increase is exactly the 44 new tests, and
  the same 3 pre-existing skips remain in the same two classes as before). Flyway validated the same
  13 migrations with no new migration added. `backend/pom.xml` and the entire `frontend/` tree are
  unchanged -- no frontend test or build was run, since no frontend code changed.
- **Frontend deferral**: the HR Admin-facing reports page, its route/sidebar entry, any chart or
  export, and manual browser verification are explicitly deferred to Phase 10C/10D -- nothing in
  this sub-phase renders or fetches these endpoints from a browser context.

**Phase 10B: Complete.** Phase 10 remains In progress overall; the backend report query and secured
HTTP API are complete, while the frontend reports surface and manual browser verification remain
deferred.

### Phase 10C — Frontend reports API and type foundation

**Status: Complete.**

- **Scope**: frontend data-layer only (`frontend/src/api/reports.ts` + its test). No Reports page,
  route replacement, sidebar item, component, chart, export, state-management abstraction, backend
  change, migration, or dependency was added. `/admin/reports` still renders `PlaceholderPage` in
  `App.tsx`, and `Sidebar.tsx` still has no reports entry -- both confirmed untouched.
- **Four exported types**, matching the committed Phase 10B backend response records field-for-field
  and named without a `Response` suffix (this project's established convention: `PayrollRunResponse`
  -> `PayrollRun`, `PayslipResponse` -> `Payslip`, `AttendanceResponse` -> `AttendanceRecord`):
  - `MonthlyPayrollCost { periodMonth: number; totalGrossPay: number }`
  - `PayrollCostReport { year: number; annualTotalGrossPay: number; monthlyTotals: MonthlyPayrollCost[] }`
  - `DepartmentHeadcount { departmentId: number; departmentName: string; activeEmployeeCount: number }`
  - `HeadcountReport { totalActiveEmployees: number; departments: DepartmentHeadcount[] }`
  - BigDecimal wire values (`annualTotalGrossPay`/`totalGrossPay`) are typed as plain `number`,
    matching every existing payroll amount in this project (e.g. `Payslip.netPay`).
- **Two exported functions**, both using only the existing shared `apiClient` (no direct `axios`
  import, no manual JWT attachment/decoding):
  - `getPayrollCostReport(year: number): Promise<PayrollCostReport>` -- `GET /api/reports/payroll-cost`
    with exactly `{ params: { year } }`, no request body, no employee/caller-identity parameter.
  - `getHeadcountReport(): Promise<HeadcountReport>` -- `GET /api/reports/headcount` with no params
    object and no request body at all.
  - Both return `response.data` unchanged: no sorting, filtering, summing, counting, rounding,
    month-filling, or any other mutation of the backend's response. The backend's authoritative
    `annualTotalGrossPay`/`totalActiveEmployees` and its ordering are trusted as-is and never
    recomputed client-side. No client-side year-range validation was added -- the backend remains
    authoritative; that belongs to a future page's UI input constraints, not this data-layer module.
  - Errors from `apiClient` propagate as an ordinary rejected promise, unchanged -- this module
    never catches or rewrites them.
- **Formatter reuse decision**: Phase 10C deliberately adds no new formatter. The existing
  `formatMYR(amount: number): string` (`frontend/src/utils/currency.ts`) and
  `formatPayrollPeriod(month: number, year: number): string`
  (`frontend/src/utils/payrollPeriod.ts`) both already accept exactly the numeric shapes these new
  types carry (`totalGrossPay`/`annualTotalGrossPay` as `number`; `periodMonth`/`year` as `number`),
  so both are reusable unchanged by a future Reports page -- confirmed by reading both files; neither
  was modified. `reports.ts` itself performs no formatting -- that belongs to the future UI layer.
- **RED confirmation**: `reports.test.ts` initially failed with a genuine Vite module-resolution
  error ("Failed to resolve import './reports'") -- confirmed before any production code was
  written.
- **Tests**: one new focused file, `frontend/src/api/reports.test.ts` (22 tests, mocking only the
  shared `apiClient`, following `payroll.test.ts`/`attendance.test.ts` conventions exactly). Covers
  the exact request shape for both endpoints (including the absence of any
  employeeId/userId/actorId/departmentId/status/sort/page/size parameter), `response.data`
  returned by reference, exact preservation of array ordering and empty arrays, exact preservation
  of decimal aggregate values, two deliberate-inconsistency proofs that neither
  `annualTotalGrossPay` nor `totalActiveEmployees` is ever recomputed client-side from the
  per-month/per-department rows, rejected-promise propagation for both endpoints, and (via typed
  object literals assigned to each exported type, backed by a runtime `Object.keys` comparison)
  representative-shape checks for all four types. The exact exported type declarations in
  `reports.ts` are the source of truth for the complete field set (including the absence of any
  employee id/name/code/email/IC/salary-structure/payslip field and of any additional optional
  field) and were directly reviewed; the typed-literal/`Object.keys` checks are complementary
  compile-time/runtime evidence using representative sample values, not a substitute for that
  review. Two source-text-scanning tests originally planned for "no broad `any` type" and "no
  direct axios import or manual JWT logic" were removed during GREEN: this project's browser-facing
  `tsconfig.app.json` (which type-checks every file under `src`, tests included) declares no Node
  types, so a `fs`-based source scan only compiles by adding `@types/node` to that config -- a
  dependency/config change outside this sub-phase's scope. Both properties were instead confirmed
  by direct source review of `reports.ts` (its only import is `{ apiClient } from './client'`,
  with no manual authorization/JWT logic anywhere in the file); the request-shape tests separately
  verify the exact arguments used by the current API calls, but -- being tests of this module's
  present behavior rather than of its import list -- they do not themselves guarantee that a
  hypothetical future unrelated direct-Axios call could never be added elsewhere in the file.
- **Focused/broader/full test totals**: 22 new Phase 10C tests; the broader `src/api`/`src/utils`
  group -- 9 files, 128 tests -- passes; full frontend suite (`npx vitest run --no-file-parallelism`)
  -- **26 test files, 480 tests, 0 failures, 0 skipped** (previous baseline 458 tests across 25
  files, 0 failures, 0 skipped; the increase is exactly the 22 new tests and 1 new file). Production
  build (`npm run build`) succeeded. Backend was not touched and its tests were not rerun.
- **Cleanliness**: no backend file, migration, or dependency changed; `package.json`/
  `package-lock.json` unchanged; `App.tsx`, `App.test.tsx`, `Sidebar.tsx`, and the `AppLayout` tests
  are all unchanged; no page/component/route/hook/context was added.
- **Deferral**: the HR Admin-facing Reports page itself, its route replacing the `/admin/reports`
  placeholder, the sidebar entry, any chart/export, and manual browser verification are all
  explicitly deferred to Phase 10D/10E -- nothing in this sub-phase renders anything or is exercised
  from a browser context.

**Phase 10C: Complete.** Phase 10 remains In progress overall; the backend reports API and frontend
data foundation are complete, while the HR Admin reports page and manual browser verification
remain deferred.

### Phase 10D — HR Admin Reports page, route, and sidebar

**Status: Complete.**

- **Scope**: the real `/admin/reports` page replaced the final `PlaceholderPage` at that route,
  built entirely on the committed Phase 10B backend contract and Phase 10C `reports.ts` API/types.
  No chart, export/download, client-side aggregation/sorting/filtering, backend file, migration,
  or dependency was added; `getPayrollCostReport`/`getHeadcountReport` and both existing formatters
  are unchanged.
- **Route**: `App.tsx`'s existing `/admin/reports` route keeps its unchanged
  `<ProtectedRoute allowedRoles={['HR_ADMIN']}>` wrapper; only the element inside it was swapped
  from `PlaceholderPage` to `ReportsPage`. No second route and no Employee-facing reports route
  were added.
- **Sidebar**: exactly one new `HR_ADMIN_ITEMS` entry, `{ label: 'Reports', to: '/admin/reports' }`,
  appended after `Payroll` (preserving every existing item's order); no `EMPLOYEE_ITEMS` entry was
  added. The stale "still an unfinished placeholder" comment above `HR_ADMIN_ITEMS` was removed.
- **`ReportsPage`** (`frontend/src/routes/admin/ReportsPage.tsx`): an `h1` "Reports" followed by two
  fully independent sections, each with its own loading/error/data state and its own `useEffect`:
  - **Payroll Cost**: a labelled Year `<select>` (options restricted to exactly 2000-2100, defaulting
    to the current calendar year clamped into that range), a visible "Annual Payroll Cost" value,
    and a monthly table (`Month`, `Gross Payroll Cost` columns). Changing the year re-runs only the
    payroll effect (`[year]` dependency); headcount's effect (`[]` dependency) never re-runs.
    Stale-response protection reuses this project's existing closure-scoped `cancelled` flag
    convention (the same pattern already used by `PayrollRunListPage`/`AttendancePage`): a year
    change's cleanup marks the previous effect's own `cancelled` true before the new effect's
    independent `cancelled` starts fresh, so a slower, older-year response can never overwrite a
    newer selection. Empty months render "No payroll cost data for this year." while still showing
    the authoritative (possibly zero) annual total above it.
  - **Current Headcount**: a visible "Total Active Employees" value and a department table
    (`Department`, `Active Employees` columns), fetched once, independent of the payroll year.
    Empty departments render "No active employees found." while still showing the authoritative
    (possibly zero) total above it.
  - Every rendered value/order is exactly what the backend returned -- this page never sums,
    recounts, sorts, filters, or fills in a missing month; `annualTotalGrossPay` and
    `totalActiveEmployees` are always the backend's own field, proven with deliberately-inconsistent
    mocked data in tests. `formatMYR`/`formatPayrollPeriod` are reused unchanged for all monetary/
    period display; no raw ISO date and no employee-level field is ever rendered (neither API type
    carries one). A failed section shows one of two fixed, safe messages --
    "Could not load payroll cost report. Please try again." or
    "Could not load headcount report. Please try again." -- inside a `role="alert"` element,
    regardless of the real error's content; a failure in one section never blocks the other, and a
    payroll failure is naturally retried by changing the year (no separate Retry control exists).
    Tables use the established `overflow-x-auto` wrapper and an `sr-only` accessible `<caption>`;
    no chart/canvas/SVG, export/download control, or employee-identity input exists anywhere on the
    page.
- **RED-first evidence**:
  - `ReportsPage.test.tsx` initially failed with a genuine Vite module-resolution error
    ("Failed to resolve import './ReportsPage'").
  - New `App.test.tsx` route tests run against the unchanged placeholder confirmed genuine RED for
    "HR_ADMIN reaches the real Reports page" specifically (the old placeholder text was still
    present); the three companion tests (unauthenticated redirect, EMPLOYEE blocked, no
    Employee-facing reports route) already passed before the route swap, since they do not depend
    on the new page's content -- recorded accurately rather than claimed as RED.
  - New `AppLayout.test.tsx` sidebar tests run against the unchanged `Sidebar.tsx` failed genuinely
    (no "Reports" link existed yet): the six-item-list assertion, the Reports-href assertion, the
    Reports-navigation assertion, and the updated "every sidebar item has the exact href" assertion
    all failed for that reason; the remaining, unrelated sidebar tests were unaffected.
  - Three test-only defects were found and fixed during GREEN, all in `ReportsPage.test.tsx`, none
    requiring a production-code change: (1) MYR amounts were queried with the same literal `RM<NBSP>`
    string `formatMYR` itself produces, but Testing Library's default text normalizer collapses all
    whitespace (NBSP included) to a plain space before a `getByText` comparison runs, so the DOM
    query needed a plain space instead; (2) two accessibility tests and one responsive-container
    test omitted their own `render()` call and relied on a previous test's now-cleaned-up DOM; (3) a
    rerender test asserted 2 calls under a mistaken belief that React Testing Library's `rerender`
    performs a second independent mount, when it actually re-renders the same mounted instance in
    place, so the correct expectation is 1 call.
- **Tests**: `ReportsPage.test.tsx` is new (29 tests); `App.test.tsx` gained 4 tests (unauthenticated
  redirect, EMPLOYEE blocked, HR_ADMIN reaches the real page, no Employee-facing reports route);
  `AppLayout.test.tsx` gained 2 tests (Reports href/uniqueness, Reports navigation) alongside
  updates to 3 existing assertions (six-item list, per-item href list, Employee-sidebar exclusion).
- **Focused/broader/full test totals**: focused `ReportsPage.test.tsx` -- 29 tests; new route tests
  -- 4; new sidebar tests -- 2; net-new frontend tests -- 35 (1 new test file); broader affected
  group (`reports.test.ts`, `ReportsPage.test.tsx`, `App.test.tsx`, `AppLayout.test.tsx`,
  `currency.test.ts`, `payrollPeriod.test.ts`) -- 6 files, 137 tests; full frontend suite
  (`npx vitest run --no-file-parallelism`) -- **27 test files, 515 tests, 0 failures, 0 skipped**
  (previous baseline 480 tests across 26 files; the increase is exactly the 35 net-new tests and 1
  new file). Production build (`npm run build`) succeeded. Backend was not touched and its tests
  were not rerun.
- **Cleanliness**: no backend file, migration, or dependency changed; `package.json`/
  `package-lock.json` unchanged; the committed Phase 10C `reports.ts` and both existing formatters
  (`formatMYR`, `formatPayrollPeriod`) are byte-for-byte unchanged.
- **Deferral**: focused manual browser verification of the real Reports page is deferred to
  Phase 10E -- nothing in this sub-phase was exercised in a real browser.

**Phase 10D: Complete.** Phase 10 remains In progress overall; the complete backend and frontend
reports feature now exists, while focused manual browser verification remains deferred.

### Phase 10E — HR Admin reports manual browser verification
Status: **Complete.** Full manual browser verification of the Phase 10A through 10D HR Admin reports
feature, performed against a live backend/frontend/PostgreSQL stack with temporary fixtures
(`MANUAL_10E_20260926`-prefixed departments, employees, users, 2091 payroll runs, and payslips),
tracked in an external cleanup ledger outside the repository, and fully removed after verification.

- **Authorization/navigation**: logged-out route protection passed; HR_ADMIN had exactly one
  `Reports` sidebar link and could reach `/admin/reports` directly; EMPLOYEE had no Reports sidebar
  item, direct navigation to `/admin/reports` was blocked, and no report data was exposed to
  EMPLOYEE (both report endpoints returned `403`).
- **Payroll cost**: the default year (2026) showed the authoritative zero total and the empty state;
  seeded year 2091 showed an annual total of `11056.65`, with monthly rows March `5501.00` then
  November `5555.65` in the backend's chronological order; missing months were not synthesized;
  deliberately-anomalous payslips under PROCESSING and FAILED runs were excluded, and no base-pay/
  net-pay figure was substituted for gross pay; empty year 2092 showed a zero total and no monthly
  rows; no chart, export/download control, or employee-level data exists.
- **Headcount**: the total ACTIVE count was `5` during fixture testing (2 pre-existing + 3 fixture
  employees); the current department breakdown (IT 2, Alpha 2, Beta 1) matched the backend with
  deterministic name ordering (a lower-id department sorted after a later-named one); INACTIVE
  employees and an INACTIVE-only department were excluded; no historical/as-of-date claim and no
  employee-level row is shown.
- **Responsive/accessibility/browser verification**: desktop (1440x900), tablet (768x1024), mobile
  (390x844), and 200% zoom all passed with tables scrolling only inside their own containers;
  keyboard/focus access and semantic headings/tables/accessible captions passed; loading/error/
  empty/data states are conveyed as text; Chrome and Firefox both passed.
- **Persistence**: report results were identical after refresh and after logout/login
  (PERSIST-01, PERSIST-02), as expected for backend-derived data.
- **Qualifications**: STATE-01 (visible independent loading states) passed manually. STATE-02 and
  STATE-03 (one section's fixed safe error while the other keeps rendering) are
  **AUTOMATED-COVERAGE-ONLY**, because Chrome's network request-blocking command was unavailable,
  so the failures were not forced manually; they are covered by the committed `ReportsPage.test.tsx`
  independent-section error tests. NETWORK-01 through NETWORK-03 are
  **SOURCE-AND-AUTOMATED-COVERAGE**: the committed `reports.ts` source and its tests show payroll cost
  sends only the `year` query parameter, headcount sends no business parameter, neither sends an
  employee/user/actor identity or request body, both go through the shared authenticated `apiClient`,
  and the page performs no aggregation, sorting, or filtering; the backend `ReportController` is
  class-level HR_ADMIN-only and accepts no client-supplied identity (`ReportControllerTest`).
  Stale-response race protection remains **AUTOMATED-COVERAGE-ONLY**; safe redaction of backend
  error detail is covered automatically; the Asia/Kuala_Lumpur New Year rollover of the default year
  remains **AUTOMATED-COVERAGE-ONLY**. Safari is unavailable on this Windows environment and is
  recorded as **not applicable**, not failed.
- **Verification evidence**: direct read-only database reconciliation (not just browser
  observation) confirmed the report totals and that report GETs are read-only: no report GET
  (successful, forbidden, unauthenticated, or invalid-year `400 INVALID_PAYROLL_PERIOD`) created an
  audit row or altered any business table. Temporary manual logins created `LOGIN_SUCCESS` /
  `LOGIN_FAILED` audit rows, which were retained as evidence.
- **Manual-testing incident**: during manual testing, fixture payroll run 2749 (Sep 2091, FAILED) was
  accidentally retried twice through the payroll detail page, producing two
  `PAYROLL_RUN_RETRIED`/`PAYROLL_RUN_FAILED` audit pairs (`SALARY_COVERAGE_MISSING`). The run remained
  FAILED (only its `version` and `failure_reason` changed), no payslip changed, report results were
  unchanged because FAILED runs are excluded, and no pre-existing business row was modified.
- **Incidental maintenance defect** (payroll detail, not Reports): the valid fixtures exposed a
  pre-existing whole-page horizontal overflow on the HR Admin payroll run detail page
  (`/admin/payroll/:id`), reproducible with any run. Root cause: the absolutely positioned `sr-only`
  "Actions" column header had no positioned ancestor, so it escaped the payslip table's
  `overflow-x-auto` wrapper and widened the page. Fixed by making that wrapper positioned
  (`relative mt-4 overflow-x-auto`), with a RED-first structural regression test in
  `PayrollRunDetailPage.test.tsx`; committed separately as `074710c7e7fd4acc08e8ff3a54258d9a3ae5e901`
  ("Phase 10E maintenance: contain payroll detail table overflow"). Focused test file 60/60; full
  frontend suite (`npx vitest run --no-file-parallelism`) -- **27 test files, 516 tests, 0 failures,
  0 skipped** (previous baseline 515; the increase is exactly the 1 new test); `npm run build`
  succeeded. Manual retest passed in Chrome and Firefox at 100% and 200% zoom and at 1440x900,
  768x1024, and 390x844: the table scrolls internally with no page-level overflow, keyboard focus
  remains usable, the accessible "Actions" header is retained, and the payroll list and Reports pages
  are unaffected. The same `sr-only`-in-header pattern in three narrower sibling tables was not
  changed without confirmed evidence of overflow.
- **Cleanup**: all 6 temporary payslips, 4 temporary payroll runs, 2 temporary users, 5 temporary
  employees, and 3 temporary departments were deleted by exact recorded id in a single transaction,
  verified by exact `RETURNING` sets; core table counts returned exactly to the original baseline
  (users 3, employees 2, departments 1, payroll_runs 0, payslips 0), and pre-existing rows were
  verified unchanged by checksum. Every audit row created during setup and testing was retained:
  deleting the temporary users caused PostgreSQL to set those audit rows' `actor_id` values to NULL
  through the schema's existing `ON DELETE SET NULL` foreign key. No audit row was deleted or manually
  edited, and `action`, `entity_name`, `entity_id`, `details`, and `created_at` remained unchanged.
- **Automated baselines**: backend remains the previously documented 571 tests, 0 failures, 0 errors,
  3 pre-existing skips, with the same 13 Flyway migrations (not rerun during Phase 10E, since no backend
  code changed). Frontend is 516 tests across 27 files after the maintenance fix above. No migration,
  backend source, dependency, lockfile, or configuration file changed during Phase 10E.

**Phase 10E: Complete.**

**Phase 10: Complete.** All Phase 10 sub-phases (10A, 10B, 10C, 10D, and 10E) are complete: backend
report-query foundation, secured HR_ADMIN reports service and API, frontend reports API/type
foundation, HR Admin Reports page/route/sidebar, and focused manual browser verification.

## Phase 11 — Dashboard implementation

**Approved scope** (from the Phase 11A audit):
- Replace the static HR Admin dashboard (`/admin`) and the placeholder Employee dashboard
  (`/employee`) with real dashboards built **entirely on existing secured domain endpoints** -- no
  backend change, no dedicated `/api/dashboard/*` endpoint, no migration, and no dependency.
- Every count/total is the backend's authoritative value (a report total or a paged endpoint's
  `page.totalElements`, which comes from the backend's own COUNT query); the frontend never counts,
  sums, sorts, or filters rows.
- Each dashboard section has its own request, loading/error/data state, and effect, so one failed
  section never hides another; failures show a fixed safe message, never raw error detail.
- Read-only dashboard GETs create no audit rows (the project convention: only mutations and the
  `/sensitive` read are audited).
- **Explicit exclusions**: no charts, forecasting, activity feed/audit-log reading, notifications,
  calendars, exports/downloads, new persistent tables, or an Employee pending-leave *count* (the
  self-service leave endpoint has no status filter, so no authoritative count exists without a new
  backend capability).

### Phase 11A — Dashboard scope and readiness audit

**Status: Complete.** Read-only audit (no file, database, or process changed). Findings:
`AdminDashboardPage` was a static stub (h1 + one sentence; its Phase 7 quick links had been removed)
and `/employee` rendered the shared `PlaceholderPage`; both routes were already role-guarded
(`HR_ADMIN` / `EMPLOYEE`) and both sidebars already had a `Dashboard` entry. Every proposed dashboard
value except an Employee pending-leave count is served by an existing secured endpoint, so no backend
work is required. Recommended sequence: 11B HR Admin dashboard, 11C Employee dashboard, 11D manual
browser verification.

### Phase 11B — HR Admin dashboard

**Status: Complete.**

- **Scope**: frontend only. `AdminDashboardPage.tsx` (static stub) replaced by the real dashboard;
  new `AdminDashboardPage.test.tsx`; `App.test.tsx` updated. No backend file, migration, dependency,
  lockfile, API-domain module, formatter, route, or sidebar item changed; the `/admin` route keeps its
  unchanged `ProtectedRoute allowedRoles={['HR_ADMIN']}` wrapper; the Employee dashboard is untouched.
- **Reused endpoints and exact request shapes** (existing API functions, no new dashboard wrapper):
  - **Active Employees** -- `getHeadcountReport()` (no arguments) -> `GET /api/reports/headcount`
    (class-level HR_ADMIN). Renders `totalActiveEmployees` as returned plus the department breakdown
    in backend order; never sums the department rows.
  - **Pending Leave Requests** -- `listLeaveRequests({ status: 'PENDING', page: 0, size: 1 })` ->
    `GET /api/leave-requests?status=PENDING&page=0&size=1` (HR_ADMIN). Renders only
    `page.totalElements`; the one over-fetched row is never stored or rendered (no employee name/code,
    reason, leave type, or internal id). Links to `/admin/leave`.
  - **Latest Payroll Run** -- `listPayrollRuns({ page: 0, size: 1 })` -> `GET /api/payroll-runs`
    (HR_ADMIN), relying on the endpoint's established `createdAt DESC, id DESC` order (the same order
    the Payroll Runs list shows), so it is the most recently created run. Renders the period via
    `formatPayrollPeriod`, the status as a visible text badge (existing payroll badge convention), and
    the created date via the existing timezone-safe `formatAttendanceDate` applied to the date part of
    `createdAt`; links to `/admin/payroll/{id}` without showing the id as text. `failureReason` (which
    can name an employee code), rate snapshots, and `version` are deliberately not rendered.
  - **Quick Actions** -- links to `/admin/employees`, `/admin/leave`, `/admin/payroll`,
    `/admin/reports` (no API).
  - No request carries an `employeeId`/`userId`/`actorId`.
- **State**: three independent `useEffect`s, each with its own loading message ("Loading active
  employees..." etc.), fixed safe `role="alert"` error ("Could not load active employees. Please try
  again.", "Could not load pending leave requests. Please try again.", "Could not load latest payroll
  run. Please try again."), and data state, using the project's closure-scoped `cancelled` flag so a
  late response after unmount never updates state. Visible empty states: "No active employees found."
  (with the authoritative total still shown), "No leave requests are awaiting review.", "No payroll
  runs yet." No refresh button, chart, canvas/SVG, export/download, or activity feed.
- **Accessibility/responsiveness**: one h1 "Admin Dashboard"; each section is a `<section>` labelled
  by its h2 (Active Employees, Pending Leave Requests, Latest Payroll Run, Quick Actions); values use
  definition lists; the department table has an `sr-only` caption ("Active employees by department")
  and sits in a `relative overflow-x-auto` wrapper (the Phase 10E containment lesson); statuses are
  text, never color alone.
- **RED-first evidence**: the new `AdminDashboardPage.test.tsx` (25 tests) ran against the static stub
  first: 24 failed on genuinely missing behavior (API functions never called, section regions/table/
  loading text not found), with no syntax, import, or mock error; 1 passed vacuously (the
  "never sends an employeeId/userId/actorId" guard, trivially true when no request is made). In
  `App.test.tsx`, 3 `/admin` route tests were added: "HR_ADMIN reaches the real dashboard" failed RED
  (no "Active Employees" heading on the stub); the logged-out redirect and EMPLOYEE-blocked tests
  already passed before the change, since the route guard pre-existed -- recorded accurately rather
  than claimed as RED.
- **Existing test adjusted**: `App.test.tsx`'s "opens the admin leave list from the sidebar Leave
  Requests link" became ambiguous once the dashboard's own "Leave Requests" quick action existed
  (two matching links on `/admin`); it is now scoped to the `Sidebar navigation` landmark, preserving
  its stated intent. No production change was needed for this.
- **Totals**: focused `AdminDashboardPage.test.tsx` -- 25/25; affected group (`AdminDashboardPage`,
  `App`, `AppLayout` tests) -- 3 files, 99 tests, all passing; full frontend suite
  (`npx vitest run --no-file-parallelism`) -- **28 test files, 544 tests, 0 failures, 0 skipped**
  (previous baseline 516 tests across 27 files; the increase is exactly the 25 + 3 new tests and 1 new
  file). `npm run build` succeeded. Backend untouched and not rerun (remains 571 tests, 0 failures,
  0 errors, 3 skipped, 13 migrations).

**Phase 11B: Complete.** Phase 11 remains **In progress** overall. **Phase 11C** (Employee dashboard)
is deferred and not started; **Phase 11D** (manual browser verification of both dashboards) is
deferred and not started -- nothing in Phase 11B was exercised in a real browser.

### Phase 11C — Employee dashboard

**Status: Complete.**

- **Scope**: frontend only. New `frontend/src/routes/employee/EmployeeDashboardPage.tsx` and its test
  file; `App.tsx`'s `/employee` route element swapped from `PlaceholderPage` to `EmployeeDashboardPage`
  inside the unchanged `ProtectedRoute allowedRoles={['EMPLOYEE']}` (the now-unused `PlaceholderPage`
  import was removed; the `PlaceholderPage.tsx` component file itself was left in place, now unused);
  `App.test.tsx` updated. No backend file, migration, dependency, lockfile, API-domain module,
  formatter, sidebar item, or HR Admin dashboard file changed.
- **Reused self-service endpoints and exact request shapes** (existing API functions, no new
  dashboard wrapper). Every endpoint is EMPLOYEE-only and derives the employee solely from the
  authenticated principal (`requireEmployeeId(principal)` on the backend); no function or request
  carries an `employeeId`/`userId`/`actorId` or any caller-selected identity:
  - **Today's Attendance** -- `getTodayAttendance()` -> `GET /api/employees/me/attendance/today`.
    Renders the backend state as text ("Not clocked in today" / "Clocked in" / "Clocked out") with the
    date and clock-in/clock-out times via the existing `formatAttendanceDate`/`formatAttendanceTime`
    (a null clock-out renders the formatter's safe "—"); links to `/employee/attendance`. Strictly
    read-only: no Clock In/Clock Out control, and today's state is never derived from attendance history.
  - **Leave Balances** -- `getOwnLeaveBalances()` -> `GET /api/leave-balances/me`. Renders the rows
    exactly in backend order (year, leave type, entitled, used, remaining, using the same one-decimal
    display as `LeaveDashboardPage`); `remainingDays` is always the backend's value, never computed;
    links to `/employee/leave`.
  - **Recent Leave Requests** -- `listOwnLeaveRequests(0, 5)` -> `GET /api/leave-requests/me?page=0&size=5`.
    Renders the returned content in backend order (leave type, date range, visible status badge); no
    pending count or total is computed or shown (no authoritative count exists -- see Phase 11 scope);
    reasons, reviewer data, and internal ids are not rendered; links to `/employee/leave`.
  - **Latest Payslip** -- `listOwnPayslips(0, 1)` -> `GET /api/employees/me/payslips?page=0&size=1`
    (newest first). Renders the period via `formatPayrollPeriod` and the backend `netPay` via
    `formatMYR`; gross pay, deductions, and the payslip's name/code/department/job-title snapshots are
    not shown, and no monetary value is computed; links to `/employee/payslips/{id}` without showing the
    id as text.
  - **My Profile** -- `getOwnProfile()` -> `GET /api/employees/me`. Shows only name, employee code,
    department, job title, and hire date (via the timezone-safe `formatAttendanceDate`); phone number,
    employment status, and internal ids are not rendered, and the audited `/sensitive` endpoint (IC/bank
    data) is never called; links to `/employee/profile`.
  - **Quick Actions** -- My Profile (`/employee/profile`), Attendance (`/employee/attendance`), My Leave
    (`/employee/leave`), My Payslips (`/employee/payslips`), Salary History (`/employee/salary`).
- **State**: five independent `useEffect`s (no `Promise.all`), each with its own loading message,
  fixed safe `role="alert"` error ("Could not load today's attendance. Please try again.", "Could not
  load leave balances. Please try again.", "Could not load recent leave requests. Please try again.",
  "Could not load latest payslip. Please try again.", "Could not load profile. Please try again."),
  and data state, using the project's closure-scoped `cancelled` flag. Visible empty states: "No leave
  balances found.", "No leave requests yet.", "No payslips yet." No refresh button, form, text input,
  chart, canvas/SVG, export/download, activity feed, or audit call.
- **Accessibility/responsiveness**: one h1 "Employee Dashboard"; each section is a `<section>` labelled
  by its h2 (Today's Attendance, Leave Balances, Recent Leave Requests, Latest Payslip, My Profile, Quick
  Actions); values use definition lists; both tables have semantic headers and `sr-only` captions ("My
  leave balances", "My recent leave requests") inside `relative overflow-x-auto` wrappers; attendance
  state and leave status are text, never color alone.
- **RED-first evidence**: the new `EmployeeDashboardPage.test.tsx` (30 tests) first failed with a
  genuine Vite module-resolution error (the page did not exist). To obtain behavior-level RED, a
  temporary stub rendering exactly the existing `/employee` placeholder (`PlaceholderPage` "Employee
  Dashboard" / "Your personal overview.") was then run: 29 failed on genuinely missing behavior (API
  functions never called, sections/tables/loading text not found), with no syntax, import, or mock
  error; 1 passed vacuously (the "never sends an identity argument" guard, trivially true when no
  request is made). The stub was then replaced by the real page. In `App.test.tsx`, 3 `/employee` route
  tests were added: "EMPLOYEE reaches the real dashboard" failed RED (no "Today's Attendance" heading on
  the placeholder); the logged-out redirect and HR_ADMIN-blocked tests already passed, since the route
  guard pre-existed -- recorded accurately rather than claimed as RED.
- **Existing test adjusted**: `App.test.tsx`'s "opens the leave dashboard from the sidebar My Leave
  link" became ambiguous once the dashboard's own "My Leave" quick action existed; it is now scoped to
  the `Sidebar navigation` landmark (same correction as Phase 11B). No production change was needed.
- **Totals**: focused `EmployeeDashboardPage.test.tsx` -- 30/30; affected group (`EmployeeDashboardPage`,
  `App`, `AppLayout` tests) -- 3 files, 107 tests, all passing; full frontend suite
  (`npx vitest run --no-file-parallelism`) -- **29 test files, 577 tests, 0 failures, 0 skipped**
  (previous baseline 544 tests across 28 files; the increase is exactly the 30 + 3 new tests and 1 new
  file). `npm run build` succeeded. Backend untouched and not rerun (remains 571 tests, 0 failures,
  0 errors, 3 skipped, 13 migrations).

**Phase 11C: Complete.** Phase 11 remains **In progress** overall. **Phase 11D** (manual browser
verification of both dashboards) is deferred and not started -- nothing in Phase 11C was exercised in a
real browser.

### Phase 11D — Dashboard manual browser verification

Status: **Complete.**

- **Scope**: focused live-stack manual verification of both completed dashboards -- HR Admin (`/admin`)
  and Employee (`/employee`) -- against a running backend/frontend/PostgreSQL stack with temporary fixtures
  (prefix `MANUAL_11D_20260928`) tracked in an external cleanup ledger outside the repository and fully
  removed afterwards. No dashboard-specific backend endpoint, migration, dependency, persistent reporting
  table, or production API change was introduced; the existing secured endpoints remained the
  authoritative sources for every dashboard value.
- **Authorization/navigation**: logged-out route protection passed for both dashboards; HR_ADMIN could
  reach only the HR Admin dashboard and its permitted links, and EMPLOYEE only the Employee dashboard and
  its permitted links; wrong-role dashboard and API access remained blocked (`403` from the other role's
  endpoints); no cross-role data leakage occurred.
- **HR Admin dashboard**: manual checks passed for the authoritative active-employee total, the ACTIVE
  headcount by department (inactive-only departments excluded as designed), the pending-leave total, the
  latest payroll-run summary (period, visible status, created date, no visible id), and the exact quick
  links; no employee-level, salary, chart, export, or unsupported report surface appeared.
- **Employee dashboard**: manual checks passed for the profile summary, current-day attendance, leave
  balances, recent leave requests (own requests only -- another employee's pending request never appeared),
  the latest own payslip (period and net pay), and the exact quick links; no other employee's data and no
  gross-pay, deduction, payslip-snapshot, phone, leave-reason, or sensitive identity data appeared.
- **Responsive/accessibility/browser/persistence**: desktop (1440x900), tablet (768x1024), mobile
  (390x844), and 200% zoom passed with no whole-page horizontal overflow and tables scrolling only within
  their own containers; keyboard access, visible focus, semantic headings, and text-based states passed;
  Chrome and Firefox both passed; values persisted across refresh and across logout/login.
- **Evidence classifications**: STATE-01 (independent loading states) is recorded as PASS through automated
  coverage, since it was not directly observed manually. Independent partial-error behavior, safe raw-error
  redaction, stale-response/unmount protection, deliberately inconsistent-data non-recomputation guards
  (structurally automated-only for leave `remainingDays`, which the backend computes as entitled − used),
  races, and timezone/business-date boundaries remain **AUTOMATED-COVERAGE-ONLY**. NETWORK request-shape
  and security checks are **SOURCE/API/AUTOMATED-COVERAGE** (the committed API-module source and tests,
  plus direct API verification during setup), not manual browser observation. Safari is unavailable on
  this Windows environment and is recorded as **not applicable**, not failed.
- **Quick Actions usability correction**: manual testing found Quick Actions usable but placed too low on
  both dashboards (below the long data sections). They were moved immediately below each dashboard's
  introduction by a pure JSX section move (added lines identical to removed lines) with no link, API,
  state, security, or data-rendering change. Two DOM-order regression tests were added (Admin Quick
  Actions before Active Employees; Employee Quick Actions before Today's Attendance), and the two existing
  heading-order assertions were updated to the new order. RED: the two new order tests failed while the
  other 55 dashboard tests passed. Focused GREEN: 57/57 dashboard tests; affected routing/layout group
  (`App`, `AppLayout`): 77/77; full frontend suite (`npx vitest run --no-file-parallelism`): **29 test
  files, 579 tests, 0 failures, 0 skipped**; `npm run build` succeeded. Committed separately as
  `bf3a84c8ab2be9093e1bc9a0bf8bd7e0e1765772` ("Phase 11D maintenance: move dashboard quick actions"). The
  final manual recheck passed in Chrome and Firefox across responsive sizes, 200% zoom, keyboard order,
  and overflow.
- **Fixtures and cleanup**: 19 temporary rows were created in one transaction -- 3 departments, 4
  employees, 2 users, 1 attendance row, 3 leave balances, 4 leave requests, 1 payroll run, and 1 payslip;
  existing leave types 1 and 2 were reused read-only. Cleanup deleted all 19 rows by exact recorded id in
  a single FK-safe transaction, verified by exact `RETURNING` sets. No sequence was reset and no
  pre-existing row was modified (verified by checksum). Core table counts returned exactly to the original
  baseline: users 3, employees 2, departments 1, attendance 0, leave_types 2, leave_balances 1,
  leave_requests 4, payroll_runs 0, payslips 0, salary_structures 0.
- **Audit behavior**: seven expected `LOGIN_SUCCESS` audit rows (ids 6305–6311) were created by the
  temporary-account logins; dashboard and report GET requests created no audit rows. All seven rows were
  retained: no audit row was deleted or manually edited. When the temporary users were deleted, PostgreSQL
  automatically set those rows' `actor_id` values to NULL through the existing `ON DELETE SET NULL`
  foreign key; their `action`, `entity_name`, `entity_id`, `details`, and `created_at` remained unchanged.
- **Service/process cleanup**: only the recorded Phase 11D backend and frontend processes were stopped
  (each command line re-verified first); ports 8080, 5173, and 5174 were free afterwards; PostgreSQL and
  unrelated containers/processes were untouched and remained healthy. The external ledger and its logs
  were preserved outside the repository.
- **Automated baselines**: backend tests were not rerun during Phase 11D; the last documented backend
  baseline remains 571 tests, 0 failures, 0 errors, 3 pre-existing skips, with 13 Flyway migrations. The
  final frontend verification (for the Quick Actions fix) is 579 tests across 29 files, 0 failures,
  0 skipped, with a successful production build. Tests and builds were not rerun during cleanup or during
  this documentation update.

**Phase 11D: Complete.**

**Phase 11: Complete.** All Phase 11 sub-phases are complete: 11A dashboard scope and readiness audit,
11B HR Admin dashboard, 11C Employee dashboard, and 11D live manual verification, Quick Actions usability
correction, fixture cleanup, and reconciliation.

## Phase 12 — Project hardening and portfolio readiness

**Approved direction** (from the Phase 12A audit):
- Repair confirmed frontend containment defects.
- Establish safe, opt-in demo readiness -- only if separately approved.
- Replace stale reviewer-facing documentation.
- Perform final verification before closure.
- No new core payroll business feature is implied.

### Phase 12A — Scope, gap, and readiness audit

Status: **Complete.** Read-only audit; no file was changed.

- Phases 12 and 13 previously had no authoritative product definition anywhere in the repository (docs,
  README, source, tests, configuration, or commit history) -- they were numbering placeholders only.
- The core application is complete for its documented feature set: every frontend route renders a real
  page, no route-backed `PlaceholderPage` remains (the component file is now unused), and both role
  sidebars link only to real, role-appropriate destinations.
- Backend security, ownership, and DTO boundaries were found sound: only login and health are public;
  role-specific endpoints carry `@PreAuthorize`; self-service endpoints derive identity from the
  authenticated principal; by-id payslip/PDF/leave reads enforce ownership (404 to non-owners);
  controllers return DTOs only; every mutating service records audit events.
- `README.md` is stale and contradictory (it still describes Phase 5 as current, states that no salary,
  leave, attendance, payroll, or report functionality exists, and references a non-existent
  `docs/decisions/` directory).
- Fresh-clone demo provisioning is absent: there is no way to create EMPLOYEE login accounts or leave
  balances through the application (both documented as intentionally out of scope), so the Employee side
  cannot be demonstrated without direct SQL. Any remedy requires a separate decision.
- Three list pages shared the same unpositioned table-wrapper pattern that caused the Phase 10E
  payroll-detail overflow. The real application was not started and the actual routes were not opened;
  isolated headless-browser reproductions of the same DOM/CSS pattern showed page-level overflow for the
  Admin leave-request and Employee payslip table structures (at tablet and mobile widths respectively).
  The Payroll Runs structure at 390px was not directly established by 12A, but the same structural
  correction applies consistently to all three wrappers. Actual-page browser verification remains
  deferred until after checkpointing.
- Deployment remains optional (the project is currently designed for local development).

### Phase 12B — List-table containment correction

Status: **Complete.**

- **Pages**: `frontend/src/routes/admin/LeaveRequestListPage.tsx`,
  `frontend/src/routes/employee/PayslipListPage.tsx`, `frontend/src/routes/admin/PayrollRunListPage.tsx`.
- **Root cause**: each table's `sr-only` "Actions" column header is absolutely positioned, and its
  `overflow-x-auto` scroll wrapper had no positioned ancestor, so the header escaped the wrapper's
  clipping and widened the whole page whenever the table was wider than the content column -- the same
  root cause as the Phase 10E `PayrollRunDetailPage` fix.
- **Fix**: `relative` added to each of the three existing wrapper class strings (one word per wrapper);
  table markup, columns, data, links, the `sr-only` Actions text, API calls, state, and permissions are
  unchanged.
- **RED-first evidence**: one structural regression test was added to each page's existing test file
  ("keeps the … table and its visually-hidden Actions header inside a positioned overflow-x-auto
  container"), asserting the table's wrapper has `overflow-x-auto` and `relative`, the Actions column
  header keeps its `sr-only` text, and the wrapper contains it (no pixel/layout assertions, since jsdom
  performs no layout). RED: exactly the 3 new tests failed on `toHaveClass('relative')` while the other 65
  tests in those files passed.
- **Totals**: focused (3 files) -- 68/68; broader group (the 3 page tests plus `App` and `AppLayout`) --
  145/145; full frontend suite (`npx vitest run --no-file-parallelism`) -- **29 test files, 582 tests,
  0 failures, 0 skipped** (previous baseline 579; the increase is exactly the 3 new tests).
  `npm run build` succeeded. Backend untouched and not rerun (remains 571 tests, 0 failures, 0 errors,
  3 skipped, 13 migrations).
- No backend, database, dependency, lockfile, migration, API-module, formatter, route, sidebar, or
  configuration change.
- **Real-page manual verification and cleanup** (performed after the Phase 12B checkpoint commit; this
  supersedes the earlier "deferred until after checkpointing" status):
  - **Scope**: the actual committed routes `/admin/leave`, `/admin/payroll`, and `/employee/payslips` were
    exercised in a real browser against a live backend/frontend/PostgreSQL stack. The Phase 12A browser work
    had been only an isolated DOM/CSS reproduction; Phase 12B now has actual-page browser evidence.
  - **Authorization**: logged-out route protection, HR_ADMIN access to both admin lists, EMPLOYEE blocked
    from both admin lists, EMPLOYEE access to their own payslip list, and HR_ADMIN blocked from the employee
    payslip list all passed; no cross-role data leakage occurred.
  - **Containment**: all three corrected pages passed at 1440x900, 768x1024, 390x844, and 200% browser
    zoom -- no whole-page horizontal overflow; wide tables scrolled only inside their own containers; the
    Actions columns and Review/View links remained reachable; row actions were keyboard-accessible with
    visible focus; the expected fixture rows and monetary values rendered correctly.
  - **Browsers and regression**: Chrome and Firefox passed; Safari is unavailable on this Windows
    environment and is recorded as **not applicable**, not failed. The payroll-detail containment regression
    check (on the fixture payroll run's detail page) passed, and quick overflow sanity checks of the HR Admin
    dashboard, Employee dashboard, and Reports page passed.
  - **Evidence classification**: real-page layout/overflow, the responsive sizes, keyboard reachability,
    rendered values, and role access were manually verified. The structural wrapper assertions remain
    automated coverage. Pagination, filter behavior, mutation flows, error redaction, stale-response/race
    behavior, and unrelated feature flows were not manually retested and are not claimed as manually observed.
  - **Temporary fixtures**: prefix `MANUAL_12B_20260928`, exactly six rows -- 1 employee, 2 users (one
    HR_ADMIN, one EMPLOYEE linked to that employee), 1 PENDING leave request, 1 COMPLETED August 2026
    payroll run, and 1 payslip -- tracked in an external cleanup ledger outside the repository. Existing
    department 332 and leave type 1 were reused read-only; no leave balance, salary structure, attendance,
    department, leave type, or PDF fixture was created, and no existing row was modified.
  - **Cleanup**: both recorded Phase 12B server processes were stopped by verified task/PID identity; all
    six rows were deleted by exact recorded id in one FK-safe transaction, with every `DELETE ... RETURNING`
    set matching before commit (no prefix-only deletion, no sequence reset, no audit-row deletion).
    Domain counts returned exactly to the baseline: users 3, employees 2, departments 1, leave_types 2,
    leave_balances 1, leave_requests 4, payroll_runs 0, payslips 0, salary_structures 0, attendance 0.
  - **Audit behavior**: four expected `LOGIN_SUCCESS` audit rows were created (ids 6312 and 6313 during
    setup verification, 6314 and 6315 during manual testing); no domain-mutation audit row was created. All
    four rows were retained -- no audit row was deleted or manually edited. Deleting the temporary users caused
    PostgreSQL to set those rows' `actor_id` values to NULL through the existing `ON DELETE SET NULL` foreign
    key; their `action`, `entity_name`, `entity_id`, `details`, and `created_at` remained unchanged.
  - **Services and ledger**: ports 8080, 5173, and 5174 were free after cleanup; both PostgreSQL containers
    remained healthy and unrelated processes/containers were untouched. The external cleanup ledger and logs
    were preserved outside the repository; the temporary credential and hash files were removed; the external
    payroll-PDF directory remained empty.
  - **Automated baselines**: frontend remains 582 tests across 29 files, 0 failures, 0 skipped, with a
    successful build; backend remains 571 tests, 0 failures, 0 errors, 3 pre-existing skips, 13 migrations
    (not rerun for Phase 12B). Tests and builds were not rerun during manual verification, cleanup, or this
    documentation update.

**Phase 12B: Complete.**

Phase 12 remains **In progress** overall.

### Phase 12C — Opt-in local demo data

Status: **Complete.**

#### Phase 12C-1 — Scope and design approval

- **Purpose**: provide an opt-in, recruiter-friendly local demo dataset so that both the HR Admin and the
  Employee workflows can be demonstrated on a fresh database without direct SQL (closing the Phase 12A
  fresh-clone provisioning gap).
- **Activation** requires both the Spring profile `demo` and `DEMO_DATA_ENABLED=true`; demo data remains
  disabled by default.
- **Logins**: fixed emails `demo.admin@demo.local` (HR_ADMIN) and `demo.employee@demo.local` (EMPLOYEE).
  Passwords must be supplied through `DEMO_ADMIN_PASSWORD` and `DEMO_EMPLOYEE_PASSWORD`, have no defaults,
  require at least 12 characters, and are never logged or included in safe errors.
- **Dataset rules**: readable reserved demo names/codes; the payroll run is CALCULATED; no attendance row,
  no audit row, and no PDF is seeded or generated. No migration, new reporting table, dependency, or
  frontend feature was required.
- **Idempotency**: absent reserved dataset -> create once; complete valid reserved dataset -> skip without
  rewriting; partial/conflicting reserved dataset -> fail safely and roll back.

#### Phase 12C-2 — Demo-data implementation

- **Commit**: `8af10c6b7b72fa8bacd5824cecbad332baff77fb` ("Phase 12C-2: add opt-in demo data profile") --
  12 files (4 modified, 8 added), 1379 insertions, 2 deletions.
  - Modified: `.env.example`, `backend/src/main/java/com/hrpayroll/backend/HrPayrollBackendApplication.java`,
    `backend/src/main/java/com/hrpayroll/backend/leave/LeaveBalance.java`,
    `backend/src/main/resources/application.yml`.
  - Added production classes (`backend/src/main/java/com/hrpayroll/backend/demo/`):
    `DemoDataConflictException.java`, `DemoDataProperties.java`, `DemoDataRunner.java`,
    `DemoDataSeeder.java`, `DemoIdentities.java`.
  - Added tests: `backend/src/test/java/com/hrpayroll/backend/demo/DemoDataRunnerTest.java`,
    `backend/src/test/java/com/hrpayroll/backend/demo/DemoDataSeederTest.java`,
    `backend/src/test/java/com/hrpayroll/backend/leave/LeaveBalanceTest.java`.
  - No frontend, README, migration, dependency, or lockfile change.
- **Design**:
  - `DemoDataRunner` is guarded by the `demo` profile and `app.demo.enabled=true`, and runs on
    `ApplicationReadyEvent`, after the normal application runners (including the bootstrap-admin runner).
  - `DemoDataProperties` validates that both passwords are present and at least 12 characters.
  - `DemoDataSeeder` checks the reserved identities before writing, then creates the complete dataset in one
    transaction. A complete valid dataset is a no-op; partial/conflicting reserved data raises
    `DemoDataConflictException` with a fixed safe message and rolls back.
  - Passwords are BCrypt-hashed through the application's `PasswordEncoder` and never logged. The existing
    Annual Leave and Sick Leave types are reused by name. A validated public `LeaveBalance` constructor was
    added. Payroll values are produced through the real `PayrollCalculator` path.
  - No controller, endpoint, audit-writing behavior, attendance seed, or PDF-writing behavior was added.
- **Dataset** (15 rows): 2 departments (Demo Engineering, Demo Finance); 2 ACTIVE employees (DEMO-001 Demo
  Employee One, DEMO-002 Demo Employee Two); 2 users (the EMPLOYEE user linked to DEMO-001); 2 salary
  structures; 2 leave balances (DEMO-001: Annual 14 entitled / 2 used, Sick 14 / 0); 2 leave requests
  (DEMO-001: one APPROVED 2-day and one PENDING 3-day Annual request); 1 previous-month CALCULATED payroll
  run; 2 PENDING payslips with NULL PDF paths.
  - Payslip values: employee 1 gross 4750.00, deductions 793.25, net 3956.75; employee 2 gross 5900.00,
    deductions 985.30, net 4914.70; total gross 10650.00.
- **RED/GREEN**: RED was a genuine compile-time failure caused only by the missing demo production types
  and the missing validated `LeaveBalance` constructor. Focused tests 41/41; broader affected group 71/71;
  full backend suite **612 tests, 0 failures, 0 errors, 3 pre-existing skips**, 13 Flyway migrations
  (previous baseline 571; the increase is exactly the 41 new tests). The frontend was unchanged and not
  rerun; its latest baseline remains 582 tests across 29 files, 0 failures, 0 skipped, with a successful
  production build.
- **Shared development-database qualification**: the full backend `clean verify` appended 176 audit rows
  through pre-existing non-demo integration tests. None matched the demo identities and none came from demo
  seeding; business-table data and audit rows through id 6315 remained unchanged. This is the project's
  existing shared-integration-test-database behavior, not a Phase 12C demo-seeding defect. The development
  database as a whole is not claimed to be byte-for-byte unchanged after the test suite.

#### Phase 12C-3 — Isolated live smoke verification

- **Isolation**: the live test used a completely separate, disposable PostgreSQL environment -- container
  `hr_payroll_demo_smoke_20260928`, named volume `hr_payroll_demo_smoke_20260928_data`, database
  `hr_payroll_demo_smoke`, a dedicated database user, loopback port 5435 -- with separate logs, secret files,
  PDF directory, and cleanup ledger outside the repository. The development database was neither queried
  nor reused (nor its volume), and the Foode and blockchain resources were not touched. The backend ran on
  8080 (log-verified JDBC URL `jdbc:postgresql://localhost:5435/hr_payroll_demo_smoke`) and the frontend on
  5174 with strict port binding.
- **First startup**: all 13 Flyway migrations applied to the empty smoke database; exactly 15 demo rows were
  created; 0 attendance rows and 0 audit rows before login; the exact monetary values and totals matched;
  both payslips were PENDING with NULL PDF paths; no PDF file was created. The backend logs contained no
  password, hash, JWT, stack trace, ERROR, or WARN line.
- **Restart/idempotency**: the backend was stopped and restarted against the same smoke database; the log
  reported the dataset already present with nothing created or changed. All row ids, password-hash
  checksums, relevant timestamps, and the dataset checksum (`3075152e0f90fe1ba4e5d1210a80c0bd`) were
  unchanged; no audit, attendance, PDF, password rehash, or business-row rewrite occurred.
- **API verification**: health UP; CORS allowed `http://localhost:5174` and rejected
  `http://localhost:5173`; unauthenticated protected requests returned 401; both demo logins succeeded; all
  tested cross-role requests returned 403; the Admin and Employee read-only endpoints returned the expected
  values; employee attendance returned `NOT_CLOCKED_IN`. The self-service payslip endpoint used was the
  committed `/api/employees/me/payslips`. No mutation endpoint other than login was called.
  - Optional-status qualification: Admin leave "all statuses" is expressed by omitting the optional
    `status` parameter; `status=ALL` returns 400 because `ALL` is not an enum value. The verifier corrected
    this by omitting the parameter. This is expected API behavior, not a defect.
- **Manual browser results** (user-reported, all PASS):
  - **Authorization**: logged-out Admin and Employee route protection; HR Admin blocked from Employee pages;
    Employee blocked from Admin pages; no cross-role data leakage.
  - **HR Admin**: dashboard active total 2, Demo Engineering 1, Demo Finance 1, pending leave total 1, latest
    payroll run August 2026 CALCULATED; both demo employees rendered; pending and approved leave requests
    rendered correctly; payroll list/detail showed both exact payslips; PDFs remained unavailable; Reports
    annual and August totals were RM 10,650.00 (on the Reports page, not the dashboard); missing months were
    not synthesized; headcount values were correct; no employee-level Reports data appeared.
  - **Employee**: DEMO-001 profile rendered correctly; attendance showed "Not clocked in today"; Annual
    balance 14 entitled / 2 used / 12 remaining; Sick balance 14 / 0 / 14; approved and pending requests
    rendered correctly; the August 2026 payslip showed RM 3,956.75 net pay; only the linked employee's data
    appeared; DEMO-002 salary/identity did not leak; the PDF remained unavailable.
  - **Persistence, responsive, accessibility, browsers**: results survived refresh and logout/login; Chrome
    and Firefox passed; Safari is unavailable on this Windows environment and is recorded as **not
    applicable**, not failed; 1440x900, 768x1024, 390x844, and 200% zoom passed with no whole-page
    horizontal overflow and tables scrolling inside their containers; keyboard access, visible focus,
    semantic headings/tables, and text-conveyed states passed.
- **Evidence classification**: the browser behavior above was manually observed and reported by the user.
  Database creation, exact values, non-rewriting, and idempotency were verified directly against the smoke
  database. Raw backend-error injection/redaction, concurrency races, and failure injection were not manually
  exercised and remain automated coverage.
- **Audit behavior**: the smoke database held six audit rows (ids 1-6), all `LOGIN_SUCCESS`: ids 1-3 from
  setup/API verification and ids 4-6 from manual browser logins. Their details contained only the
  corresponding login email. No domain-mutation audit row existed, and dashboard/report/list/detail GETs
  created no audit rows. These ids belonged only to the disposable smoke database and ceased to exist when
  its volume was removed; they are not development-database audit rows.
- **Cleanup**:
  - The backend and frontend processes were stopped by verified task/PID identity. Recorded process-chain
    qualification: Node PID 38372 was the frontend `npx` wrapper (omitted from the originally recorded
    chain) and exited naturally with the frontend task.
  - After exact identity re-verification, only the smoke container and then only the smoke volume were
    removed. No broad prune, network removal, image removal, or cleanup SQL occurred.
  - The transient database-password and demo-password files, the handoff file, and the secret-generation
    script were deleted. The external ledger and logs were preserved, and the external payroll-PDF directory
    remained empty.
  - Ports 8080, 5174, and 5435 were free afterwards. The development and unrelated containers/volumes
    remained healthy with unchanged identities, and the repository remained clean.
- **Automated baselines**: tests and builds were not rerun during Phase 12C-3, cleanup, or this
  documentation update. Backend remains 612 tests, 0 failures, 0 errors, 3 pre-existing skips, 13
  migrations; frontend remains 582 tests across 29 files, 0 failures, 0 skipped, with a successful build.

**Phase 12C: Complete.**

**Phase 12 remains In progress overall.** Phase 12D README/setup documentation has not started.

## Phase 13
Status: Not started.
