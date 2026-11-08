import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { matchesDemoIdentity } from './demo-identity-match.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const base = 'http://localhost:8081';
const realm = 'webhook-local';
console.log('Waiting for local Keycloak readiness (up to two minutes)...');
let ready = false;
const readinessDeadline = Date.now() + 120000;
while (Date.now() < readinessDeadline) {
  try {
    const result = await fetch(`${base}/realms/${realm}/.well-known/openid-configuration`, {
      signal: AbortSignal.timeout(2000),
    });
    if (result.ok) { ready = true; break; }
  } catch { /* Startup can briefly refuse connections. */ }
  await new Promise(resolve => setTimeout(resolve, 2000));
}
if (!ready) throw new Error('Keycloak is not ready. Inspect: docker compose logs --tail 60 keycloak');
const env = Object.fromEntries((await readFile(path.join(root, '.env'), 'utf8'))
  .split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
    const at = line.indexOf('=');
    return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
  }));
const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
  method: 'POST',
  body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli',
    username: env.KEYCLOAK_ADMIN_USERNAME, password: env.KEYCLOAK_ADMIN_PASSWORD }),
  signal: AbortSignal.timeout(15000),
});
if (!response.ok) throw new Error(`Local administrator authentication failed (${response.status}). Check Keycloak startup and .env locally.`);
const { access_token: token } = await response.json();

async function api(route, method = 'GET', body) {
  const result = await fetch(`${base}/admin/realms/${realm}/${route}`, {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000),
  });
  if (!result.ok) throw new Error(`Local identity setup failed: ${method} ${route.split('?')[0]} (${result.status})`);
  const text = await result.text();
  return text ? JSON.parse(text) : null;
}

const clients = await api('clients?clientId=webhook-dashboard');
if (clients.length !== 1) throw new Error('Expected exactly one webhook-dashboard client in the imported realm');
const client = clients[0];
if (!client.publicClient || !client.standardFlowEnabled || client.directAccessGrantsEnabled
    || client.attributes?.['pkce.code.challenge.method'] !== 'S256') {
  throw new Error('Dashboard client is not configured for authorization code with PKCE S256');
}
const mapper = {
  name: 'webhook-api-audience', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper',
  consentRequired: false,
  config: { 'included.custom.audience': 'webhook-api', 'access.token.claim': 'true', 'id.token.claim': 'false' },
};
const mappers = await api(`clients/${client.id}/protocol-mappers/models`);
const existing = mappers.find(entry => entry.name === mapper.name);
await api(`clients/${client.id}/protocol-mappers/models${existing ? '/' + existing.id : ''}`,
  existing ? 'PUT' : 'POST', existing ? { ...mapper, id: existing.id } : mapper);

await mkdir(path.join(root, '.local'), { recursive: true });
const credentialsPath = path.join(root, '.local', 'demo-owners.json');
let credentials;
try { credentials = JSON.parse(await readFile(credentialsPath, 'utf8')); }
catch (error) {
  if (error.code !== 'ENOENT') throw error;
  credentials = { realm, owners: ['one', 'two'].map(suffix => ({
    username: `demo-owner-${suffix}`, email: `owner-${suffix}@example.test`,
    password: randomBytes(24).toString('base64url'),
  })) };
  // Save before creating users, so an interrupted run can resume with the same passwords.
  await writeFile(credentialsPath, JSON.stringify(credentials, null, 2) + '\n', { mode: 0o600 });
}
const expectedUsernames = ['demo-owner-one', 'demo-owner-two'];
if (credentials.realm !== realm || credentials.owners.length !== 2
    || expectedUsernames.some(name => !credentials.owners.some(owner => owner.username === name))) {
  throw new Error('The private demo credential file does not match this project realm');
}
let verifiedChecks = [];
try { verifiedChecks = JSON.parse(await readFile(path.join(root, '.local', 'account-check-results.json'), 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
for (const owner of credentials.owners) {
  const firstName = 'Demo';
  const lastName = owner.username.endsWith('-two') ? 'Owner Two' : 'Owner One';
  const users = await api(`users?username=${encodeURIComponent(owner.username)}&exact=true`);
  if (users.length > 1) throw new Error('Ambiguous local demo identity');
  if (users.length === 1) {
    const user = users[0];
    if (!matchesDemoIdentity(owner, user, verifiedChecks, `${base}/realms/${realm}`)) {
      throw new Error(`Existing identity does not match the private demo setup record: ${owner.username}`);
    }
    owner.userId = user.id;
    // Keycloak requires these fields before completing the browser login flow.
    // Fill missing demo fields only; keep passwords and any entered names unchanged.
    await api(`users/${users[0].id}`, 'PUT', {
      ...users[0],
      credentials: undefined,
      firstName: users[0].firstName || firstName,
      lastName: users[0].lastName || lastName,
      requiredActions: (users[0].requiredActions || []).filter(action =>
        action !== 'UPDATE_PROFILE' && action !== 'VERIFY_PROFILE'),
    });
  } else {
    if (owner.userId) throw new Error(`Previously recorded demo identity is missing: ${owner.username}`);
    await api('users', 'POST', { username: owner.username, email: owner.email, firstName, lastName,
      enabled: true, emailVerified: true, attributes: { webhookDemoManaged: ['true'] },
      credentials: [{ type: 'password', value: owner.password, temporary: false }] });
    const created = await api(`users?username=${encodeURIComponent(owner.username)}&exact=true`);
    if (created.length !== 1) throw new Error('Could not record the newly created demo identity');
    owner.userId = created[0].id;
  }
  await writeFile(credentialsPath, JSON.stringify(credentials, null, 2) + '\n', { mode: 0o600 });
}
console.log('PASS: dashboard PKCE configuration and webhook-api token audience configured.');
console.log('PASS: two synthetic demo owner identities prepared (not customer accounts).');
console.log('PASS: required demo profile names are present; existing passwords retained.');
console.log('Passwords are stored only in .local/demo-owners.json. Do not paste that file into chat.');
