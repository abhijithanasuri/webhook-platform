# Backend development

Updated: 7 November 2026. Backend, owner authentication, accounts, and endpoint create/read/list are implemented and locally verified. Endpoint mutations, publisher keys, ingestion, and delivery remain future work. Start with [Daily SOP](13-daily-sop.md) for the current startup/shutdown procedure.

## Run from PowerShell

Follow [Daily SOP](13-daily-sop.md). Open Docker Desktop and wait for its engine. These launcher commands work without enabling PowerShell scripts. Run them one at a time:

```powershell
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' start-localstack
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' setup-store
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' setup-identity
```

Build with scripts/dev.cmd build only when code changed or the jar is missing. Then start scripts/dev.cmd run, leave that terminal open, and check health in a second terminal. See the Daily SOP for expected output and troubleshooting.

The build runs tests and creates backend/api/target/webhook-api-0.1.0-SNAPSHOT.jar. Setup starts the local Keycloak container, waits for discovery, configures the existing dashboard client's API audience, and creates two managed synthetic identities. Run starts the API on 127.0.0.1:8080 with the explicit local profile. Stop it with Ctrl+C.

The launcher now runs a unique copy of the jar under ignored .local/runtime/, so a build cannot modify the running executable. Restart the API to load new code. The login helper checks backend readiness before opening a login flow and reports which step failed if a later request cannot complete.

## Local endpoints

| Route | Access | Result |
|---|---|---|
| GET /actuator/health | Public | Basic UP status only; no dependency-readiness claim |
| GET /v1/me | Valid owner access token | Verified issuer and subject; no tenant/account ID yet |
| POST /v1/account | Valid owner access token | Idempotently create or return the caller's account; no request body |
| GET /v1/account | Valid owner access token | Caller account only; 404 if none exists |

All other requests require authentication. Endpoint create/read/list are implemented; mutations/rotation, publisher keys, and event/delivery remain planned. The owner identity route is a diagnostic addition to the rough API contract. Session cookies and password-based API login are not enabled.

## Verify real browser login

Keep Keycloak and the API running. In another PowerShell window:

```powershell
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' check-login
```

Open the printed URL and sign in as demo-owner-one with its password from .local/demo-owners.json. The temporary callback listener uses 127.0.0.1:5173 and expires after five minutes. It performs authorization code + PKCE S256, then calls /v1/me with the real access token. Tokens stay in memory and are not printed or written to disk. Keep credentials, login URLs, and tokens out of chat. The helper checks authentication, not tenant isolation.

Identity setup retains generated passwords and tracks the two synthetic Keycloak user IDs in .local/demo-owners.json. Legacy demo identities without recorded IDs must match the saved generated username/email or a prior verified login; unrelated collisions and changed IDs are rejected. Missing profile names are filled with synthetic values. Identity setup itself does not create customer accounts; the separate account API does. The administrator password in .env and demo passwords in .local/ are excluded from Git.

## Structure and version decisions

- Java 25 with Spring Boot 3.5.16 and Maven 3.9.16; framework/plugin transitive versions are managed by the pinned Boot parent. The project launcher sets `JAVA_HOME` to its project-local Corretto 25 installation.
- domain contains the issuer/subject identity value; application resolves it from already verified claims; api contains Spring HTTP/security adapters.
- adapters now contains the transactional DynamoDB account repository. worker and dispatcher remain placeholders until delivery functionality is implemented.
- The local decoder accepts RS256 signatures and validates issuer, timestamps, required expiry/issued-at/subject, authorized client, API audience, and Keycloak's Bearer token type. ID tokens, wrong clients/audiences, and invalid signatures are rejected.
- Default startup requires explicit identity settings. Only the explicitly activated local profile supplies localhost identity endpoints. Cognito token validation and AWS profiles are not implemented; do not deploy this local configuration to AWS.
- The Spring Boot 3 Lambda adapter has an available 2.1.7 release, but it is not yet included or runtime-tested. API Gateway/Lambda compatibility and cold starts remain cloud-readiness work.

## Initial foundation evidence (historical)

- Maven verify: BUILD SUCCESS; 16 tests, zero failures/errors.
- Tests make actual HTTP requests against embedded Tomcat and validate RSA-signed tokens against a controlled JWKS server. They cover valid identity, missing/malformed/expired credentials, wrong issuer/client/audience/token type, missing subject/expiry, invalid signature, health exposure, and no authentication session.
- Packaged jar smoke check: health returned 200/UP; unauthenticated /v1/me returned 401; temporary process stopped.
- User terminal: DynamoDB and SQS lifecycle checks passed with temporary resources cleaned up; Keycloak audience/PKCE and demo identity setup passed.
- Real browser login check passed from user output on 6 November 2026: authorization code + PKCE completed and the backend accepted the real Keycloak access token. This verifies local identity integration, not tenant isolation or AWS parity.

Mockito is excluded from the test starter because these tests do not use mocks; its automatic runtime agent attachment stalled in the restricted Windows execution environment. Tests use the real security decoder and HTTP stack.

## Remaining work

Accounts and endpoint create/read/list passed real two-owner checks. Latest full integration build: 36 passing tests. Next: version-checked endpoint edits, disable/delete, secret rotation, publisher keys, and ingestion. LocalStack persistence/image pinning and AWS deployment remain separate. No cloud resources or remote Git repository were created.

## Sources

- Spring Boot 3.5 requirements: https://docs.spring.io/spring-boot/3.5/system-requirements.html
- AWS Java container: https://github.com/aws/serverless-java-container
- Adapter release metadata: https://repo.maven.apache.org/maven2/com/amazonaws/serverless/aws-serverless-java-container-springboot3/maven-metadata.xml

