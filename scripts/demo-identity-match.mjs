// This guard is only for the two synthetic identities in the private local setup record.
export function matchesDemoIdentity(owner, user, verifiedChecks, issuer) {
  if (!['demo-owner-one', 'demo-owner-two'].includes(owner.username) || user.username !== owner.username) return false;
  if (owner.userId) return owner.userId === user.id;
  const verified = verifiedChecks.some(check => check.issuer === issuer && check.subject === user.id);
  const generatedEmail = `${owner.username.replace('demo-', '')}@example.test`;
  return verified || (owner.email === generatedEmail && user.email === generatedEmail);
}
