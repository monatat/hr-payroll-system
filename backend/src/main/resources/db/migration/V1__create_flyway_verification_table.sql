-- Phase 2 foundation only: proves Flyway is wired to PostgreSQL correctly.
-- Carries no business meaning and will be dropped by a Phase 3 migration once
-- the real schema is introduced. Never edit this file after it has been
-- applied to a database -- create a new migration instead.
CREATE TABLE flyway_verification (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    verified_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO flyway_verification (verified_at) VALUES (now());
