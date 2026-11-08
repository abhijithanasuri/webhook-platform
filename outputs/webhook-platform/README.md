# Webhook Platform: design documentation

Status: initial design, ready for implementation review. Date: 2026-11-05.

A multi-tenant service that accepts typed events and reliably sends signed HTTP webhooks to subscribed endpoints. Built as a backend portfolio project with a path toward real use. No infrastructure has been provisioned and no performance claims have been measured.

## Read in this order

1. [Requirements and scope](docs/01-requirements.md)
2. [High-level architecture](docs/02-high-level-design.md)
3. [Low-level design and DynamoDB model](docs/03-low-level-design.md)
4. [API contracts and delivery flows](docs/04-api-and-flows.md)
5. [Implementation plan and validation](docs/05-implementation-plan.md)
6. [Decisions and sources](docs/06-decisions-and-sources.md)

Diagrams use Mermaid and render in GitHub Markdown. This documentation directory can become the starting point of the project repository.

## Chosen stack

Java + Spring Boot application, React + TypeScript dashboard, DynamoDB, SQS Standard, Docker Compose and LocalStack for local development. AWS CDK in TypeScript describes API Gateway, Lambda, Cognito, DynamoDB, SQS, EventBridge, KMS, S3/CloudFront, and CloudWatch deployment resources.

Local identity uses a development OIDC provider such as Keycloak, with a seeded owner. It is not a Cognito emulator; token and sign-in integration require an AWS smoke test. LocalStack edition and feature availability must be checked at implementation time; the baseline requires only DynamoDB and SQS emulation.
