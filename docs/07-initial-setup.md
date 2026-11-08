# Initial setup and account preparation

Date: 2026-11-05. Today's scope is development environment and account readiness. Application implementation and cloud deployment have not started.

## Completed

- Project folders and design documents prepared; original transfer snapshot preserved.
- Private Notion home and seven-document review tracker created.
- Local Git repository initialized on main; no commit, remote, or push created.
- Project-local Amazon Corretto JDK 25.0.5 (latest Java 25 LTS release), Node.js 24.22.2 with npm, and Maven 3.9.16 downloaded from official sources; the JDK archive was verified against its published SHA-256 checksum.
- Tools and caches excluded from Git; formatting and line-ending defaults added.
- GitHub account confirmed by user. Notion connection works.

## Activate local tools

From the project root in PowerShell:

```powershell
. ./scripts/use-dev-tools.ps1
java -version
javac -version
node --version
npm.cmd --version
mvn.cmd --version
```

This changes only the current shell environment. Tools live in .tools/; caches live in .local/. Use npm.cmd and mvn.cmd on Windows to avoid PowerShell script execution-policy conflicts. The backend targets Java 25. Fresh clones need the project-local Corretto 25 tool installed; binaries remain deliberately uncommitted. Maven 3.9.16 is already used by the launcher.

## Remaining setup

| Item | State | Completion evidence |
|---|---|---|
| Docker Desktop and Compose | Verified from user-supplied terminal output | Desktop 4.98.0; Engine/client 29.9.2; Compose 5.6.1; desktop-linux context; linux/amd64 server |
| Linux container backend | Running, confirmed by user-supplied Docker server output | Linux/amd64 engine available; individual container smoke test still pending |
| LocalStack account | Complete | Account created; lstk 1.4.3 authenticated; Community license valid |
| DynamoDB / SQS emulator | Running; API reachability checks passed | DynamoDB ListTables returned []; SQS ListQueues exited 0. Write/read/message lifecycle tests still pending |
| Local identity provider | Startup, realm import, and discovery passed | Issuer http://localhost:8081/realms/webhook-local verified from user output |
| GitHub authentication | Account exists; project access unverified | Authenticate when creating the remote repository; no token needed in chat |

Initial environment/account preparation is complete at the basic readiness level: tool versions, Docker engine, LocalStack license/list APIs, and Keycloak startup/discovery passed. Deeper service lifecycle and application integration tests remain follow-up work.

## Accounts

- **GitHub:** existing account; no new account needed. Remote repository creation is deferred.
- **Notion:** connected and in use. Keep updating the same project home incrementally.
- **LocalStack:** current activation uses an account and auth token. Sign up at https://app.localstack.cloud/ and check current plan eligibility before accepting a paid plan. Keep the token in an ignored local environment file or credential store, never in docs or chat. Community license and DynamoDB/SQS list API access are verified; lifecycle and persistence tests remain pending.
- **AWS:** no account yet; deferred until cloud preparation. Local work does not need AWS credentials. Before cloud use, set up root MFA, non-root daily access, budget alerts, a region, and a teardown plan.
- **Docker Hub:** do not create an account unless an actual pull/sign-in requirement arises.
- **Cognito / Keycloak:** no separate developer account needed now. Cognito resources will be part of the later AWS design; local Keycloak users will be synthetic demo identities.

## Official setup references

- Docker Windows requirements/install: https://docs.docker.com/desktop/setup/install/windows-install/
- LocalStack authentication: https://docs.localstack.cloud/aws/getting-started/auth-token/
- LocalStack plans: https://docs.localstack.cloud/aws/licensing/
- Corretto 25 downloads: https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/downloads-list.html
- Node downloads and checksums: https://nodejs.org/dist/v24.22.2/
- Maven download and checksum: https://downloads.apache.org/maven/maven-3/3.9.16/binaries/

Detailed artifact URLs and verified hashes are recorded locally in .tools/manifest.json.

## Verification results

Java 25.0.5 and javac 25.0.5 were installed from the official Corretto archive after verifying its published SHA-256 checksum. The pre-migration Java 21.0.13 baseline passed. On Java 25, `scripts/dev.cmd build`, clean `test-compile`, and clean `test` passed; the ordinary suite had 31 passes and 11 repository integration tests skipped. `scripts/dev.cmd test-store` then passed all 42 tests with no skips. Maven 3.9.16 emitted a Java 25 native-access warning during the integration run, but completed successfully. Node/npm remain Node 24.22.2/npm 11.19.1.

Docker follow-up: user reported installation complete on 2026-11-05. The agent could not locate docker.exe in its PATH, standard all-user/per-user installation locations, or inspected local Programs folders; no Docker process was visible. Awaiting fresh PowerShell version output or installation path before claiming readiness.


Docker verification completed from user-supplied terminal output on 2026-11-05: Docker Desktop 4.98.0 (245422), client/engine 29.9.2, Compose v5.6.1, context desktop-linux, server linux/amd64. This confirms the daemon is available in the user terminal; the agent session still cannot locate the executable. Container and local-service smoke tests remain pending. This supersedes the earlier pending version check.


LocalStack milestone: user terminal output confirms lstk 1.4.3 login succeeded, valid Community license, AWS emulator running using localstack/localstack-pro:latest, endpoint localhost.localstack.cloud:4566, and local AWS profile created. No real AWS account was created. DynamoDB/SQS API smoke tests are next; the container image is currently unpinned.


API check update: user supplied DynamoDB ListTables result with TableNames=[] (passed). SQS ListQueues emitted no JSON after the awslocal deprecation warning; exit status was not supplied, so SQS verification remains pending. Retire awslocal from future scripts. Use lstk aws with a host AWS CLI, or the container's native aws command with explicit local endpoint and demo credentials.


## Current next step: local identity

SQS ListQueues passed with exit code 0 from the user terminal. Both DynamoDB and SQS list APIs are reachable. Earlier pending status notes above are historical and superseded by this result.

Keycloak development configuration is in compose.yaml. It uses version 26.8.1, binds only to 127.0.0.1:8081, persists development data in a named Docker volume, and imports the webhook-local realm. The public webhook-dashboard client uses authorization code with PKCE S256 and localhost:5173 redirects. Application login integration and demo user seeding remain future work.

A random local administrator password was generated in the ignored .env file; .env.example contains no password. Do not share .env. This is a development service using start-dev and an embedded database, not production infrastructure. Existing LocalStack remains managed by lstk; this Compose file starts only Keycloak and does not replace or restart LocalStack.

Run from the project root:

```powershell
docker compose up -d keycloak
```

After startup, verify realm discovery:

```powershell
(Invoke-RestMethod 'http://localhost:8081/realms/webhook-local/.well-known/openid-configuration').issuer
```

Expected issuer: http://localhost:8081/realms/webhook-local. Startup may take a minute. On failure, inspect docker compose logs --tail 60 keycloak. Configuration JSON syntax was checked, but Docker Compose rendering and runtime startup cannot be verified in the agent session yet. LocalStack image pinning, persistence, and CRUD/message lifecycle checks remain outstanding.

Keycloak source: https://www.keycloak.org/getting-started/getting-started-docker

Keycloak startup verified from user-supplied logs: version 26.8.1 started successfully, webhook-local realm imported, development admin created, and container is Up with 127.0.0.1:8081 mapped to port 8080. Endpoint discovery retry remains pending. Deprecation warnings did not prevent startup.


## Initial setup milestone completed

User supplied the expected discovery issuer: http://localhost:8081/realms/webhook-local. Keycloak startup, realm import, and OIDC discovery are now verified. Today's initial environment/account preparation is complete at the basic readiness level; no application implementation, cloud account creation, cloud deployment, or GitHub publication occurred.

Ready: project structure, local Git, Java/Node/npm/Maven version checks, Docker/Compose, LocalStack Community account and running AWS emulator, DynamoDB/SQS list APIs, and local Keycloak discovery. GitHub account already exists; Notion tracking is active.

Follow-up work: DynamoDB write/read and SQS send/receive lifecycle tests, LocalStack image pinning and persistence verification, synthetic owner seeding/login integration, framework/adapter version decisions, and a real application build. Earlier pending entries in this document are historical and superseded by these results.

To resume: start Docker Desktop, start LocalStack with lstk.cmd start if needed, and run docker compose up -d keycloak from the project root. Stop Keycloak with docker compose stop keycloak when finished; its named volume retains local state. LocalStack persistence is not yet verified, so treat its data as disposable. Its local AWS profile does not require a real AWS account.
