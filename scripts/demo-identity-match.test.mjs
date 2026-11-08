import test from 'node:test';
import assert from 'node:assert/strict';
import { matchesDemoIdentity } from './demo-identity-match.mjs';

const issuer = 'http://localhost:8081/realms/webhook-local';
const owner = { username: 'demo-owner-one', email: 'owner-one@example.test' };
const user = { id: 'user-one', username: owner.username, email: owner.email };
test('legacy generated identity is recognized without a custom Keycloak attribute', () => {
  assert.equal(matchesDemoIdentity(owner, user, [], issuer), true);
});
test('a previously recorded ID must still match', () => {
  assert.equal(matchesDemoIdentity({ ...owner, userId: 'old-user' }, user, [], issuer), false);
});
test('an unknown same-name identity with a different email is not adopted', () => {
  assert.equal(matchesDemoIdentity(owner, { ...user, email: 'foreign@example.test' }, [], issuer), false);
});
test('a real prior verified login can recognize an existing demo user with an updated email', () => {
  assert.equal(matchesDemoIdentity(owner, { ...user, email: 'updated@example.test' },
    [{ issuer, subject: user.id }], issuer), true);
});
test('a verification from another issuer is not trusted', () => {
  assert.equal(matchesDemoIdentity(owner, { ...user, email: 'foreign@example.test' },
    [{ issuer: 'https://foreign.example.test', subject: user.id }], issuer), false);
});
test('the other demo user or arbitrary names cannot be matched by ID alone', () => {
  assert.equal(matchesDemoIdentity({ ...owner, userId: user.id }, { ...user, username: 'demo-owner-two' }, [], issuer), false);
  assert.equal(matchesDemoIdentity({ ...owner, username: 'someone-else', userId: user.id }, user, [], issuer), false);
});
