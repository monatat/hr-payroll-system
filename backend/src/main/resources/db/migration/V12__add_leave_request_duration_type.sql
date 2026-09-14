ALTER TABLE leave_requests
    ADD COLUMN duration_type VARCHAR(10) NOT NULL DEFAULT 'FULL_DAY';

ALTER TABLE leave_requests
    ADD CONSTRAINT chk_leave_requests_duration_type
        CHECK (duration_type IN ('FULL_DAY', 'HALF_DAY'));

ALTER TABLE leave_requests
    ADD CONSTRAINT chk_leave_requests_half_day_single_date
        CHECK (duration_type <> 'HALF_DAY' OR start_date = end_date);

-- The DEFAULT is kept permanently (not dropped after backfill): it backfills
-- every pre-existing row to FULL_DAY in this same statement, and continues
-- to safely cover direct fixture/test inserts and any transitional client
-- that omits duration_type, exactly like the existing `version BIGINT NOT
-- NULL DEFAULT 0` column on this same table.
