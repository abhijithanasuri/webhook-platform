# Webhook Platform

Status: local implementation in progress. Updated: 8 November 2026.

A multi-tenant service that accepts typed events and reliably sends signed HTTP webhooks to subscribed endpoints. Built as a backend portfolio project with a path toward real use. No infrastructure has been provisioned and no performance claims have been measured.

## Read in this order

For product and business readers, start with the [Stakeholder design document](docs/08-stakeholder-design.md).

1. [Requirements and scope](docs/01-requirements.md)
2. [High-level architecture](docs/02-high-level-design.md)
3. [Low-level design and DynamoDB model](docs/03-low-level-design.md)
4. [API contracts and delivery flows](docs/04-api-and-flows.md)
5. [Implementation plan and validation](docs/05-implementation-plan.md)
6. [Decisions and sources](docs/06-decisions-and-sources.md)

Diagrams use Mermaid and render in GitHub Markdown. This directory is the project root. Active design documents live in docs/. The original verified transfer snapshot remains in outputs/webhook-platform/.

## Chosen stack

Java + Spring Boot application, React + TypeScript dashboard, DynamoDB, SQS Standard, Docker Compose and LocalStack for local development. AWS CDK in TypeScript describes API Gateway, Lambda, Cognito, DynamoDB, SQS, EventBridge, KMS, S3/CloudFront, and CloudWatch deployment resources.

Local identity uses a development OIDC provider such as Keycloak, with a seeded owner. It is not a Cognito emulator; token and sign-in integration require an AWS smoke test. LocalStack edition and feature availability must be checked at implementation time; the baseline requires only DynamoDB and SQS emulation.

## Project structure

```text
backend/
  domain/          # Entities, retry policy, signing contracts
  application/     # Ingestion, dispatch, delivery, replay
  adapters/        # DynamoDB, SQS, HTTP, KMS, identity
  api/             # Spring Boot API and Lambda HTTP adapter
  worker/          # Local poller and Lambda SQS handler
  dispatcher/      # Local scheduler and Lambda scheduled handler
dashboard/         # React + TypeScript dashboard
demo-receiver/     # Controlled webhook receiver
infra/             # AWS CDK in TypeScript
docs/              # Active requirements, designs, plan and handoff
tests/             # Future cross-component and end-to-end tests
outputs/           # Deliverables and preserved transfer snapshot
work/              # Ignored scratch files and temporary assets
```

The Java 25 backend, health check, owner authentication, account mapping, and endpoint create/read/list/edit/disable/delete/rotation APIs are implemented locally. The full automated suite passes 42 tests; two mutation browser successes were reported by the user. See [Backend development](docs/10-backend-development.md), [Account management](docs/11-account-management.md), and [Endpoint management](docs/12-endpoint-management.md) for evidence and limitations. Publisher keys, ingestion, delivery, the dashboard, and AWS deployment remain planned. Component-specific tests live alongside their components; tests/ is for cross-component validation.

## Document tracking

See [Notion document tracking](docs/NOTION.md) for the project home, review workflow, and synchronization policy.

## Initial setup

Start each working day with the [Daily SOP](docs/13-daily-sop.md). It is the current startup, checks, troubleshooting, and shutdown procedure.

See [Initial setup and account preparation](docs/07-initial-setup.md) and [today's progress](docs/09-day-two-plan.md). Use scripts/dev.cmd build or run from PowerShell; this launcher handles project-local tool paths without enabling PowerShell scripts. Docker/LocalStack lifecycle checks have passed; agent access to the user's Docker engine remains restricted.
