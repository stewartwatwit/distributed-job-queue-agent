CREATE TABLE jobs (
    id               UUID         PRIMARY KEY,
    type             VARCHAR(64)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts         INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts     INT          NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    last_error       TEXT,
    result           JSONB,
    locked_by        VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    queued_at        TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

-- Sweeper: find stale PENDING/QUEUED jobs.
CREATE INDEX idx_jobs_status_queued_at ON jobs (status, queued_at);
-- Sweeper: find expired leases without indexing every finished job.
CREATE INDEX idx_jobs_expired_lease ON jobs (lease_expires_at) WHERE status = 'PROCESSING';
-- Listing newest jobs first.
CREATE INDEX idx_jobs_created_at ON jobs (created_at DESC);
