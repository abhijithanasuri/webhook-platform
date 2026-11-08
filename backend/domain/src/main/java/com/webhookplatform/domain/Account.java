package com.webhookplatform.domain;

import java.time.Instant;

public record Account(String tenantId, Instant createdAt) {}
