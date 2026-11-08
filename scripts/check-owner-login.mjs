// Local integration check only. Tokens stay in memory and are never printed or saved.
import http from 'node:http';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const issuer = 'http://localhost:8081/realms/webhook-local';
const redirect = 'http://localhost:5173/callback';
const state = randomBytes(32).toString('base64url');
const verifier = randomBytes(48).toString('base64url');
const challenge = createHash('sha256').update(verifier).digest('base64url');
const discoveryResponse = await fetch(`${issuer}/.well-known/openid-configuration`, { signal: AbortSignal.timeout(5000) });
if (!discoveryResponse.ok) throw new Error('Local Keycloak is not ready. Run dev.cmd setup-identity first.');
const discovery = await discoveryResponse.json();
// Keep this diagnostic restricted to the configured loopback realm.
if (discovery.issuer !== issuer || !discovery.token_endpoint.startsWith(`${issuer}/`)
    || !discovery.authorization_endpoint.startsWith(`${issuer}/`)) throw new Error('Unexpected local identity endpoints');
try {
  const health = await fetch('http://127.0.0.1:8080/actuator/health', { signal: AbortSignal.timeout(3000) });
  if (!health.ok || (await health.json()).status !== 'UP') throw new Error('API health is not UP');
} catch {
  throw new Error('Backend is not ready at 127.0.0.1:8080. Start dev.cmd run in another window before check-login.');
}
const login = new URL(discovery.authorization_endpoint);
login.search = new URLSearchParams({ client_id: 'webhook-dashboard', response_type: 'code',
  redirect_uri: redirect, scope: 'openid', state, code_challenge: challenge,
  code_challenge_method: 'S256', prompt: 'login' }).toString();
let used = false;
let timeout;
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, redirect);
  if (request.method !== 'GET' || url.pathname !== '/callback') { response.writeHead(404).end(); return; }
  const receivedState = url.searchParams.get('state') || '';
  const receivedBytes = Buffer.from(receivedState);
  const expectedBytes = Buffer.from(state);
  if (used || receivedBytes.length !== expectedBytes.length
      || !timingSafeEqual(receivedBytes, expectedBytes)) {
    response.writeHead(400).end('Invalid login state.'); return;
  }
  used = true;
  let stage = 'authorization code exchange';
  try {
    if (url.searchParams.has('error') || !url.searchParams.get('code')) throw new Error('Browser login was not completed');
    const tokenResponse = await fetch(discovery.token_endpoint, { method: 'POST',
      body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'webhook-dashboard',
        code: url.searchParams.get('code'), redirect_uri: redirect, code_verifier: verifier }),
      signal: AbortSignal.timeout(10000) });
    if (!tokenResponse.ok) throw new Error(`Code exchange rejected (${tokenResponse.status})`);
    const tokens = await tokenResponse.json();
    stage = 'backend owner request at 127.0.0.1:8080';
    const identityResponse = await fetch('http://127.0.0.1:8080/v1/me', {
      headers: { Authorization: `Bearer ${tokens.access_token}` }, signal: AbortSignal.timeout(10000),
    });
    if (!identityResponse.ok) throw new Error(`Backend rejected local access token (${identityResponse.status})`);
    const owner = await identityResponse.json();
    if (owner.issuer !== issuer || !owner.subject) throw new Error('Unexpected verified identity');
    if (process.argv.includes('--accounts') || process.argv.includes('--endpoints') || process.argv.includes('--endpoint-mutations')) {
      stage = 'account creation/read/repeat verification';
      const accountRequest = async method => {
        const result = await fetch('http://127.0.0.1:8080/v1/account', { method,
          headers: { Authorization: `Bearer ${tokens.access_token}` }, signal: AbortSignal.timeout(15000) });
        if (!result.ok) {
          let failure = {};
          try { failure = await result.json(); } catch { /* A non-JSON error is still reported by status. */ }
          const code = typeof failure.code === 'string' && /^[A-Z_]+$/.test(failure.code) ? failure.code : 'UNKNOWN';
          const requestId = typeof failure.requestId === 'string' && /^[a-f0-9-]{36}$/.test(failure.requestId) ? failure.requestId : 'unavailable';
          throw new Error(`Account ${method} rejected (${result.status}, ${code}, request ${requestId})`);
        }
        return result.json();
      };
      const account = await accountRequest('POST');
      const read = await accountRequest('GET');
      const repeated = await accountRequest('POST');
      if (!account.tenantId || account.tenantId !== read.tenantId || account.tenantId !== repeated.tenantId
          || account.createdAt !== repeated.createdAt) throw new Error('Account identity was not stable');
      const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
      await mkdir(path.join(root, '.local'), { recursive: true });
      const file = path.join(root, '.local', 'account-check-results.json');
      let checked = [];
      try { checked = JSON.parse(await readFile(file, 'utf8')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      const others = checked.filter(entry => entry.issuer === issuer && entry.subject !== owner.subject);
      if (others.some(entry => entry.tenantId === account.tenantId)) throw new Error('Different owners resolved to the same account');
      checked = checked.filter(entry => entry.issuer !== issuer || entry.subject !== owner.subject);
      checked.push({ issuer, subject: owner.subject, tenantId: account.tenantId });
      await writeFile(file, JSON.stringify(checked, null, 2) + '\n', { mode: 0o600 });
      console.log('PASS: authenticated account create/read/repeat returned one stable account.');
      console.log(others.length ? 'PASS: different verified owners have distinct accounts.'
        : 'NEXT: run check-accounts again and sign in as demo-owner-two to check separation.');
      if (process.argv.includes('--endpoints') || process.argv.includes('--endpoint-mutations')) {
        stage = 'endpoint create/read/list and owner isolation';
        const endpointRequest = async (route, method = 'GET', body) => fetch(`http://127.0.0.1:8080/v1/endpoints${route}`, {
          method, headers: { Authorization: `Bearer ${tokens.access_token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
          body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000),
        });
        const resultsFile = path.join(root, '.local', 'endpoint-check-results.json');
        let records = [];
        try { records = JSON.parse(await readFile(resultsFile, 'utf8')); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
        const own = records.find(entry => entry.issuer === issuer && entry.subject === owner.subject && entry.tenantId === account.tenantId);
        let endpointId = own?.endpointId;
        let existing = endpointId ? await endpointRequest('/' + endpointId) : null;
        if (existing && existing.status !== 200 && existing.status !== 404) throw new Error(`Existing endpoint read failed (${existing.status})`);
        if (!existing || existing.status === 404) {
          const create = await endpointRequest('', 'POST', { url: 'https://hooks.example.test/events', eventTypes: ['invoice.paid'] });
          if (create.status !== 201) throw new Error(`Endpoint create rejected (${create.status})`);
          const result = await create.json();
          if (!/^[A-Za-z0-9_-]{43}$/.test(result.signingSecret || '')) throw new Error('Creation did not return a signing secret');
          endpointId = result.endpoint.endpointId;
          console.log('PASS: endpoint created; signing secret returned once and kept only in memory.');
        }
        const read = await endpointRequest('/' + endpointId);
        if (!read.ok) throw new Error(`Endpoint read rejected (${read.status})`);
        const details = await read.json();
        const list = await endpointRequest('');
        if (!list.ok) throw new Error(`Endpoint list rejected (${list.status})`);
        const listing = await list.json();
        if ('signingSecret' in details || 'secretCiphertext' in details
            || listing.items.some(item => 'signingSecret' in item || 'secretCiphertext' in item)) {
          throw new Error('Endpoint read/list exposed a secret field');
        }
        if (!listing.items.some(item => item.endpointId === endpointId)) throw new Error('Created endpoint missing from own list');
        const foreign = records.filter(entry => entry.issuer === issuer && entry.subject !== owner.subject);
        for (const entry of foreign) {
          const response = await endpointRequest('/' + entry.endpointId);
          if (response.status !== 404 || listing.items.some(item => item.endpointId === entry.endpointId)) {
            throw new Error('Foreign endpoint was visible to another owner');
          }
        }
        records = records.filter(entry => entry.issuer !== issuer || entry.subject !== owner.subject);
        records.push({ issuer, subject: owner.subject, tenantId: account.tenantId, endpointId,
          verifiedForeignIds: foreign.map(entry => entry.endpointId) });
        await writeFile(resultsFile, JSON.stringify(records, null, 2) + '\n', { mode: 0o600 });
        console.log('PASS: own endpoint read/list work without exposing secret fields.');
        console.log(foreign.length ? 'PASS: foreign endpoint ID returns 404 and is absent from this owner list.'
          : 'NEXT: repeat check-endpoints as demo-owner-two, then demo-owner-one, to check isolation in both directions.');
        if (process.argv.includes('--endpoint-mutations')) {
          stage = 'endpoint edit, rotation, deletion and foreign-write verification';
          for (const entry of foreign) {
            for (const [route, method, body] of [
              [`/${entry.endpointId}`, 'PATCH', { version: 1, enabled: false }],
              [`/${entry.endpointId}/rotate-secret`, 'POST', { version: 1 }],
              [`/${entry.endpointId}?version=1`, 'DELETE', undefined],
            ]) {
              if ((await endpointRequest(route, method, body)).status !== 404) throw new Error('Foreign endpoint mutation did not return 404');
            }
          }
          // Mutate only a fresh synthetic endpoint; preserve the saved isolation-check endpoint.
          const created = await endpointRequest('', 'POST', { url: 'https://mutation.example.test/events', eventTypes: ['invoice.paid'] });
          if (created.status !== 201) throw new Error(`Mutation fixture creation failed (${created.status}); one free endpoint slot is required`);
          const fixture = await created.json();
          const route = '/' + fixture.endpoint.endpointId;
          let version = fixture.endpoint.version;
          const patch = await endpointRequest(route, 'PATCH', { version, enabled: false,
            url: 'https://updated.example.test/events', eventTypes: ['invoice.failed'] });
          if (patch.status !== 200) throw new Error(`Endpoint edit rejected (${patch.status})`);
          const edited = await patch.json();
          if (edited.enabled !== false || edited.version !== version + 1 || edited.url !== 'https://updated.example.test/events'
              || edited.eventTypes.join(',') !== 'invoice.failed') throw new Error('Endpoint edit result mismatch');
          if ((await endpointRequest(route, 'PATCH', { version, enabled: true })).status !== 409) throw new Error('Stale edit was not rejected');
          version = edited.version;
          const rotate = await endpointRequest(route + '/rotate-secret', 'POST', { version });
          if (rotate.status !== 200) throw new Error(`Rotation rejected (${rotate.status})`);
          const rotated = await rotate.json();
          if (!/^[A-Za-z0-9_-]{43}$/.test(rotated.signingSecret || '') || rotated.signingSecret === fixture.signingSecret
              || rotated.endpoint.keyVersion !== 2 || rotated.endpoint.version !== version + 1) throw new Error('Rotation result mismatch');
          const metadata = await (await endpointRequest(route)).json();
          if ('signingSecret' in metadata || 'secretCiphertext' in metadata) throw new Error('Read exposed secret fields');
          if ((await endpointRequest(route + '?version=' + version, 'DELETE')).status !== 409) throw new Error('Stale deletion was not rejected');
          if ((await endpointRequest(route + '?version=' + rotated.endpoint.version, 'DELETE')).status !== 204) throw new Error('Deletion failed');
          if ((await endpointRequest(route)).status !== 404) throw new Error('Deleted endpoint is still readable');
          const remaining = await (await endpointRequest('')).json();
          if (remaining.items.some(item => item.endpointId === fixture.endpoint.endpointId)) throw new Error('Deleted endpoint is still listed');
          console.log('PASS: version-checked edit/disable, stale-write rejection, secret rotation, and deletion.');
          console.log(foreign.length ? 'PASS: foreign endpoint edit, rotation, and deletion all returned 404.'
            : 'NEXT: run as both owners to verify foreign mutations.');
        }
      }
    }
    response.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' })
      .end('Login check passed. You may close this tab.');
    console.log('PASS: browser authorization code + PKCE login completed.');
    console.log('PASS: backend accepted the real Keycloak access token and returned its verified owner identity.');
  } catch (error) {
    response.writeHead(500, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' })
      .end('Login check failed. See the local terminal for the error.');
    const networkCode = error.cause?.code || error.cause?.errors?.[0]?.code;
    console.error(`FAIL during ${stage}: ${error.message}${networkCode ? ' (' + networkCode + ')' : ''}`);
    process.exitCode = 1;
  } finally {
    clearTimeout(timeout);
    server.close();
  }
});
server.on('error', error => { console.error(`Local callback listener failed: ${error.code}`); process.exitCode = 1; clearTimeout(timeout); });
server.listen(5173, '127.0.0.1', () => {
  console.log('Open this URL in your browser to check a local demo owner login:');
  console.log(login.toString());
  console.log('Use demo-owner-one (or demo-owner-two for the second account check) and its password from .local/demo-owners.json. Keep that file private.');
  console.log('This check expires in five minutes and uses port 5173 temporarily.');
});
timeout = setTimeout(() => { console.error('Login check expired.'); process.exitCode = 1; server.close(); }, 300000);
