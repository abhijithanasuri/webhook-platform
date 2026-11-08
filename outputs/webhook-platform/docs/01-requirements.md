# Requirements and scope

## Confirmed requirements

- Java + Spring Boot backend; local Docker first, AWS CDK deployment design.
- DynamoDB storage; remaining components selected for simplicity and reliability.
- Multiple isolated customer accounts, one owner per account initially.
- Email/password owner login, Cognito in AWS; API keys for event publishers.
- Small dashboard: endpoints, subscriptions, keys, events, attempts, and replay.
- Event-type subscriptions with independent delivery to each matching endpoint.
- HMAC-signed requests, retry history, manual replay, and failure simulation receiver.
- JSON event body up to 65,536 UTF-8 bytes. Reject oversized bodies before parsing.
- Ten-second total outbound request deadline, including DNS, connect, TLS, and body handling.
- Eight total delivery attempts over approximately 24 hours, subject to service availability.
- Seven-day payload availability, measured from original event acceptance.
- Test targets: 10 accepted events/second sustained and bursts to 50; not an SLA.
- Development capacity: 24 hours/week. Portfolio-first spending; no paid deployment until explicitly initiated.

## Proposed implementation defaults

These decisions complete the design but are adjustable without changing the project goal.

- Ten endpoints/account, twenty exact event-type subscriptions/endpoint; no wildcard routing initially.
- HTTPS on port 443 only in AWS; no custom request headers initially.
- At most 10 deliveries/event, so test workloads must report fan-out as well as event rate.
- POST publisher requests require an idempotency key; seven-day deduplication window.
- API key permissions: publish only. Dashboard management uses owner access tokens.
- Thirty-day delivery/attempt metadata retention, excluding raw payload and response bodies.
- No ordering guarantee, no exactly-once claim, no team invitations, billing, or multi-region failover.
- Markdown/Mermaid documentation. Four weeks is a planning estimate, not a promised deadline.

## Product behavior

1. Owner registers/signs in, creates an account, registers an endpoint, and subscribes it to exact event types.
2. Endpoint URL and subscriptions are versioned. Publisher submits an event using an account-scoped key.
3. API atomically persists the event, deduplication record, and one delivery/outbox item per matching endpoint before returning 202.
4. Zero matches still returns 202 with matchedEndpoints=0. Event remains visible; later subscriptions do not receive it automatically.
5. Each endpoint has its own delivery status and retry budget. One failure does not block another endpoint.
6. Replay creates a new delivery with a new delivery ID linked to its source; event ID and payload stay unchanged. Replay requires an unexpired payload and an enabled endpoint. It uses current endpoint configuration, explicitly shown in the UI.
7. Disabling/deleting an endpoint stops unclaimed deliveries. A request already in flight may finish. Existing delivery URL snapshots do not change after URL edits; replay uses the new URL.

## Acceptance criteria

- Cross-account reads and changes are rejected, including requests containing another tenant's resource ID.
- Same key plus same event type/body returns the same acceptance result; a conflicting body returns 409.
- Acceptance survives a process crash before queue publication; recovery discovers the persisted delivery.
- Duplicate queue messages do not normally produce concurrent attempts; receiver deduplication remains necessary after ambiguous failures.
- Receiver verifies signatures and demonstrates success, retry, timeout, and duplicate handling.
- Attempts stop at success, permanent rejection, eight consumed attempts, or payload expiry.
- Owner can inspect attempt timing and sanitized failure reasons, and replay eligible deliveries.
- Payload cannot be fetched, dispatched, or replayed after seven days even when DynamoDB has not physically deleted it yet.

## Security and readiness boundary

This first version is designed for a controlled demo. Before unrestricted customer onboarding, validate SSRF protection against DNS rebinding, abuse controls, account recovery, secret rotation, backup/restore, and incident procedures. These are concrete release gates, not implemented capabilities.
