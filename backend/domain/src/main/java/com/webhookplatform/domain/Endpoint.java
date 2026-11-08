package com.webhookplatform.domain;

import java.time.Instant;
import java.util.List;

/** Internal entity. API responses must never serialize secretCiphertext. */
public record Endpoint(String tenantId, String endpointId, String url, List<String> eventTypes,
        boolean enabled, long version, Instant createdAt, String secretCiphertext, int keyVersion) {
    public Endpoint { eventTypes = List.copyOf(eventTypes); }
}
