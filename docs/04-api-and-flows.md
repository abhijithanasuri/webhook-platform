# API contracts and delivery flows

All paths are under /v1. IDs are opaque; timestamps are UTC ISO-8601. Tenant identity comes from the verified credential. Use structured errors with code, message, requestId; never include secrets or payloads in errors. List endpoints take limit (default 25, maximum 100) and opaque cursor.

| Method and path | Auth | Behavior |
|---|---|---|
| POST /account | Owner JWT | Idempotently create one account for owner sub |
| GET /account | Owner JWT | Return caller account; 404 before account creation |
| POST /endpoints | Owner JWT | Create endpoint, subscriptions, one-time signing secret |
| GET /endpoints | Owner JWT | List tenant endpoints |
| GET /endpoints/{id} | Owner JWT | Read caller-owned endpoint metadata; foreign/missing IDs return 404 |
| PATCH /endpoints/{id} | Owner JWT | Version-checked URL/subscription/enabled update |
| DELETE /endpoints/{id} | Owner JWT | Soft-delete/disable, retain historical references |
| POST /endpoints/{id}/rotate-secret | Owner JWT | Return new signing secret/version once |
| POST /api-keys | Owner JWT | Return publisher key once |
| GET /api-keys | Owner JWT | List key metadata only |
| DELETE /api-keys/{id} | Owner JWT | Revoke publisher key |
| POST /events | Publisher key | Accept JSON payload; Event-Type and Idempotency-Key headers required |
| GET /events | Owner JWT | List event metadata |
| GET /events/{id} | Owner JWT | Metadata and available payload; expired payload returns 410 |
| GET /deliveries | Owner JWT | Tenant history, optional eventId query |
| GET /deliveries/{id} | Owner JWT | Delivery and source/replay links |
| GET /deliveries/{id}/attempts | Owner JWT | Paginated attempt history |
| POST /deliveries/{id}/replays | Owner JWT | New delivery, requires Idempotency-Key and live payload |

For expired payloads, delivery metadata remains available separately. Resource lookups verify tenant ownership before returning details; foreign resource IDs return 404.

Implemented account contract: POST /account accepts no request body and returns 200 with tenantId and createdAt, both for initial creation and repeated calls. GET /account returns the same caller-scoped representation. Ownership uses verified issuer plus subject, not request-supplied account IDs. See 11-account-management.md for transaction and test details.

Endpoint create/read/list and mutations are implemented locally. Creation returns 201 with metadata and one-time signingSecret; GET responses exclude secrets. PATCH requires JSON version and changed url/eventTypes/enabled fields; rotation requires JSON version and returns a replacement secret once. DELETE requires query parameter version. Stale versions return 409; foreign/missing/deleted IDs return 404. Two successful mutation browser runs were reported by the user on 7 November; detailed terminal output was not captured. See 12-endpoint-management.md for details. Publisher keys and other business routes remain planned.

Example publisher request:

```http
POST /v1/events
Authorization: Bearer whk_publicId.randomSecret
Content-Type: application/json
Event-Type: invoice.paid
Idempotency-Key: invoice-1042-paid-v1

{"invoiceId":"1042","amount":12500,"currency":"INR"}
```

```json
{"eventId":"evt_example","matchedEndpoints":2,"acceptedAt":"2026-11-05T10:00:00Z"}
```

Response is 202 after transaction commit, not after delivery. Validate event-type grammar, key length, JSON syntax and UTF-8 byte size; return 400/413 as appropriate. Return 401/403 for credential/scope failure, 409 for idempotency or configuration-version conflict, 429 for bounded ingress throttling, and 503 when durable acceptance cannot be confirmed. A client retry uses the same idempotency key.

Replay idempotency records are scoped to tenant, source delivery, and operation key. A transactional condition checks original event expiry; write a new delivery with current endpoint snapshot plus its deduplication result. Endpoint configuration version is checked just as for ingestion. Reject replay with 410 for expired payload and 409 for disabled/deleted endpoint. Replay does not reset the original event's seven-day clock.

## Accept and deliver

```mermaid
sequenceDiagram
    participant P as Publisher
    participant A as API
    participant D as DynamoDB
    participant S as Dispatcher
    participant Q as SQS
    participant W as Worker
    participant R as Receiver
    P->>A: Typed JSON event and idempotency key
    A->>D: Read routing snapshot
    A->>D: Transaction: version check, event, dedup, deliveries
    D-->>A: Commit
    A-->>P: 202 eventId and match count
    S->>D: Query due delivery outbox records
    S->>Q: Delivery ID and generation
    Q->>W: Wake-up message
    W->>D: Conditional lease and STARTED attempt
    W->>D: Read live payload and endpoint state
    W->>R: Signed HTTPS POST
    R-->>W: 204
    W->>D: Transaction: attempt success and SUCCEEDED
    W-->>Q: Acknowledge
```

## Retry and crash recovery

```mermaid
sequenceDiagram
    participant Q as SQS
    participant W as Worker
    participant R as Receiver
    participant D as DynamoDB
    participant S as Dispatcher
    Q->>W: Delivery generation N
    W->>D: Claim and allocate attempt
    W->>R: Signed POST
    R-->>W: 503
    W->>D: Finish attempt, RETRY_WAIT, dueAt, generation N+1
    W-->>Q: Acknowledge original notification
    Note over D,S: Retry time is durable; no sleeping worker
    S->>D: Query due work after dueAt
    S->>Q: Generation N+1
    Q->>W: Retry notification
    W->>D: Claim lease
    W->>R: POST
    Note over W,D: If worker crashes after send, lease expires
    S->>D: Mark UNKNOWN, release lease, reschedule or fail
    Note over R,D: Receiver must deduplicate: HTTP result may be ambiguous
```

## Dashboard screens

- Account overview: recent delivery counts and latest failures; do not claim real-time aggregate counters before designing their storage.
- Endpoints: URL, exact subscriptions, enabled state, secret rotation.
- API keys: create/show once, list metadata, revoke.
- Events: paginated recent events, fan-out and payload availability.
- Delivery detail: attempts, statuses, next retry, error category, replay confirmation showing destination and possible duplicate business effect.
- Demo controls: controlled receiver success/503/429/timeout modes, local only.
