-- Removes the Phase 2 throwaway table used only to prove Flyway was wired
-- correctly to PostgreSQL. It carried no business meaning. V1 itself is
-- never edited -- this is a new migration, as required.
DROP TABLE flyway_verification;
