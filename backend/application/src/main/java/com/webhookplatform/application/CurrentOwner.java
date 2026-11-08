package com.webhookplatform.application;

import com.webhookplatform.domain.OwnerIdentity;

/** Accepts claims only after the authentication adapter has verified them. */
public final class CurrentOwner {
    public OwnerIdentity identify(String verifiedIssuer, String verifiedSubject) {
        return new OwnerIdentity(verifiedIssuer, verifiedSubject);
    }
}
