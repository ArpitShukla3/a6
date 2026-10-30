-- Tenants (corporate clients). brake_missing_ratio overrides the global safety-brake threshold.
CREATE TABLE tenant (
    tenant_id            UUID PRIMARY KEY,
    name                 TEXT NOT NULL,
    brake_missing_ratio  NUMERIC(5,4)
);

CREATE TABLE office (
    tenant_id    UUID NOT NULL REFERENCES tenant(tenant_id),
    office_code  TEXT NOT NULL,
    PRIMARY KEY (tenant_id, office_code)
);

CREATE TABLE employee (
    employee_id          UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant(tenant_id),
    external_id          TEXT NOT NULL,
    -- HR-owned fields
    name                 TEXT,
    email                TEXT,
    phone                TEXT,
    manager_external_id  TEXT,
    office_code          TEXT,
    shift_code           TEXT,
    status               TEXT NOT NULL,             -- ACTIVE | INACTIVE
    deactivated_by       TEXT,                      -- HR | RECONCILIATION | NULL
    hr_version           BIGINT NOT NULL,
    hr_digest            BIGINT NOT NULL,           -- hash(external_id + canonical HR fields), computed in Java
    hr_source_ts         TIMESTAMPTZ NOT NULL,      -- source-clock time of the last applied HR change
    bucket               SMALLINT NOT NULL,         -- hash(external_id) mod bucket count, computed in Java
    -- Transport-admin-owned fields
    pickup_point_id      TEXT,
    special_assistance   TEXT,
    admin_version        BIGINT NOT NULL DEFAULT 0,
    pickup_needs_review  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_ext UNIQUE (tenant_id, external_id)
);
CREATE INDEX ix_employee_bucket ON employee (tenant_id, bucket);
CREATE INDEX ix_employee_status ON employee (tenant_id, status);

-- Transactional outbox: written in the same transaction as the employee change.
CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,
    tenant_id     UUID NOT NULL,
    aggregate_id  UUID NOT NULL,
    event_type    TEXT NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error    TEXT
);
CREATE INDEX ix_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

-- Per-record error report. Field name + code only, never the submitted value.
CREATE TABLE ingest_rejection (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    source        TEXT NOT NULL,                    -- BATCH | SNAPSHOT
    ref_id        UUID NOT NULL,                    -- batch id or reconciliation run id
    record_index  INT NOT NULL,
    external_id   TEXT,
    field         TEXT NOT NULL,
    error_code    TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rejection UNIQUE (tenant_id, ref_id, record_index, field, error_code)
);

CREATE TABLE reconciliation_run (
    run_id               UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    status               TEXT NOT NULL,   -- RECEIVED|DIFFING|HELD|APPLYING|COMPLETED|REJECTED|FAILED
    file_path            TEXT NOT NULL,
    as_of                TIMESTAMPTZ,
    expected_rows        INT,
    rows_read            INT NOT NULL DEFAULT 0,
    rows_rejected        INT NOT NULL DEFAULT 0,
    truncated            BOOLEAN NOT NULL DEFAULT FALSE,
    active_before        INT NOT NULL DEFAULT 0,
    to_create            INT NOT NULL DEFAULT 0,
    to_update            INT NOT NULL DEFAULT 0,
    to_deactivate        INT NOT NULL DEFAULT 0,
    missing_ratio        NUMERIC(6,5),
    threshold            NUMERIC(5,4),
    brake_reasons        TEXT,
    applied              INT NOT NULL DEFAULT 0,
    stale                INT NOT NULL DEFAULT 0,
    deactivated          INT NOT NULL DEFAULT 0,
    diff_millis          BIGINT,
    error_code           TEXT,
    decided_by           TEXT,
    decided_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- At most one run per tenant may be in flight.
CREATE UNIQUE INDEX uq_run_in_flight ON reconciliation_run (tenant_id)
    WHERE status IN ('RECEIVED', 'DIFFING', 'APPLYING');

CREATE TABLE reconciliation_candidate (
    run_id              UUID NOT NULL REFERENCES reconciliation_run(run_id),
    employee_id         UUID NOT NULL,
    hr_version_at_diff  BIGINT NOT NULL,
    PRIMARY KEY (run_id, employee_id)
);

CREATE TABLE seat (
    seat_id      UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    trip_code    TEXT NOT NULL,
    employee_id  UUID
);

CREATE TABLE booking (
    booking_id     UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    employee_id    UUID NOT NULL REFERENCES employee(employee_id),
    seat_id        UUID NOT NULL REFERENCES seat(seat_id),
    pickup_at      TIMESTAMPTZ NOT NULL,
    status         TEXT NOT NULL,          -- CONFIRMED | CANCELLED
    cancel_reason  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_booking_employee ON booking (employee_id, status, pickup_at);
