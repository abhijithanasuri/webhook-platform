package com.webhookplatform.adapters;

import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import com.webhookplatform.domain.Endpoint;
import com.webhookplatform.application.EndpointFailure;
import java.util.List;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly targets a local DynamoDB emulator; never uses cloud credentials. */
@EnabledIfSystemProperty(named = "dynamodb.test.endpoint", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class DynamoAccountRepositoryTest {
    DynamoDbClient db;
    String table;
    DynamoAccountRepository repository;

    @BeforeEach void createTable() {
        db = DynamoDbClient.builder().endpointOverride(URI.create(System.getProperty("dynamodb.test.endpoint")))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(5))).build();
        table = "account-test-" + UUID.randomUUID();
        db.createTable(request -> request.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()));
        db.waiter().waitUntilTableExists(request -> request.tableName(table));
        repository = new DynamoAccountRepository(db, table);
    }

    @AfterEach void cleanup() {
        if (db != null) {
            try { if (table != null) db.deleteTable(request -> request.tableName(table)); }
            finally { db.close(); }
        }
    }

    @Test void accountAndLookupCommitTogetherAndSurviveRepositoryRecreation() {
        var owner = new OwnerIdentity("https://issuer.example.test", "same-subject");
        var candidate = account();
        assertThat(repository.findByOwner(owner)).isEmpty();
        assertThat(repository.createIfAbsent(owner, candidate)).isEqualTo(candidate);
        assertThat(new DynamoAccountRepository(db, table).findByOwner(owner)).contains(candidate);
        assertThat(repository.createIfAbsent(owner, account())).isEqualTo(candidate);
        assertThat(db.scan(request -> request.tableName(table).consistentRead(true)).count()).isEqualTo(2);
    }

    @Test void issuerAndSubjectBothScopeOwnership() {
        var one = new OwnerIdentity("https://one.example.test", "shared-subject");
        var two = new OwnerIdentity("https://two.example.test", "shared-subject");
        var first = repository.createIfAbsent(one, account());
        assertThat(repository.findByOwner(two)).isEmpty();
        var second = repository.createIfAbsent(two, account());
        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());
        assertThat(repository.findByOwner(one)).contains(first);
        assertThat(repository.findByOwner(two)).contains(second);
    }

    @Test void competingCreatesResolveToOneAccountWithoutOrphanRecords() throws Exception {
        var owner = new OwnerIdentity("https://issuer.example.test", "racing-owner");
        var tasks = new ArrayList<Callable<Account>>();
        for (int i = 0; i < 8; i++) tasks.add(() -> repository.createIfAbsent(owner, account()));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(tasks);
            var winner = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(winner);
            assertThat(repository.findByOwner(owner)).contains(winner);
        }
        assertThat(db.scan(request -> request.tableName(table).consistentRead(true)).count()).isEqualTo(2);
    }

    private Account account() { return new Account("acct_" + UUID.randomUUID(), Instant.now()); }

    @Test void endpointAndRoutingCommitTogetherWithoutLeakingSecrets() {
        var endpoints = new DynamoEndpointRepository(db, table);
        var candidate = endpoint("tenant-one");
        assertThat(endpoints.create(candidate)).isEqualTo(candidate);
        assertThat(endpoints.find("tenant-one", candidate.endpointId())).contains(candidate);
        assertThat(endpoints.find("tenant-two", candidate.endpointId())).isEmpty();
        var route = routing("tenant-one");
        assertThat(route.get("version").n()).isEqualTo("1");
        assertThat(route.get("endpoints").l()).hasSize(1);
        assertThat(route.toString()).doesNotContain("secretCiphertext", "synthetic-signing-secret");
        assertThat(db.scan(r -> r.tableName(table)).count()).isEqualTo(2);
    }

    @Test void endpointPaginationIsTenantScopedAndDoesNotRepeatItems() {
        var endpoints = new DynamoEndpointRepository(db, table);
        for (int i = 0; i < 3; i++) endpoints.create(endpoint("tenant-one"));
        endpoints.create(endpoint("tenant-two"));
        var ids = new java.util.HashSet<String>();
        String after = null;
        int pages = 0;
        do {
            var page = endpoints.list("tenant-one", 1, after);
            for (var item : page.items()) {
                assertThat(item.tenantId()).isEqualTo("tenant-one");
                assertThat(ids.add(item.endpointId())).isTrue();
            }
            after = page.nextAfterId();
            assertThat(++pages).isLessThan(6);
        } while (after != null);
        assertThat(ids).hasSize(3);
    }

    @Test void anExistingEmptyRoutingSnapshotCanAcceptItsFirstEndpoint() {
        db.putItem(r -> r.tableName(table).item(java.util.Map.of(
                "PK", AttributeValue.builder().s("TENANT#tenant-one").build(),
                "SK", AttributeValue.builder().s("ROUTING").build(),
                "version", AttributeValue.builder().n("0").build(),
                "endpoints", AttributeValue.builder().l(List.<AttributeValue>of()).build())));
        var endpoints = new DynamoEndpointRepository(db, table);
        endpoints.create(endpoint("tenant-one"));
        assertThat(routing("tenant-one").get("version").n()).isEqualTo("1");
    }

    @Test void competingEndpointCreatesCannotExceedTheAccountCapOrLeaveOrphans() throws Exception {
        var endpoints = new DynamoEndpointRepository(db, table);
        for (int i = 0; i < 9; i++) endpoints.create(endpoint("tenant-one"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> tasks = List.of(() -> createAtCap(endpoints), () -> createAtCap(endpoints));
            var results = executor.invokeAll(tasks);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(endpoints.list("tenant-one", 100, null).items()).hasSize(10);
        assertThat(routing("tenant-one").get("endpoints").l()).hasSize(10);
        assertThat(routing("tenant-one").get("version").n()).isEqualTo("10");
        assertThat(db.scan(r -> r.tableName(table)).count()).isEqualTo(11);
    }

    private boolean createAtCap(DynamoEndpointRepository endpoints) {
        try { endpoints.create(endpoint("tenant-one")); return true; }
        catch (EndpointFailure failure) {
            assertThat(failure.code()).isEqualTo("ENDPOINT_LIMIT"); return false;
        }
    }
    @Test void mutationsUpdateRoutingAndDeletionReleasesCapacity() {
        var endpoints = new DynamoEndpointRepository(db, table);
        var original = endpoints.create(endpoint("tenant-one"));
        for (int i = 0; i < 9; i++) endpoints.create(endpoint("tenant-one"));
        var updated = changed(original, "https://changed.example.test/events");
        assertThat(endpoints.replace(updated, 1)).isEqualTo(updated);
        var descriptor = routing("tenant-one").get("endpoints").l().stream()
                .filter(e -> e.m().get("endpointId").s().equals(original.endpointId())).findFirst().orElseThrow().m();
        assertThat(descriptor.get("url").s()).isEqualTo(updated.url());
        assertThat(descriptor.get("enabled").bool()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> endpoints.replace(changed(original, "https://stale.example.test"), 1))
                .isInstanceOfSatisfying(EndpointFailure.class, e -> assertThat(e.code()).isEqualTo("ENDPOINT_VERSION_CONFLICT"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> endpoints.delete(original)).isInstanceOf(EndpointFailure.class);
        endpoints.delete(updated);
        assertThat(endpoints.find("tenant-one", original.endpointId())).isEmpty();
        assertThat(endpoints.list("tenant-one", 100, null).items()).hasSize(9);
        assertThat(routing("tenant-one").get("endpoints").l()).hasSize(9);
        endpoints.create(endpoint("tenant-one"));
        assertThat(endpoints.list("tenant-one", 100, null).items()).hasSize(10);
    }

    @Test void competingUpdatesHaveOneWinnerAndAConsistentSnapshot() throws Exception {
        var endpoints = new DynamoEndpointRepository(db, table);
        var original = endpoints.create(endpoint("tenant-one"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> tasks = List.of(
                    () -> tryReplace(endpoints, changed(original, "https://one.example.test")),
                    () -> tryReplace(endpoints, changed(original, "https://two.example.test")));
            var results = executor.invokeAll(tasks);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        var winner = endpoints.find("tenant-one", original.endpointId()).orElseThrow();
        assertThat(winner.version()).isEqualTo(2);
        assertThat(routing("tenant-one").get("version").n()).isEqualTo("2");
        assertThat(routing("tenant-one").get("endpoints").l().getFirst().m().get("url").s()).isEqualTo(winner.url());
    }
    private boolean tryReplace(DynamoEndpointRepository endpoints, Endpoint candidate) {
        try { endpoints.replace(candidate, 1); return true; }
        catch (EndpointFailure error) {
            assertThat(error.code()).isEqualTo("ENDPOINT_VERSION_CONFLICT"); return false;
        }
    }
    @Test void concurrentChangesToDifferentEndpointsPreserveBothDescriptors() throws Exception {
        var endpoints = new DynamoEndpointRepository(db, table);
        var one = changed(endpoints.create(endpoint("tenant-one")), "https://one.example.test");
        var two = changed(endpoints.create(endpoint("tenant-one")), "https://two.example.test");
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Endpoint>> tasks = List.of(() -> endpoints.replace(one, 1), () -> endpoints.replace(two, 1));
            for (var result : executor.invokeAll(tasks)) assertThat(result.get().version()).isEqualTo(2);
        }
        assertThat(routing("tenant-one").get("version").n()).isEqualTo("4");
        assertThat(routing("tenant-one").get("endpoints").l()).allSatisfy(entry ->
                assertThat(entry.m().get("version").n()).isEqualTo("2"));
    }

    @Test void deleteAndRotationRaceCannotResurrectAnEndpoint() throws Exception {
        var endpoints = new DynamoEndpointRepository(db, table);
        var original = endpoints.create(endpoint("tenant-one"));
        var rotated = new Endpoint(original.tenantId(), original.endpointId(), original.url(), original.eventTypes(),
                true, 2, original.createdAt(), "new-encrypted-key", 2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> tasks = List.of(() -> {
                try { endpoints.delete(original); return true; }
                catch (EndpointFailure error) { assertThat(error.code()).isEqualTo("ENDPOINT_VERSION_CONFLICT"); return false; }
            }, () -> {
                try { endpoints.replace(rotated, 1); return true; }
                catch (EndpointFailure error) { assertThat(error.code()).isIn("ENDPOINT_VERSION_CONFLICT", "ENDPOINT_NOT_FOUND"); return false; }
            });
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        var saved = endpoints.find("tenant-one", original.endpointId());
        assertThat(routing("tenant-one").get("version").n()).isEqualTo("2");
        if (saved.isPresent()) {
            assertThat(saved.get()).isEqualTo(rotated);
            assertThat(routing("tenant-one").get("endpoints").l()).hasSize(1);
        } else assertThat(routing("tenant-one").get("endpoints").l()).isEmpty();
    }
    private Endpoint changed(Endpoint source, String url) {
        return new Endpoint(source.tenantId(), source.endpointId(), url, List.of("invoice.failed"), false,
                source.version() + 1, source.createdAt(), source.secretCiphertext(), source.keyVersion());
    }
    private Endpoint endpoint(String tenant) {
        String id = "ep_" + UUID.randomUUID();
        var secrets = new LocalSecretProtector(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        return new Endpoint(tenant, id, "https://hooks.example.test/events", List.of("invoice.paid"), true,
                1, Instant.now(), secrets.encrypt(tenant, id, "synthetic-signing-secret"), 1);
    }
    private java.util.Map<String, AttributeValue> routing(String tenant) {
        return db.getItem(r -> r.tableName(table).consistentRead(true).key(java.util.Map.of(
                "PK", AttributeValue.builder().s("TENANT#" + tenant).build(),
                "SK", AttributeValue.builder().s("ROUTING").build()))).item();
    }
}
