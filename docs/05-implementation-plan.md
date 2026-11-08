# Implementation plan and validation

Planning capacity: 24 hours/week; approximately four weeks for a focused initial version. Reassess after the first working vertical slice. Dashboard polish and cloud compatibility may extend the schedule.

| Week | Deliverable | Evidence |
|---|---|---|
| 1 | Repository structure, Docker dependencies, identity, tenant auth, endpoint/key management, atomic ingestion | Two tenants isolated; idempotent acceptance; persisted event and fan-out |
| 2 | Dispatcher, SQS adapter, worker, signing, bounded transport, retries and recovery | Success/failure receiver demo; crash recovery; signing fixture |
| 3 | Dashboard, replay, retention, observability, security hardening | Delivery timeline; replay linked to source; expired payload blocked |
| 4 | CDK design implementation, short AWS smoke test, load tests, documentation and demo | Local/cloud differences recorded; measured results; deployment teardown guide |

## Suggested repository layout

```text
backend/
  domain/              # Entities, retry policy, signing contracts
  application/         # Ingestion, dispatch, delivery, replay
  adapters/            # DynamoDB, SQS, HTTP, KMS, identity
  api/                 # Spring Boot controllers and Lambda HTTP adapter
  worker/              # Local poller and Lambda SQS handler
  dispatcher/          # Local schedule and Lambda scheduled handler
dashboard/
demo-receiver/
infra/                 # TypeScript CDK
docs/
docker-compose.yml
```

## Required correctness tests

- Transaction cancellation leaves no partial event/delivery acceptance.
- Racing same-key submissions result in one event; changed payload conflicts.
- Racing endpoint configuration update produces a coherent routing snapshot.
- Cross-tenant access and JWT/API-key scope confusion are rejected.
- Crash before/after queue publication recovers durable work.
- Duplicate notifications and competing workers honor lease/version conditions.
- Crash after receiver success records ambiguity and can redeliver safely to a deduplicating receiver.
- Lease recovery at attempt eight cannot create a ninth allocated attempt.
- Retry classification, jitter bounds, Retry-After dates, timeout, and expiry use a fake clock.
- Replay races and repeated requests create one new delivery per replay idempotency key.
- TTL-delayed records cannot leak expired payloads or trigger delivery.
- Receiver verifies known HMAC vectors; body/header tampering is rejected.
- SSRF tests cover IPv4/IPv6, metadata IPs, mixed DNS answers, rebinding, redirects, encoded hosts, redirects to private addresses, and reconnect behavior.

## Deployment and observability checks

Use local integration tests for the core; AWS smoke tests verify actual Cognito token claims, IAM permissions, Lambda HTTP/SQS adapters, KMS decryption, EventBridge invocation, partial batch behavior, and DynamoDB indexes/transactions. Local emulation is not evidence of full AWS parity.

Record acceptance count/latency, delivery success/failure, attempts, due-work lag, queue oldest age, expired leases, dispatcher errors, DLQ depth, and worker concurrency. Correlate requestId, tenantId, eventId and deliveryId without logging payloads, secrets, or authorization headers. Alarm on durable-work lag and repeated failures, not just queue depth.

Load test both 1 and 10 endpoint fan-out. Suggested first runs: 10 events/s for 15 minutes and 50 events/s for 60 seconds, using synthetic payload sizes. At ten-way fan-out this is 100 and 500 delivery requests/s respectively, so concurrency/backlog and receiver latency matter. Run locally first; a cloud burst requires a deliberate cost review. Publish hardware/configuration, p50/p95/p99 acceptance latency, delivery lag, backlog drain time, error rates, and saturation points. Targets are hypotheses until measured.

Before cloud use: choose a region, validate service/library compatibility, enable budget alerts, set account quotas and concurrency limits, configure log retention, choose a teardown/retention policy, and verify the CDK diff. No cloud resources are created by this documentation task.
