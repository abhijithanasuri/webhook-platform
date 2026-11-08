# Implementation plan — 6 November 2026

Status: In progress. Estimates below use a four-hour implementation budget; adjust for time already spent reviewing documentation.

## Outcome

A repeatable local environment and a Spring Boot backend that builds, starts, and responds to a health check. Owner authentication is the next priority if the foundation is ready. Endpoint/key management and event ingestion follow after that foundation.

Stakeholder design review is complete: the user approved the edited Notion document on 6 November 2026. Its tracker status is Agreed and the local copy preserves the reviewed content. This closes documentation review, not application implementation.

## Ordered work

| Work | Estimate | Done when |
|---|---|---|
| Restart and verify local services | 45 min | Docker, LocalStack, and Keycloak respond; a temporary DynamoDB item is written/read and an SQS message is sent/received/deleted. Test resources are cleaned up. |
| Establish the backend build | 90 min | Choose compatible pinned Java/Spring dependencies; create the agreed Maven modules with real dependencies as needed; build successfully; run the API locally; GET /actuator/health returns 200 with UP. Keep other routes protected. |
| Start owner authentication | 75 min | Create synthetic local owner identities; validate Keycloak access tokens; a protected API rejects missing/invalid tokens and accepts a valid owner token. Begin account creation/tenant mapping only if time permits. |
| Verify and record | 30 min | Repeat build/tests and startup instructions, record actual evidence and unfinished work in Notion, and document restart/shutdown steps. |

Estimates are planning aids, not a commitment to finish every row. If environment recovery takes longer, prioritize a working build and health check; carry authentication forward rather than weakening validation.

## Dependencies and constraints

- Docker executable location is known, but the agent previously received access denied when connecting to the engine. Retest first. If still blocked, the user runs the small local service checks; independent backend work continues here.
- LocalStack currently uses latest. Record the running image digest and persistence behavior before changing or recreating its container. Keep credentials out of tracked files and test only local endpoints.
- Verify Keycloak realm discovery again. Existing configuration has a public dashboard client with PKCE; do not enable password grants merely to simplify testing.
- Validate framework/Lambda adapter compatibility before selecting versions. A local build is not proof of AWS parity.
- No AWS resources, GitHub publication, dashboard implementation, or delivery worker work is included today.

## Checks matched to the design

- Local service lifecycle checks establish the dependencies needed for durable event storage and queued delivery.
- Backend build and health establish the API runtime before adding business flows.
- Identity validation establishes owner credentials; full two-tenant isolation tests follow when tenant mapping exists.
- Endpoint and API-key management, atomic event acceptance, idempotency, and fan-out remain later week-one work. Do not mark them complete from a successful health check.

## First action

Open Docker Desktop. Restart LocalStack with lstk.cmd start if needed and Keycloak with docker compose up -d keycloak. Retest agent access to the engine and collect current local service status before creating resources.

## Sources

- Approved stakeholder design: https://app.notion.com/p/4106baeb2fb08190b367e660cea7aaac
- Requirements: 01-requirements.md
- Detailed design: 03-low-level-design.md
- Overall implementation plan: 05-implementation-plan.md

## Progress

- Passed from user terminal output: DynamoDB create/write/consistent-read and SQS create/send/receive/delete; temporary resources cleaned up.
- Initially implemented and built with Java 21 (subsequently upgraded to Java 25): Spring Boot 3.5.16 Maven modules for domain, application, and API; executable API jar; public basic health and protected owner identity endpoint.
- Passed in agent environment: 16 HTTP/JWT authentication tests with a local signing-key fixture, packaged API health 200/UP, protected route 401, and temporary API shutdown.
- Passed from user terminal output: existing Keycloak client updated with API audience and PKCE retained; two synthetic demo identities prepared. These are not customer accounts/tenants.
- Pending: real Keycloak browser login integration check on the user's machine. Customer-account persistence and cross-tenant authorization are not implemented.
- Deferred: LocalStack digest pinning/persistence validation, endpoint/key management, ingestion, Lambda adapter/runtime validation, and cloud deployment.

Backend commands and limitations: 10-backend-development.md.


Final package rebuilt successfully after the user stopped the API. All 16 tests passed; final jar health 200/UP and unauthenticated owner 401 checks passed. Browser-login helper now checks backend readiness first, uses the IPv4 loopback backend address, and labels failures by stage. The real Keycloak login retry remains pending.


Local owner authentication verified: user supplied successful API startup logs and browser result 'Login check passed. You may close this tab.' Authorization code + PKCE and the real Keycloak-to-backend token flow passed. Customer-account persistence, tenant isolation, and endpoint/key management remain next. No cloud deployment or GitHub publication occurred.


Account verification completed: real browser check succeeded after LocalStack startup. Saved results confirm two verified owners with distinct accounts. Tenant-scoped endpoints and publisher keys are next; LocalStack restart persistence remains a separate check.


Endpoint create/read/list integration verified through both real demo identities, including foreign-ID denial in both directions. Mutations, rotation, and publisher keys remain next.
