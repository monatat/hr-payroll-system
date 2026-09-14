CREATE TABLE audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id BIGINT,
    action VARCHAR(50) NOT NULL,
    entity_name VARCHAR(50) NOT NULL,
    -- entity_id intentionally has no FK: it is a polymorphic reference whose
    -- target table is identified by entity_name.
    entity_id BIGINT,
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id)
        REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_name, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);
