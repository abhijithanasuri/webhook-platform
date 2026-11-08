# Daily SOP — Webhook Platform

Last updated: 7 November 2026. Use this as the daily startup, check, and shutdown procedure. It assumes the tools/accounts from initial setup are already installed, including project-local Amazon Corretto JDK 25.0.5. The backend builds and runs on Java 25 through `scripts/dev.cmd`; no manual Java activation is needed. Run commands one at a time; stop at an error instead of continuing.

## 1. Start Docker Desktop

Open Docker Desktop and wait until the engine is running. Opening Docker alone does not start LocalStack or the backend.

## 2. Open the project in PowerShell

```powershell
cd C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform
```

Use scripts/dev.cmd for project commands. It supplies the local tool paths and works without enabling PowerShell scripts.

Java 25 readiness rechecked on 7 November: all 42 tests passed with no skips, and the rebuilt packaged API started on Corretto 25.0.5 with health UP. The temporary test processes stopped. A global java command is not required; the launcher selects the project-local JDK. This check does not replace real Keycloak/LocalStack browser verification.

## 3. Start dependencies in this order

```powershell
scripts\dev.cmd start-localstack
```

Expected: LocalStack reports a valid Community license and that its AWS emulator is running. If login is requested, complete the browser flow locally; do not share tokens.

```powershell
scripts\dev.cmd setup-store
```

Expected: the local configuration table is either validated with records preserved, or created because it was missing. A newly created table does not restore old account/endpoint data. LocalStack restart/recreation persistence remains unverified.

```powershell
scripts\dev.cmd setup-identity
```

Expected: Keycloak starts, readiness completes, and PASS lines confirm the client/audience and two synthetic demo identities. Existing passwords are retained. No need to manually create accounts or re-enter profile names.

## 4. Build only when needed, then start the backend

If code changed, the packaged jar is missing, or a fresh build is requested:

```powershell
scripts\dev.cmd build
```

Expected: BUILD SUCCESS. This runs ordinary tests; repository integration tests require test-store below. An ordinary build does not run the DynamoDB integration cases.

Then start the backend:

```powershell
scripts\dev.cmd run
```

Expected: a build fingerprint, then Started WebhookApiApplication on port 8080. Leave this terminal running. The launcher uses a private runtime copy, so builds do not modify the running jar. Restart the backend to load any newer build.

## 5. Check readiness in a second PowerShell window

```powershell
cd C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Expected: status UP. This confirms the API process is running; it does not prove DynamoDB or Keycloak is available. The dependency setup steps above must also pass.

## 6. Run only the checks relevant to the day's changes

| Situation | Command | Expected result |
|---|---|---|
| Login/authentication changed | scripts/dev.cmd check-login | Browser login and backend token checks pass. |
| Account behavior changed | scripts/dev.cmd check-accounts | Stable account create/read/repeat; use each demo owner when checking separation. |
| Endpoint behavior changed | scripts/dev.cmd check-endpoints | Run owner one → owner two → owner one for foreign-ID checks in both directions. |
| Endpoint edits/rotation/deletion changed | scripts/dev.cmd check-endpoint-mutations | Run as each owner; checks stale writes, foreign mutations, rotation, and deletion using a separate temporary endpoint. Requires one free slot. |
| DynamoDB repository changed | scripts/dev.cmd test-store | Full integration build passes and the temporary test engine stops. Requires the existing .tools/dynamodb test installation. |

Each browser checker prints a fresh URL. Open it promptly and finish login within five minutes. Do not run two browser checkers simultaneously: they temporarily use port 5173. Use demo-owner-one or demo-owner-two and the private password from .local/demo-owners.json. Share only PASS/error lines, not passwords, tokens, or login URLs.

Account checks create an account if absent. Endpoint checks may create a synthetic destination and then reuse it on later runs. They do not send a webhook. If LocalStack data was reset, treat older saved checker metadata as historical and recheck both owners; saved files are not proof that old resources still exist.

You do not need to repeat every browser check on an unchanged daily startup.

## Common problems

| Symptom | Action |
|---|---|
| lstk/npm/Java command not found | Use scripts/dev.cmd from the project folder rather than raw tool commands. |
| PowerShell says scripts are disabled | Use the .cmd launcher; no execution-policy change is needed. |
| Connection refused during Keycloak setup | Let setup-identity wait for readiness. If it times out, inspect docker compose logs --tail 60 keycloak. |
| Account or endpoint operation returns 503 | Run start-localstack, then setup-store. If it persists, share the error code/request ID and sanitized backend log line. |
| New endpoint route returns 404 | Stop the old backend, build if needed, restart run, and verify the printed build fingerprint/startup success. |
| Port 8080 already in use | Stop the earlier backend in its own terminal. Do not kill every Java process. |
| Port 5173 already in use | Stop the earlier browser checker with Ctrl+C, then start a fresh check. |
| Wrong working directory / file not found | Run the project cd command first. |
| Setup reports an identity mismatch | Stop and share the error; do not delete users or reset passwords to force it through. |

The agent may lack Docker-engine access even when your terminal works. That is a separate permission boundary; it is not evidence that Docker itself is broken.

## End-of-day shutdown

1. Stop the backend terminal with Ctrl+C. If asked Terminate batch job (Y/N)?, enter Y.
2. Stop any remaining browser checker with Ctrl+C and close its login/result tabs.
3. From the project folder, stop Keycloak:

```powershell
docker compose stop keycloak
```

4. Stop this project's LocalStack container using Docker Desktop. Quit Docker Desktop when other work no longer needs it. Avoid stopping unrelated project containers.
5. Close project terminals when finished. Do not delete containers, volumes, or private local files as routine shutdown.

Keep .env, .local/demo-owners.json, and .local/endpoint-signing-key private. The signing key is not a disposable cache: deleting it prevents recovery of existing encrypted endpoint secrets. Do not share these files in chat or commit them.

## Maintenance at the end of each working session

The assistant updates this SOP and the existing Notion runbook whenever setup changes and when the day is wrapped up. Verify command names against scripts/dev.cmd; update prerequisites, order, expected output, relevant checks, shutdown, and known issues. Update the date and current verification status. Replace obsolete guidance rather than stacking contradictory updates. Keep technical history/evidence in the implementation documents.

Current real-integration baseline from 6 November: backend, login, two-owner accounts, and endpoint create/read/list with foreign-ID denial in both directions. Endpoint mutations/rotation pass 42 automated tests; the user reported two successful mutation browser runs on 7 November after the two-owner instructions. Detailed terminal output was not captured. Publisher keys remain next. Startup order is unchanged. Repeat mutation browser checks when relevant behavior changes; no repeat is needed solely to resume unchanged work. LocalStack restart persistence remains unverified.
