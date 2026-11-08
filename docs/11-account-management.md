# Customer account creation and owner scoping

Date: 6 November 2026. Account creation and lookup are implemented locally. Endpoint, publisher-key, event, and delivery authorization remain future work.

## Behavior

| API | Behavior |
|---|---|
| POST /v1/account | No request body. Return 200 with the caller's account, creating it atomically if absent. Repeating the request returns the same tenantId and createdAt. |
| GET /v1/account | Return only the caller's account. Return 404 if the caller has not created one. |

Both routes require a validated owner access token. Clients cannot supply an owner identity or choose an account ID. Nonblank creation bodies, including owner/tenant fields, are rejected with 400. Account reads are derived exclusively from verified issuer and subject. There is no account-by-ID route.

The response contains tenantId and createdAt. Storage failures return a sanitized 503 with code, message, requestId, and X-Request-Id; no false acceptance result is returned. The public health check still reports process health, not storage readiness.

## Durable owner mapping

The configuration table uses string PK and SK keys. An owner lookup has PK OWNER#identityHash and SK ACCOUNT, pointing to a tenant. Its identity hash is SHA-256 over length-prefixed UTF-8 issuer and subject. Issuer is included because subject IDs are not globally unique across identity providers.

The account metadata item has PK TENANT#tenantId and SK META, plus owner identity, ownerKey, and creation time. Account metadata and owner lookup are created in one conditional DynamoDB transaction. Competing requests cannot create multiple accounts for one owner or leave losing candidate accounts behind. Transaction retries reuse the same request token; readback resolves an existing winner or an ambiguous response after commit. Reads are strongly consistent and verify that metadata belongs to the lookup owner. Failed resolution returns 503 rather than inventing an account.

This refines the initial OWNER#sub sketch in the low-level design. No existing production data or migration is involved.

## Local setup and verification

Keep Docker, LocalStack, and Keycloak running. From any PowerShell directory:

```powershell
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' start-localstack
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' setup-store
& 'C:\Users\abhij\Documents\Codex\2026-11-05\webhook-platform\scripts\dev.cmd' run
```

Setup creates webhook-configuration on the local emulator only, or checks its existing schema without deleting records. The API local profile uses the fixed endpoint http://127.0.0.1:4566, us-east-1, and synthetic credentials. No default credential chain or real AWS endpoint is used.

Opening Docker Desktop alone does not start LocalStack. If account creation returns 503, first run start-localstack and setup-store. Setup reports whether the table was missing and created or already existed and was validated. LocalStack persistence across recreation remains unverified; a successful login does not prove account storage is available. The newer backend logs a sanitized storage-failure category and request ID, while the checker displays the structured error code/request ID without printing credentials or tokens. Restart the backend to load these diagnostics after rebuilding.

In a second terminal, run scripts/dev.cmd check-accounts. Sign in first as demo-owner-one, then repeat the command and sign in as demo-owner-two. Use private passwords from .local/demo-owners.json. The helper checks account creation/read/repeat behavior and compares the two verified identities' account IDs. It stores only issuer, subject, and tenantId in ignored .local/account-check-results.json; access tokens stay in memory.

The launcher now copies the packaged API to a unique ignored .local/runtime jar before running it. This prevents builds from changing the running jar. A new build does not update an already running API: restart to load new code. The earlier shutdown class-loading error occurred after an overlapping rebuild; no application state was deleted.

Demo setup records Keycloak user IDs privately rather than depending on custom attributes, which Keycloak did not retain. Existing legacy demo users can be recognized only by the saved generated username/email or a prior verified login result; later ID mismatches are rejected. It fills missing synthetic names and retains passwords and existing names. Six focused identity-matching tests passed, including mismatch/collision cases.

## Test evidence

- 21 HTTP/authentication tests passed. Account cases include missing authentication, absent account, repeat creation, two-owner separation, rejected owner/account body fields, and sanitized storage failure. HTTP account tests use a clearly separate in-memory repository fixture, not production storage.
- Three repository tests passed against a temporary official DynamoDB Local engine: durable lookup after repository recreation, issuer-scoped identities, and eight competing creates resolving to one account with exactly two stored items and no orphan accounts. All test tables were removed.
- The full integration Maven verify build passed. Default build runs HTTP tests but skips repository integration tests unless a local emulator endpoint is explicitly supplied.
- User verified local table setup and successful browser account checks. The saved checker results contain two verified owner subjects and two distinct account IDs. Account create/read/repeat and owner separation are verified locally; persistence across LocalStack restarts remains unverified.
- Packaged runtime-copy launcher returned health 200/UP and unauthenticated account access 401. Process cleanup from that launcher smoke check could not be confirmed through the initial process-tree command; no completion claim is based on it.

To repeat repository tests without Docker, obtain the official DynamoDB Local ZIP from https://s3.us-west-2.amazonaws.com/dynamodb-local/dynamodb_local_latest.zip and extract it into .tools/dynamodb/. Then run scripts/dev.cmd test-store. This starts an isolated in-memory engine on a temporary port, runs all tests, and stops that engine. It never targets LocalStack or real AWS. The test tool is not an application dependency.

The downloaded test archive used here reported DynamoDB Local 1.25.1; its recorded SHA-256 is a3cb28740ea3875a7b0e14cfb13ced686f36ce27bc00f13f8a7d72ddeee6c9b1. This is a recorded local hash, not a separately verified vendor checksum. DynamoDB Local tests are not evidence of full AWS or LocalStack parity.

## Next

The real two-owner account check passed. Next implement tenant-scoped endpoints and publisher keys. Test cross-tenant resource IDs as those APIs are introduced. LocalStack restart/persistence checks and cloud deployment remain separate.

Demo-profile follow-up: second-owner login requested missing first/last names. The identity setup script now supplies synthetic names for new demo users and fills missing names on existing managed users, retaining passwords and existing names. It clears only profile-update/verification actions after populating those fields; unrelated required actions are preserved. The second-owner integration check remains pending until retry succeeds.


Final verification: after starting LocalStack, the user reported a successful browser check. Saved check metadata confirms two verified owners and two distinct account IDs, without reading or exposing passwords or tokens. This completes the local account flow verification; it does not establish general resource authorization or restart persistence.

