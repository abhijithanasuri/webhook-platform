# Architecture decisions and source notes

## Decisions

| Decision | Rationale | Consequence |
|---|---|---|
| DynamoDB | User preference; conditional writes and bounded transactions fit event/delivery state | Access patterns and indexes are specified before code |
| SQS Standard | Decouples notifications from worker capacity | Duplicates and no ordering are explicit |
| Delivery record as outbox | Atomic acceptance plus recoverable publication without a database/queue dual-write gap | Minute polling, repeated wake-ups, indexed due work |
| Durable due-work scheduler | Retry window exceeds SQS native delay | Application owns leases, due times, recovery, and alarms |
| Bounded synchronous fan-out | At most ten endpoints keeps acceptance atomic and understandable | Raising the cap requires redesign/transaction sizing |
| Java service core and thin adapters | Spring Boot locally/API, shared worker logic in AWS | Cloud adapter compatibility must be smoke-tested |
| Cognito in AWS; local OIDC provider | Avoid storing user passwords in application code | Local login is an integration substitute, not Cognito parity |
| One-minute initial dispatch | Frugal, simple baseline without Streams infrastructure | Delivery-lag target must reflect polling |
| Seven-day logical expiry | Prevent expired payload use even if physical deletion lags | Does not promise physical erasure exactly at seven days |

## Sources checked 2026-11-05

- [SQS delay queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-delay-queues.html): queue/message delays have a 15-minute maximum; do not use them as the sole 24-hour retry scheduler.
- [DynamoDB constraints](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html): 400 KB item size, up to 100 transaction actions, and 4 MB transaction size; bounded fan-out and single payload storage keep this design within those constraints.
- [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html): expiry deletion is asynchronous and typically occurs within days; enforce availability in the application.
- [Lambda with SQS](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html) and [error handling](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-errorhandling.html): idempotent processing and partial batch responses are needed for robust retry behavior.
- [AWS Serverless Java Container: Spring quick start](https://github.com/aws/serverless-java-container/wiki/Quick-start---Spring): adapter path for a Spring application behind API Gateway/Lambda.
- [Cognito JWT verification](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html): verify signatures, issuer, expiration, token use, and application client identity.

Exact framework versions, LocalStack features, regional availability, prices, and Lambda startup tuning remain implementation-time checks. No price or throughput guarantee is inferred from these sources.
