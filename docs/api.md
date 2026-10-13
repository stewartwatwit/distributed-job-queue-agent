# REST API

Base path: `/api/v1`. All bodies are JSON. Interactive OpenAPI docs are served by springdoc at
`/swagger-ui.html`, with the raw document at `/v3/api-docs`.

## Submitting a job

`POST /api/v1/jobs`

| Field         | Type    | Rules                                   |
|---------------|---------|-----------------------------------------|
| `type`        | string  | required, non-blank, max 64 chars; must be a supported job type |
| `payload`     | object  | required JSON object; shape depends on `type` |
| `maxAttempts` | integer | optional, 1..10 (server default is 3)   |

```bash
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"CHECKSUM","payload":{"text":"hello"},"maxAttempts":3}'
```

```
HTTP/1.1 201
Location: /api/v1/jobs/6f1c0a3e-2f7e-4c55-9d8e-0d0f6c6b1d11
```

```json
{
  "id": "6f1c0a3e-2f7e-4c55-9d8e-0d0f6c6b1d11",
  "type": "CHECKSUM",
  "status": "QUEUED",
  "attempts": 0,
  "maxAttempts": 3,
  "payload": {"text": "hello"},
  "result": null,
  "lastError": null,
  "createdAt": "2026-01-01T12:00:00Z",
  "updatedAt": "2026-01-01T12:00:00Z",
  "startedAt": null,
  "finishedAt": null
}
```

The job is validated (type known, payload accepted by the handler) before anything is stored, so a
rejected request never creates a job. A `201` means the job is durably stored; it is normally
`QUEUED` already. If Redis was unavailable at that instant the job is still stored as `QUEUED` and
is re-enqueued later rather than failing the request.

## Reading jobs

```bash
# full job
curl http://localhost:8080/api/v1/jobs/6f1c0a3e-2f7e-4c55-9d8e-0d0f6c6b1d11

# compact status, cheap to poll
curl http://localhost:8080/api/v1/jobs/6f1c0a3e-2f7e-4c55-9d8e-0d0f6c6b1d11/status
# {"id":"6f1c...","status":"COMPLETED","attempts":1,"updatedAt":"2026-01-01T12:00:01Z"}

# list, newest first
curl 'http://localhost:8080/api/v1/jobs?status=FAILED&page=0&size=20'
```

`GET /jobs` query parameters: `status` (optional, one of the statuses below), `page` (default 0,
>= 0), `size` (default 20, >= 1, values above 100 are capped at 100). Response:

```json
{"items": [ /* JobResponse */ ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

`result` is populated once the job is `COMPLETED`; `lastError` holds the most recent failure
message (kept while a retry is pending and on terminal failure).

## Job statuses

| Status       | Meaning |
|--------------|---------|
| `PENDING`    | Persisted, not yet confirmed in the queue (transient during submit) |
| `QUEUED`     | Waiting for a worker, including waiting for a retry |
| `PROCESSING` | Claimed by a worker |
| `COMPLETED`  | Finished successfully (terminal) |
| `FAILED`     | Gave up: attempts exhausted or non-retryable error (terminal) |

## Job types and payloads

| Type       | Payload                | Result                | Notes |
|------------|------------------------|-----------------------|-------|
| `ECHO`     | any JSON object        | `{"echo": <payload>}` | smoke testing |
| `SLEEP`    | `{"millis": 0..60000}` | `{"sleptMillis": n}`  | simulates slow work |
| `CHECKSUM` | `{"text": "<string>"}` | `{"sha256": "<hex>"}` | SHA-256 of the UTF-8 text |

## Errors

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem document with
`Content-Type: application/problem+json`.

```bash
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' -d '{"type":"","payload":{}}'
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/jobs",
  "errors": [{"field": "type", "message": "must not be blank"}]
}
```

| Status | When |
|--------|------|
| 400 | Bean validation failure (`errors` lists each field), malformed JSON, non-object payload, unknown job type, payload rejected by the job type, invalid `status` filter, bad `page`/`size`, malformed job id |
| 404 | No job with that id (or unknown route) |
| 405 | Wrong HTTP method |
| 500 | Unexpected failure; the body is generic (`"Internal server error"`) and never contains stack traces or internals, which are only logged server-side |
