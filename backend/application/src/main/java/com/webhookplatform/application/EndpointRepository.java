package com.webhookplatform.application;

import com.webhookplatform.domain.Endpoint;
import java.util.List;
import java.util.Optional;

public interface EndpointRepository {
    Endpoint create(Endpoint candidate);
    Endpoint replace(Endpoint candidate, long expectedVersion);
    void delete(Endpoint current);
    Optional<Endpoint> find(String tenantId, String endpointId);
    Page list(String tenantId, int limit, String afterId);
    record Page(List<Endpoint> items, String nextAfterId) {}
}
