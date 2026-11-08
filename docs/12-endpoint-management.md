# Endpoint management

Updated: 7 November 2026. Creation, reads/listing, version-checked edits, disable/delete, and signing-secret rotation are implemented locally. The user reported two successful mutation browser checks on 7 November. Outbound delivery is not implemented.

## API

All routes require a valid owner access token and an existing customer account. The tenant comes from the verified owner/account mapping; callers cannot choose it in a body or cursor.

| Route | Behavior |
|---|---|
| POST /v1/endpoints | Create a destination and return 201. The signing secret appears only in this creation response. |
| GET /v1/endpoints/{id} | Read metadata for the caller's endpoint. Foreign and missing endpoint IDs both return 404. |
| GET /v1/endpoints?limit=25&cursor=... | List caller endpoints; limit is 1–100. Response contains items and nextCursor, with null at the end. |

Example request:

```json
{"url":"https://hooks.example.test/events","eventTypes":["invoice.paid"],"enabled":true}
```

Creation response shape:

```json
{
  "endpoint": {
    "endpointId": "ep_example",
    "url": "https://hooks.example.test/events",
    "eventTypes": ["invoice.paid"],
    "enabled": true,
    "version": 1,
    "createdAt": "2026-11-06T14:00:00Z",
    "keyVersion": 1
  },
  "signingSecret": "shown_once"
}
```

IDs and secret above are placeholders. Real endpoint IDs use a server-generated UUID. Get/list responses exclude signingSecret and secretCiphertext. Creation is not idempotent: retrying it can create another endpoint. A lost secret cannot be retrieved; use rotation with the latest endpoint version to obtain a replacement. This remains a controlled local demo.

Errors are structured with code, message, and requestId. Invalid input returns 400; missing account returns 409 ACCOUNT_REQUIRED; the endpoint cap returns 409 ENDPOINT_LIMIT; unavailable storage returns a sanitized 503. Request-supplied tenant/secret fields and unknown JSON properties are rejected.

## Validation and limits

- At most ten configured endpoints per account, including disabled endpoints; twenty unique event types per endpoint. Empty subscription lists are allowed and match no event types.
- Exact types are case-sensitive, 1–100 ASCII characters: first character is a letter, followed by letters, digits, underscores, dots, colons, or hyphens. Wildcards and duplicate types are rejected.
- URL length is at most 2,048 characters. This first configuration policy accepts HTTPS DNS names on the default port or explicit 443. IP literals, localhost/local/internal hostnames, URL credentials, fragments, and other ports/schemes are rejected.
- Registration performs no DNS lookup and sends no outbound request. A valid-looking hostname can still resolve to a private address. Send-time DNS/IP validation, connection pinning, redirects, and deadline enforcement remain mandatory before implementing delivery. These static checks are not complete SSRF protection.

## Storage and consistency

The existing configuration table stores endpoint metadata at TENANT#tenantId / ENDPOINT#endpointId. It includes subscriptions, enabled state, initial version/keyVersion, creation time, and encrypted signing secret.

A TENANT#tenantId / ROUTING item stores a bounded list of routing descriptors and a global routing version. A conditional transaction writes the endpoint and replacement routing snapshot together. The initial empty/absent snapshot is supported. Competing creates re-read and retry the routing version, and cannot exceed the ten-endpoint cap or leave an endpoint without its matching routing descriptor. Readback resolves a committed-but-unconfirmed creation before another transaction is attempted. This prepares the routing boundary for future ingestion; it does not implement event ingestion.

Listings query only the authenticated tenant partition with the ENDPOINT# prefix. Cursors carry a validated last endpoint ID, never a trusted tenant identity. Reusing a foreign cursor cannot query another tenant's partition. Ordering follows opaque endpoint IDs, not creation time. Concurrent additions can affect later pages; this is not a point-in-time list snapshot.

## Signing-secret handling

Creation generates 32 random bytes, returned as an unpadded Base64url string. Local storage uses AES-256-GCM with a fresh nonce and tenant/endpoint-bound authenticated context. The API launcher creates a persistent local development key at ignored .local/endpoint-signing-key and passes it to the process without printing it. Reads and lists expose neither the plaintext secret nor its ciphertext.

Keep that local key private and retain it alongside the development database. Deleting it prevents recovery of existing encrypted endpoint secrets. It is not a disposable build cache. AWS must use KMS; the current local key adapter is not a production deployment design. The byte-level request-signing contract and receiver test vectors remain work for the delivery implementation.

## Verification

- Full Maven verify passed on 7 November: 29 HTTP/authentication/account/endpoint tests, eleven DynamoDB Local repository tests, and two encryption tests — 42 tests total. Mutation coverage includes stale/foreign writes, disable/edit, secret rotation, deletion/capacity release, competing updates, independent concurrent edits, and deletion racing rotation. The temporary engine stopped after the build.
- HTTP cases cover secret-once creation, no secret fields on reads/lists, foreign IDs/cursors, missing account/authentication, invalid destinations/subscriptions, unknown tenant/secret input fields, ten-endpoint cap, and pagination without duplicates.
- Repository cases verify atomic routing/endpoint creation, tenant-scoped pagination, initial empty routing snapshots, and two creates competing for the final slot with no orphan items. These run against a temporary official DynamoDB Local engine, not an in-memory repository substitute.
- Encryption tests verify nonce freshness, successful round-trip, and rejection under a different tenant, endpoint, key, or malformed ciphertext format.
- Packaged local API startup/health returned 200/UP; endpoint listing without authentication returned 401. The temporary process stopped.
- Real Keycloak/LocalStack create/read/list checks passed: two owners with distinct endpoints and foreign-ID denial in both directions. Two mutation browser successes were reported after the two-owner instructions on 7 November. Detailed terminal output was not captured and the saved checker metadata does not record mutation completion. Restart persistence remains unverified.

## Check on the local running setup

Restart the backend after building so it loads the endpoint update. Keep Docker Desktop, LocalStack, Keycloak, and the local configuration table available. The run launcher generates/loads the private signing key automatically.

In a second project-folder PowerShell window:

```powershell
scripts\dev.cmd check-endpoints
```

Sign in as demo-owner-one, repeat as demo-owner-two, and repeat once more as demo-owner-one to exercise foreign-ID denial in both directions. The helper checks account setup, endpoint creation/read/list, absence of secret fields, and foreign endpoint 404 responses. It retains only endpoint IDs and verification metadata in ignored .local/endpoint-check-results.json. Signing secrets and access tokens are not printed or saved. Repeat checks reuse the existing test endpoint rather than consuming more of the cap. It does not send a webhook to the example destination.

## Next

Publisher API keys and atomic event ingestion follow the completed local mutation browser checkpoint. AWS adapter testing and LocalStack restart persistence remain separate.

Local integration milestone: owner one → owner two → owner one completed successfully on 6 November 2026. Saved metadata confirms both foreign-ID checks. No credentials, signing secrets, or access tokens were exposed.

## Version-checked mutations — 7 November

| Route | Contract |
|---|---|
| PATCH /v1/endpoints/{id} | JSON version and at least one of url, eventTypes, enabled; returns updated metadata, 200. |
| POST /v1/endpoints/{id}/rotate-secret | JSON version; returns endpoint metadata and new signingSecret once, 200. |
| DELETE /v1/endpoints/{id}?version=N | Removes active configuration/routing and retains an internal deleted record; returns 204. |

Examples: edit body {"version":1,"enabled":false}; rotation body {"version":2}. Each edit/rotation increments endpoint version; rotation also increments keyVersion. Missing/invalid versions return 400; stale versions return 409 ENDPOINT_VERSION_CONFLICT. Read metadata before retrying. Foreign, missing, and deleted IDs return 404. Null edit fields mean omitted; empty eventTypes clears subscriptions. Disabling still consumes a configured endpoint slot; deletion releases it. Repeated deletion returns 404; restoration is not exposed.

Mutations condition both endpoint and routing versions in one transaction. Concurrent changes to different endpoints retry the routing snapshot without losing each other. Deletion retains prior metadata and encrypted key at DELETED#endpointId with deletedAt, outside active reads/listing. Retention cleanup is not implemented. Rotation replaces the stored key; there is no old-key overlap. Creation and rotation responses use Cache-Control: no-store. Delivery and queued-work cancellation are future behavior because no worker exists yet.

After restarting the backend with the new build, run scripts/dev.cmd check-endpoint-mutations as each demo owner. It includes prior endpoint checks, rejects foreign edits/rotation/deletion, and uses a separate synthetic endpoint for edit/disable, stale writes, rotation, and deletion. It requires one free slot and deletes that temporary endpoint on success. A failed run may leave it behind; do not delete arbitrary endpoints to recover. Secrets stay in memory, never printed or saved. The saved isolation-check endpoint is preserved.
