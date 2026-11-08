package com.webhookplatform.adapters;

import com.webhookplatform.application.EndpointFailure;
import com.webhookplatform.application.EndpointRepository;
import com.webhookplatform.domain.Endpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class DynamoEndpointRepository implements EndpointRepository {
    private final DynamoDbClient db;
    private final String table;
    public DynamoEndpointRepository(DynamoDbClient db, String table) { this.db = db; this.table = table; }

    public Endpoint create(Endpoint candidate) {
        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                var current = db.getItem(r -> r.tableName(table).key(key(candidate.tenantId(), "ROUTING")).consistentRead(true)).item();
                long version = current.isEmpty() ? 0 : Long.parseLong(current.get("version").n());
                List<AttributeValue> entries = current.isEmpty() ? new ArrayList<>() : new ArrayList<>(current.get("endpoints").l());
                Optional<Endpoint> existing = find(candidate.tenantId(), candidate.endpointId());
                if (existing.isPresent()) {
                    if (existing.get().equals(candidate)) return existing.get();
                    throw new EndpointFailure(409, "ENDPOINT_CONFLICT", "Endpoint already exists");
                }
                if (entries.size() >= 10) throw new EndpointFailure(409, "ENDPOINT_LIMIT", "An account may configure at most ten endpoints");
                entries.add(AttributeValue.builder().m(Map.of(
                        "endpointId", s(candidate.endpointId()), "url", s(candidate.url()),
                        "eventTypes", types(candidate.eventTypes()), "enabled", bool(candidate.enabled()), "version", n(candidate.version()))).build());
                var routing = Map.of("PK", s("TENANT#" + candidate.tenantId()), "SK", s("ROUTING"),
                        "version", n(version + 1), "endpoints", AttributeValue.builder().l(entries).build());
                Put.Builder routePut = Put.builder().tableName(table).item(routing);
                if (current.isEmpty()) routePut.conditionExpression("attribute_not_exists(PK)");
                else routePut.conditionExpression("#v = :previous").expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(":previous", n(version)));
                var transaction = TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                        .transactItems(TransactWriteItem.builder().put(routePut.build()).build(),
                                TransactWriteItem.builder().put(Put.builder().tableName(table).item(item(candidate))
                                        .conditionExpression("attribute_not_exists(PK)").build()).build()).build();
                try { db.transactWriteItems(transaction); return candidate; }
                catch (SdkException failure) {
                    // Recover a committed-but-unconfirmed create before rebuilding a new routing transaction.
                    Optional<Endpoint> saved = find(candidate.tenantId(), candidate.endpointId());
                    if (saved.isPresent() && saved.get().equals(candidate)) return saved.get();
                    if (attempt == 5) throw new EndpointFailure(failure);
                    try { Thread.sleep(20L * (attempt + 1)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new EndpointFailure(interrupted); }
                }
            }
            throw new IllegalStateException("Unreachable retry state");
        } catch (SdkException | IllegalArgumentException | NullPointerException error) { throw new EndpointFailure(error); }
    }

    public Optional<Endpoint> find(String tenantId, String endpointId) {
        try {
            var result = db.getItem(r -> r.tableName(table).key(key(tenantId, "ENDPOINT#" + endpointId)).consistentRead(true)).item();
            return result.isEmpty() ? Optional.empty() : Optional.of(entity(tenantId, result));
        } catch (SdkException | IllegalArgumentException | NullPointerException | java.time.DateTimeException error) { throw new EndpointFailure(error); }
    }

    public Endpoint replace(Endpoint candidate, long expectedVersion) {
        mutate(candidate, expectedVersion, false);
        return candidate;
    }

    public void delete(Endpoint current) { mutate(current, current.version(), true); }

    private void mutate(Endpoint candidate, long expectedVersion, boolean deleting) {
        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                var saved = find(candidate.tenantId(), candidate.endpointId());
                if (saved.isEmpty()) throw new EndpointFailure(404, "ENDPOINT_NOT_FOUND", "Endpoint not found");
                if (saved.get().version() != expectedVersion)
                    throw new EndpointFailure(409, "ENDPOINT_VERSION_CONFLICT", "Read the endpoint again before changing it");
                var route = db.getItem(r -> r.tableName(table).key(key(candidate.tenantId(), "ROUTING")).consistentRead(true)).item();
                long routeVersion = Long.parseLong(route.get("version").n());
                var entries = new ArrayList<>(route.get("endpoints").l());
                boolean removed = entries.removeIf(entry -> entry.m().get("endpointId").s().equals(candidate.endpointId()));
                if (!removed) throw new EndpointFailure(new IllegalStateException("Missing routing entry"));
                if (!deleting) entries.add(AttributeValue.builder().m(Map.of(
                        "endpointId", s(candidate.endpointId()), "url", s(candidate.url()),
                        "eventTypes", types(candidate.eventTypes()), "enabled", bool(candidate.enabled()), "version", n(candidate.version()))).build());
                var routePut = Put.builder().tableName(table).item(Map.of("PK", s("TENANT#" + candidate.tenantId()),
                        "SK", s("ROUTING"), "version", n(routeVersion + 1), "endpoints", AttributeValue.builder().l(entries).build()))
                        .conditionExpression("#v = :v").expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(":v", n(routeVersion))).build();
                var writes = new ArrayList<TransactWriteItem>();
                writes.add(TransactWriteItem.builder().put(routePut).build());
                if (deleting) {
                    writes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(table)
                            .key(key(candidate.tenantId(), "ENDPOINT#" + candidate.endpointId()))
                            .conditionExpression("#v = :v").expressionAttributeNames(Map.of("#v", "version"))
                            .expressionAttributeValues(Map.of(":v", n(expectedVersion))).build()).build());
                    var tombstone = new java.util.HashMap<>(item(candidate));
                    tombstone.put("SK", s("DELETED#" + candidate.endpointId()));
                    tombstone.put("deletedAt", s(Instant.now().toString()));
                    writes.add(TransactWriteItem.builder().put(Put.builder().tableName(table).item(tombstone)
                            .conditionExpression("attribute_not_exists(PK)").build()).build());
                } else {
                    writes.add(TransactWriteItem.builder().put(Put.builder().tableName(table).item(item(candidate))
                            .conditionExpression("#v = :v").expressionAttributeNames(Map.of("#v", "version"))
                            .expressionAttributeValues(Map.of(":v", n(expectedVersion))).build()).build());
                }
                var transaction = TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString()).transactItems(writes).build();
                try { db.transactWriteItems(transaction); return; }
                catch (SdkException failure) {
                    // Recover an ambiguous commit only when this exact result is still present.
                    var after = find(candidate.tenantId(), candidate.endpointId());
                    if (!deleting && after.isPresent() && after.get().equals(candidate)) return;
                    if (deleting && after.isEmpty()) {
                        var deleted = db.getItem(r -> r.tableName(table).key(key(candidate.tenantId(), "DELETED#" + candidate.endpointId())).consistentRead(true)).item();
                        if (!deleted.isEmpty() && entity(candidate.tenantId(), deleted).equals(candidate)) return;
                    }
                    if (attempt == 5) throw new EndpointFailure(failure);
                    try { Thread.sleep(20L * (attempt + 1)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new EndpointFailure(interrupted); }
                }
            }
        } catch (SdkException | IllegalArgumentException | NullPointerException error) { throw new EndpointFailure(error); }
    }

    public Page list(String tenantId, int limit, String afterId) {
        try {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table).consistentRead(true).limit(limit)
                    .keyConditionExpression("PK = :tenant AND begins_with(SK, :prefix)")
                    .expressionAttributeValues(Map.of(":tenant", s("TENANT#" + tenantId), ":prefix", s("ENDPOINT#")));
            if (afterId != null) request.exclusiveStartKey(key(tenantId, "ENDPOINT#" + afterId));
            var result = db.query(request.build());
            String next = result.lastEvaluatedKey().isEmpty() ? null : result.lastEvaluatedKey().get("SK").s().substring("ENDPOINT#".length());
            return new Page(result.items().stream().map(row -> entity(tenantId, row)).toList(), next);
        } catch (SdkException | IllegalArgumentException | NullPointerException | java.time.DateTimeException error) { throw new EndpointFailure(error); }
    }

    private Map<String, AttributeValue> item(Endpoint endpoint) {
        return Map.ofEntries(Map.entry("PK", s("TENANT#" + endpoint.tenantId())), Map.entry("SK", s("ENDPOINT#" + endpoint.endpointId())),
                Map.entry("endpointId", s(endpoint.endpointId())), Map.entry("url", s(endpoint.url())),
                Map.entry("eventTypes", types(endpoint.eventTypes())), Map.entry("enabled", bool(endpoint.enabled())),
                Map.entry("version", n(endpoint.version())), Map.entry("createdAt", s(endpoint.createdAt().toString())),
                Map.entry("secretCiphertext", s(endpoint.secretCiphertext())), Map.entry("keyVersion", n(endpoint.keyVersion())));
    }
    private Endpoint entity(String tenant, Map<String, AttributeValue> row) {
        return new Endpoint(tenant, row.get("endpointId").s(), row.get("url").s(), row.get("eventTypes").l().stream().map(AttributeValue::s).toList(),
                row.get("enabled").bool(), Long.parseLong(row.get("version").n()), Instant.parse(row.get("createdAt").s()),
                row.get("secretCiphertext").s(), Integer.parseInt(row.get("keyVersion").n()));
    }
    private Map<String, AttributeValue> key(String tenant, String sk) { return Map.of("PK", s("TENANT#" + tenant), "SK", s(sk)); }
    private static AttributeValue types(List<String> types) { return AttributeValue.builder().l(types.stream().map(DynamoEndpointRepository::s).toList()).build(); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static AttributeValue bool(boolean value) { return AttributeValue.builder().bool(value).build(); }
}
