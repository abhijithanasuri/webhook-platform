# Webhook Platform: conversation handoff

## User intent and working preferences

Abhijith Sai Anasuri wants a strong GitHub portfolio project, with a path toward a real customer service. His resume describes 3.5+ years as an Amazon backend engineer (Sep 2021-Apr 2025), Java/TypeScript/AWS, event-driven ingestion, distributed caching, workflow automation and reliability. Resume achievements include 45% latency reduction, roughly 14,000 API requests/day and 23,000 automated requests/week. These are resume claims, not benchmarks for this project. Target positioning is backend/SDE II. Build original code using synthetic data.

He chose the webhook platform first among proposed portfolio projects. He explicitly requested a questionnaire before design, high-level and low-level diagrams, and documentation before implementation. Questionnaire is complete; do not restart it. Latest request is to transfer all information to a dedicated Codex chat. Implementation has NOT been requested yet in this transfer step.

## Explicitly accepted choices

- Java + Spring Boot backend.
- Local Docker development with AWS CDK deployment design.
- DynamoDB; remaining services recommended by assistant.
- Multiple customer accounts, API keys, small dashboard.
- Exact event-type subscriptions, fan-out to every matching endpoint.
- Email/password login, one account owner initially, Cognito in AWS.
- 64 KB JSON events, 10-second delivery timeout, eight attempts over approximately 24 hours, seven-day payload retention.
- Load-test targets of 10 incoming events/second and bursts to 50; unmeasured targets.
- 4 hours/day, 6 days/week; frugal, GitHub portfolio first.
- User accepted proposed baseline: React/TypeScript dashboard, CDK TypeScript, LocalStack DynamoDB/SQS, Lambda/API Gateway, durable outbox, at-least-once unordered delivery, signed requests, retries and replay, Markdown/Mermaid docs.

## Design refinements proposed in documentation

Ten endpoints/account, twenty exact types/endpoint; local Keycloak/OIDC instead of assuming Cognito emulation. Three Lambdas: API, dispatcher, worker. Three DynamoDB tables: Configuration, Events, Deliveries. Delivery records double as outbox. One-minute EventBridge Scheduler invokes dispatcher, which queries a sharded due-work GSI and publishes SQS notifications. Conditional worker leases and generation checks handle duplicates; expired leases record ambiguous attempts and recover. Durable due times support retries beyond SQS's 15-minute delay. Payload access expiry enforced in application because DynamoDB TTL deletion is asynchronous. Metadata retention 30 days. These refinements are documented defaults, not separate user questionnaire answers.

No Step Functions. No Redis, Kubernetes, RDS, VPC/NAT, or per-delivery schedules. Cognito for identity; KMS protects signing secrets; S3/CloudFront hosts dashboard. CloudWatch/SNS operational alerts, separate delivery/scheduler DLQs, IAM and CDK assets accounted for in detailed design. Four-week plan is an estimate. No cloud resources provisioned, no code implementation, no benchmark results, no GitHub repository created.

## Visual/documentation preferences

User first requested every low-level component explicitly mapped to AWS, e.g. ingestion -> Lambda, including supporting resources. This is now in 03-low-level-design.md. Then user requested a very readable, non-comprehensive high-level diagram like an attached LinkedIn system-design infographic. Keep BOTH: simple visual overview and comprehensive technical detail. Use icons and clear flow; avoid overwhelming the overview.

Generated overview is docs/high-level-overview.png and embedded in 02-high-level-design.md. It simplifies all DynamoDB tables into Event Store. Dashboard management actually passes through API Gateway, although the overview draws a simplified connection to ingestion. Clarification is in the document. Generated diagram was visually seen; Mermaid diagrams were authored but not renderer-tested. Links/code fences were checked before the latest AWS detail additions.

## Files to preserve and read

- README.md: documentation index.
- docs/01-requirements.md: confirmed requirements, defaults, acceptance criteria.
- docs/02-high-level-design.md: simple image plus logical/local/AWS diagrams.
- docs/03-low-level-design.md: explicit AWS map/inventory/IAM; components, DynamoDB schema, transactions, state machine, retry/lease/security design.
- docs/04-api-and-flows.md: API contracts, acceptance/recovery sequence diagrams, dashboard screens.
- docs/05-implementation-plan.md: four-week plan, repository layout, correctness/security/load tests.
- docs/06-decisions-and-sources.md: decisions and official AWS source links checked during design.
- docs/high-level-overview.png: user-facing simplified overview.

Original source directory: C:/Users/abhij/Documents/Codex/2026-11-05/bas/outputs/webhook-platform
Original resume: C:/Users/abhij/Downloads/Abhijith_Sai_Anasuri_Resume.pdf
Original visual reference: C:/Users/abhij/Downloads/images.jpeg
Original chat remains intact. This handoff preserves working context and decisions, not a verbatim export of every message.

## Next action in destination chat

Copy the entire source directory into the new dedicated workspace without modifying the original; verify file counts/hashes. Read every document and this handoff. Confirm readiness and summarize the agreed stack and current documentation-only status. Do not launch implementation or cloud provisioning merely because this chat was created. When the user requests implementation, begin with the week-one vertical slice and review detailed design for unresolved edge cases before treating it as production-ready. In particular pin exact dependency versions, validate AWS adapter compatibility, finalize byte-level signing contract/test vectors, verify Event-ID lookup strategy and pagination, and test due-index/lease races and SSRF-safe transport.
