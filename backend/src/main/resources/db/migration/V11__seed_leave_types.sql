-- Data-only seed migration -- no schema changes. Populates the two Phase 7
-- leave types demo/portfolio use needs to submit a request end-to-end.
-- "Unpaid Leave" is deliberately not seeded here: the v1 balance-based
-- approval model (entitled/used days, insufficient-balance rejection) has
-- no natural meaning for unpaid leave without special-case behavior, which
-- is out of Phase 7 scope.
INSERT INTO leave_types (name, default_annual_entitlement_days) VALUES
    ('Annual Leave', 14.0),
    ('Sick Leave', 14.0);
