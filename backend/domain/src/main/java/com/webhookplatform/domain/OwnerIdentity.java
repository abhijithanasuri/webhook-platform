package com.webhookplatform.domain;

import java.util.Objects;

/** An identity is scoped by issuer and subject; it is not yet a customer account. */
public record OwnerIdentity(String issuer, String subject) {
    public OwnerIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("Issuer and subject must be nonblank");
        }
    }
}
