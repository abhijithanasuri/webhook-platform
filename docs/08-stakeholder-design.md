# Design Document: Reliable Webhook Delivery Platform

**Audience:** Product and business stakeholders · **Status:** Agreed — review complete · **Date:** 6 November 2026

**Summary:** Give customers one place to publish business events, deliver them to subscribed systems, and understand or recover failed deliveries.

## Background

Applications often need to tell other systems when something happens: an invoice is paid, an order is shipped, or a subscription changes. A webhook sends that notification to a customer-provided web address.

For example, an invoicing application publishes `invoice.paid` to an accounting system and a notification service. Delivery to either destination must continue independently if the other is unavailable.

The proposed service begins as a controlled portfolio demo using synthetic data, with a path toward customer use. Local services are prepared; the application is not implemented or deployed to AWS. Performance is unmeasured.

## Problem Statement

Receivers may be unavailable, respond slowly, or process a request before the sender loses the response. Without a durable delivery record, operators cannot reliably tell which events need another attempt.

The platform addresses three needs:

- **Continuity:** temporary receiver failures should not require the publishing application to manage delivery retries.
- **Visibility:** account owners need a clear record of successful, pending, and failed deliveries.
- **Control and trust:** customers need isolated accounts, verifiable notifications, and a deliberate way to replay eligible deliveries.

## Goal

Provide a simple workflow: **configure destinations → publish an event → inspect delivery → recover when needed**.

### Initial scope and success criteria

| Goal | What the first version should demonstrate |
| --- | --- |
| Reliable acceptance | Confirm acceptance only after the event and intended deliveries are saved together; recover work after a process crash. |
| Independent delivery | Send each event to every enabled destination subscribed to its exact event type. |
| Failure recovery | Retry temporary failures within a bounded attempt budget; show the outcome and allow eligible manual replay. |
| Customer confidence | Separate account data, sign outgoing requests, and show sanitized attempt history in a small dashboard. |

Events may contain up to **64 KB of JSON**. Each outbound attempt has a **10-second total deadline**; delivery allows up to **eight attempts over approximately 24 hours**. Payload availability ends **seven days after original acceptance**. Proposed initial limits are ten destinations per account and twenty exact event types per destination; delivery metadata would remain for thirty days.

The initial test targets are 10 incoming events/second sustained and bursts to 50. These are unmeasured targets, not service commitments; one event can generate up to ten outbound deliveries. Billing, team permissions, guaranteed ordering, and multi-region operation are outside the initial scope.

## Approaches

### Options considered

| Approach | Benefit | Limitation | Assessment |
| --- | --- | --- | --- |
| Send notifications during the publish request | Few components and an immediate attempt. | Slow receivers delay acceptance; recovery after a crash is difficult. | Does not meet the recovery goal. |
| Save the event, then independently enqueue deliveries | Separates acceptance from delivery work. | A crash between saving and enqueueing can leave accepted events without scheduled delivery. | Needs a recovery mechanism. |
| Save events and delivery work together, then deliver asynchronously | Accepted work remains discoverable after a crash; each destination has its own outcome. | Adds a dispatcher and introduces some delivery delay. | **Recommended; existing design baseline.** |

### Recommended approach

![Proposed AWS architecture](high-level-overview.png)

*Read across the top, then across the bottom. “Event Store” groups the database tables. Dashboard management also passes through API Gateway; that connection is simplified in this overview. This is a proposed AWS design.*

1. **Configure:** the account owner signs in, registers destinations, and chooses event types.
2. **Accept:** a customer application publishes an event using its API key. The service saves the event and a delivery record for each matching destination before acknowledging it.
3. **Deliver:** a background dispatcher finds due work; a queue passes it to delivery workers, which send signed HTTPS requests.
4. **Recover and explain:** workers record each attempt. Temporary failures receive a later retry; permanent failures stop. Owners inspect history and can create a new replay delivery while the original payload is available.

![Temporary failure and successful retry](delivery-retry-overview.png)

*Illustrative example, not a measured result: one destination returns a temporary error, then accepts the retry. Other destinations continue independently.*

### Customer-visible behavior and tradeoffs

- **Accepted means saved, not delivered.** A one-minute dispatch cycle can add about a polling interval, plus queueing and startup time. We make no instant-delivery promise.
- **Duplicates are possible.** A receiver may process a request before the sender loses the response. Receivers must use the event ID to avoid repeating the same business action; delivery order is not guaranteed.
- **Retries are bounded.** Temporary network failures and selected HTTP errors are retried. Permanent rejections stop; redirects are not followed. Availability and retry instructions can extend the approximate 24-hour window.
- **Replay is explicit.** It creates a new delivery using the current enabled destination configuration, preserves the original event, and does not extend its seven-day availability. The dashboard must show the destination and duplicate-processing risk before replay.

Planned stack: Java/Spring Boot on three AWS Lambdas, DynamoDB, SQS, Cognito login, and a React dashboard hosted on S3/CloudFront. Local development uses Docker, LocalStack, and Keycloak. The appendix links to the full AWS design.

Cost depends on traffic, destinations per event, retries, and retention; no monthly estimate is asserted. Rollout starts locally, followed by an explicitly initiated AWS smoke test. General customer access requires destination-security, abuse-control, and backup/recovery validation.

## API Documentation — Initial Draft

All routes start with `/v1`. **Owner** means the signed-in account owner; **Publisher** means an application using an account-scoped API key. Account identity comes from credentials. Request schemas and response details remain subject to implementation review.

| Capability | Method and route | Access / behavior |
| --- | --- | --- |
| Set up account | `POST /account` | Owner; create the account once. |
| Manage destinations | `POST /endpoints`, `GET /endpoints` | Owner; create/list destinations and subscriptions. |
| Update/remove destination | `PATCH /endpoints/{id}`, `DELETE /endpoints/{id}` | Owner; version-checked updates or disable/remove. |
| Rotate signing secret | `POST /endpoints/{id}/rotate-secret` | Owner; show the new secret once. |
| Manage publisher keys | `POST /api-keys`, `GET /api-keys`, `DELETE /api-keys/{id}` | Owner; create/list/revoke keys; show new key secrets once. |
| Publish event | `POST /events` | Publisher; requires `Event-Type` and `Idempotency-Key`. |
| Inspect events | `GET /events`, `GET /events/{id}` | Owner; metadata and payload while available. |
| Inspect deliveries | `GET /deliveries`, `GET /deliveries/{id}`, `GET /deliveries/{id}/attempts` | Owner; status and paginated history; optional event filter. |
| Replay delivery | `POST /deliveries/{id}/replays` | Owner; requires an idempotency key, available payload, and enabled destination. |

### Example: publish an invoice event

```javascript
POST /v1/events
Authorization: Bearer whk_example.syntheticSecret
Content-Type: application/json
Event-Type: invoice.paid
Idempotency-Key: invoice-1042-paid-v1

{"invoiceId":"1042","amount":12500,"currency":"INR"}
```

After durable acceptance, return **202 Accepted**:

```json
{"eventId":"evt_example","matchedEndpoints":2,"acceptedAt":"2026-11-06T09:00:00Z"}
```

Repeating the same live idempotency key with the same event type/body returns the original acceptance result; conflicting content returns **409**. No matching destinations still returns **202** with a match count of zero. Lists use a bounded page size and continuation cursor.

Errors include **400/413** for invalid/oversized input, **401/403** for credential or permission failures, **404** for unavailable or foreign-account resources, **410** for expired payload access, **429** for throttling, and **503** when durable acceptance cannot be confirmed. Clients retry uncertain publish requests with the same idempotency key. Error bodies contain a code, message, and request ID.

## Appendix

### Terms and open decisions

- **Event:** the original business notification. **Delivery:** sending that event to one destination. **Attempt:** one outbound request for a delivery.
- **Open implementation details:** exact signing format and receiver test vectors; framework/Lambda adapter compatibility; event lookup/pagination; race-condition and destination-security validation.
- **Planning:** the existing four-week implementation outline is an estimate based on 24 hours/week, to be revised after the first working slice.

### Supporting documents

- [Supporting document](https://app.notion.com/p/40f6baeb2fb08129b670e1d1b5b5bb50)
- [Supporting document](https://app.notion.com/p/40f6baeb2fb0816ba8e4e1c10a86d206)
- [Supporting document](https://app.notion.com/p/40f6baeb2fb081518bb8cc4747288ed4)
- [Supporting document](https://app.notion.com/p/40f6baeb2fb08183a1c7de817b63b6ea)
- [Supporting document](https://app.notion.com/p/40f6baeb2fb081f1b21ce6a91162768b)
- [Supporting document](https://app.notion.com/p/40f6baeb2fb0814d84ccd89ea74d1f9c)
