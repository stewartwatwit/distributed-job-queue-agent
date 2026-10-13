-- The sweeper's stale scan filters and orders by updated_at; V1's (status, queued_at) index cannot serve it.
DROP INDEX idx_jobs_status_queued_at;
CREATE INDEX idx_jobs_status_updated_at ON jobs (status, updated_at);
