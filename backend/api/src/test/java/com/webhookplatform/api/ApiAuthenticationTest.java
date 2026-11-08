package com.webhookplatform.api;

import com.webhookplatform.application.AccountRepository;
import com.webhookplatform.application.AccountStoreUnavailable;
import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import com.webhookplatform.domain.Endpoint;
import com.webhookplatform.application.EndpointRepository;
import com.webhookplatform.application.EndpointFailure;
import com.webhookplatform.application.SecretProtector;
import com.webhookplatform.adapters.LocalSecretProtector;
import java.util.List;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Full HTTP requests and signed tokens exercise the real decoder and security filter chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiAuthenticationTest.StoreFixture.class)
@Timeout(20)
class ApiAuthenticationTest {
    private static final String ISSUER = "https://identity.example.test/realms/test";
    private static final RSAKey SIGNING_KEY;
    private static final HttpServer JWKS;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) { stream.write(body); }
            });
            JWKS.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("webhook.security.issuer", () -> ISSUER);
        properties.add("webhook.security.jwks", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        properties.add("webhook.security.client-id", () -> "webhook-dashboard");
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired TestAccounts accounts;
    @Autowired TestEndpoints endpoints;

    @TestConfiguration
    static class StoreFixture {
        @Bean TestAccounts accountRepository() { return new TestAccounts(); }
        @Bean TestEndpoints endpointRepository() { return new TestEndpoints(); }
        @Bean SecretProtector secrets() { return new LocalSecretProtector(Base64.getEncoder().encodeToString(new byte[32])); }
    }

    static class TestEndpoints implements EndpointRepository {
        final ConcurrentHashMap<String, Endpoint> data = new ConcurrentHashMap<>();
        public synchronized Endpoint create(Endpoint candidate) {
            if (data.values().stream().filter(e -> e.tenantId().equals(candidate.tenantId())).count() >= 10)
                throw new EndpointFailure(409, "ENDPOINT_LIMIT", "An account may configure at most ten endpoints");
            data.put(candidate.tenantId() + ":" + candidate.endpointId(), candidate); return candidate;
        }
        public Optional<Endpoint> find(String tenant, String id) { return Optional.ofNullable(data.get(tenant + ":" + id)); }
        public synchronized Endpoint replace(Endpoint candidate, long version) {
            var current = find(candidate.tenantId(), candidate.endpointId()).orElseThrow();
            if (current.version() != version) throw new EndpointFailure(409, "ENDPOINT_VERSION_CONFLICT", "Stale version");
            data.put(candidate.tenantId() + ":" + candidate.endpointId(), candidate); return candidate;
        }
        public synchronized void delete(Endpoint current) {
            if (!data.remove(current.tenantId() + ":" + current.endpointId(), current))
                throw new EndpointFailure(409, "ENDPOINT_VERSION_CONFLICT", "Stale version");
        }
        public Page list(String tenant, int limit, String afterId) {
            var all = data.values().stream().filter(e -> e.tenantId().equals(tenant))
                    .filter(e -> afterId == null || e.endpointId().compareTo(afterId) > 0)
                    .sorted(java.util.Comparator.comparing(Endpoint::endpointId)).toList();
            var items = all.stream().limit(limit).toList();
            return new Page(items, all.size() > limit ? items.getLast().endpointId() : null);
        }
    }

    static class TestAccounts implements AccountRepository {
        final ConcurrentHashMap<OwnerIdentity, Account> data = new ConcurrentHashMap<>();
        boolean unavailable;
        public Optional<Account> findByOwner(OwnerIdentity owner) {
            if (unavailable) throw new AccountStoreUnavailable(new IllegalStateException("private storage details"));
            return Optional.ofNullable(data.get(owner));
        }
        public Account createIfAbsent(OwnerIdentity owner, Account candidate) {
            return data.computeIfAbsent(owner, ignored -> candidate);
        }
    }

    @BeforeEach void boundRequests() {
        accounts.data.clear();
        accounts.unavailable = false;
        endpoints.data.clear();
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        http.getRestTemplate().setRequestFactory(factory);
    }

    @AfterAll static void stopJwks() { JWKS.stop(0); }

    @Test void healthIsPublicAndDoesNotExposeDetails() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test void missingCredentialsAreRejected() { assertUnauthorized(null); }
    @Test void malformedTokenIsRejected() { assertUnauthorized("not-a-jwt"); }
    @Test void expiredTokenIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
    }
    @Test void wrongIssuerIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.issuer("https://foreign.example.test")));
    }
    @Test void wrongClientIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.claim("azp", "foreign-client")));
    }
    @Test void wrongAudienceIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.audience("another-api")));
    }
    @Test void missingAudienceIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.audience((String) null)));
    }
    @Test void missingIssuedAtIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.issueTime(null)));
    }
    @Test void notYetValidTokenIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120)))));
    }
    @Test void identityTokenIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.claim("typ", "ID")));
    }
    @Test void missingSubjectIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.subject(null)));
    }
    @Test void missingExpiryIsRejected() throws Exception {
        assertUnauthorized(token(claims -> claims.expirationTime(null)));
    }
    @Test void invalidSignatureIsRejected() throws Exception {
        RSAKey foreignKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        assertUnauthorized(sign(claims().build(), foreignKey));
    }
    @Test void validAccessTokenReturnsVerifiedIdentityWithoutSession() throws Exception {
        ResponseEntity<String> response = request(token(claims -> {}));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("owner-one", ISSUER).doesNotContain("tenantId");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertUnauthorized(null);
    }
    @Test void unexposedManagementEndpointsRemainProtected() {
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test void accountRequiresAuthentication() {
        assertThat(account(HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(account(HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test void accountCreationIsRepeatableAndReadBackUsesAuthenticatedOwner() throws Exception {
        String owner = token(claims -> {});
        assertThat(account(HttpMethod.GET, owner, null).getStatusCode().value()).isEqualTo(404);
        var created = account(HttpMethod.POST, owner, null);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(account(HttpMethod.POST, owner, null).getBody()).isEqualTo(created.getBody());
        assertThat(account(HttpMethod.GET, owner, null).getBody()).isEqualTo(created.getBody());
    }

    @Test void twoOwnersNeverReadOrCreateEachOthersAccount() throws Exception {
        String one = token(claims -> claims.subject("owner-one"));
        String two = token(claims -> claims.subject("owner-two"));
        var first = account(HttpMethod.POST, one, null);
        assertThat(account(HttpMethod.GET, two, null).getStatusCode().value()).isEqualTo(404);
        var second = account(HttpMethod.POST, two, null);
        String firstId = json.readTree(first.getBody()).get("tenantId").asText();
        String secondId = json.readTree(second.getBody()).get("tenantId").asText();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(account(HttpMethod.GET, one, null).getBody()).isEqualTo(first.getBody());
        assertThat(account(HttpMethod.GET, two, null).getBody()).isEqualTo(second.getBody());
    }

    @Test void suppliedAccountOrOwnerFieldsAreRejected() throws Exception {
        var response = account(HttpMethod.POST, token(claims -> {}),
                "{\"tenantId\":\"foreign\",\"ownerSub\":\"owner-two\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(accounts.data).isEmpty();
    }

    @Test void storageFailureIsSanitizedAndDoesNotClaimAcceptance() throws Exception {
        accounts.unavailable = true;
        var response = account(HttpMethod.POST, token(claims -> {}), null);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("ACCOUNT_STORE_UNAVAILABLE", "requestId")
                .doesNotContain("private storage details", "owner-one");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    private ResponseEntity<String> account(HttpMethod method, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        if (body != null) headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange("/v1/account", method, new HttpEntity<>(body, headers), String.class);
    }

    @Test void endpointCreateReturnsSecretOnceAndReadsNeverExposeStoredCiphertext() throws Exception {
        String owner = token(claims -> {});
        account(HttpMethod.POST, owner, null);
        var created = endpoint(HttpMethod.POST, "/v1/endpoints", owner, validEndpoint());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        var result = json.readTree(created.getBody());
        String secret = result.get("signingSecret").asText();
        String id = result.get("endpoint").get("endpointId").asText();
        assertThat(Base64.getUrlDecoder().decode(secret)).hasSize(32);
        var read = endpoint(HttpMethod.GET, "/v1/endpoints/" + id, owner, null);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody()).doesNotContain(secret, "secretCiphertext", "signingSecret");
        assertThat(endpoint(HttpMethod.GET, "/v1/endpoints", owner, null).getBody())
                .doesNotContain(secret, "secretCiphertext", "signingSecret");
        assertThat(endpoints.data.values().iterator().next().secretCiphertext()).doesNotContain(secret);
    }

    @Test void foreignEndpointIdIsIndistinguishableFromMissingAndListsAreScoped() throws Exception {
        String one = token(claims -> claims.subject("one"));
        String two = token(claims -> claims.subject("two"));
        account(HttpMethod.POST, one, null); account(HttpMethod.POST, two, null);
        String id = json.readTree(endpoint(HttpMethod.POST, "/v1/endpoints", one, validEndpoint()).getBody())
                .get("endpoint").get("endpointId").asText();
        assertThat(endpoint(HttpMethod.GET, "/v1/endpoints/" + id, two, null).getStatusCode().value()).isEqualTo(404);
        assertThat(json.readTree(endpoint(HttpMethod.GET, "/v1/endpoints", two, null).getBody()).get("items").size()).isZero();
        String foreignCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
        assertThat(json.readTree(endpoint(HttpMethod.GET, "/v1/endpoints?cursor=" + foreignCursor, two, null).getBody()).get("items").size()).isZero();
    }

    @Test void endpointsRequireAnAccountAndAuthentication() throws Exception {
        assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", null, validEndpoint()).getStatusCode().value()).isEqualTo(401);
        assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", token(claims -> {}), validEndpoint()).getStatusCode().value()).isEqualTo(409);
    }

    @Test void invalidDestinationsAndSubscriptionsAreRejected() throws Exception {
        String owner = token(claims -> {}); account(HttpMethod.POST, owner, null);
        for (String url : List.of("http://hooks.example.test/a", "https://127.0.0.1/a", "https://[::1]/a",
                "https://localhost/a", "https://hooks.internal/a", "https://user:pass@hooks.example.test/a",
                "https://hooks.example.test:8443/a", "https://hooks.example.test/a#fragment")) {
            String body = json.writeValueAsString(java.util.Map.of("url", url, "eventTypes", List.of("invoice.paid")));
            assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, body).getStatusCode().value()).isEqualTo(400);
        }
        for (List<String> types : List.of(List.of("invoice.*"), List.of("same", "same"), List.of(""))) {
            String body = json.writeValueAsString(java.util.Map.of("url", "https://hooks.example.test/a", "eventTypes", types));
            assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, body).getStatusCode().value()).isEqualTo(400);
        }
        var tooMany = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "event.type" + i).toList();
        assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, json.writeValueAsString(
                java.util.Map.of("url", "https://hooks.example.test/a", "eventTypes", tooMany))).getStatusCode().value()).isEqualTo(400);
        assertThat(endpoints.data).isEmpty();
    }

    @Test void endpointBodyCannotChooseTenantOrSecret() throws Exception {
        String owner = token(claims -> {}); account(HttpMethod.POST, owner, null);
        String body = "{\"url\":\"https://hooks.example.test/a\",\"eventTypes\":[],\"tenantId\":\"foreign\",\"signingSecret\":\"chosen\"}";
        assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, body).getStatusCode().value()).isEqualTo(400);
    }

    @Test void endpointCapAndPaginationAreEnforcedWithoutDuplicates() throws Exception {
        String owner = token(claims -> {}); account(HttpMethod.POST, owner, null);
        for (int i = 0; i < 10; i++) assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, validEndpoint()).getStatusCode().value()).isEqualTo(201);
        assertThat(endpoint(HttpMethod.POST, "/v1/endpoints", owner, validEndpoint()).getStatusCode().value()).isEqualTo(409);
        var first = json.readTree(endpoint(HttpMethod.GET, "/v1/endpoints?limit=6", owner, null).getBody());
        var second = json.readTree(endpoint(HttpMethod.GET, "/v1/endpoints?limit=6&cursor=" + first.get("nextCursor").asText(), owner, null).getBody());
        var ids = new java.util.HashSet<String>();
        first.get("items").forEach(item -> ids.add(item.get("endpointId").asText()));
        second.get("items").forEach(item -> ids.add(item.get("endpointId").asText()));
        assertThat(ids).hasSize(10); assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(endpoint(HttpMethod.GET, "/v1/endpoints?cursor=malformed", owner, null).getStatusCode().value()).isEqualTo(400);
        assertThat(endpoint(HttpMethod.GET, "/v1/endpoints?limit=101", owner, null).getStatusCode().value()).isEqualTo(400);
    }

    private String validEndpoint() { return "{\"url\":\"https://hooks.example.test/events\",\"eventTypes\":[\"invoice.paid\"]}"; }
    @Test void endpointMutationsRequireCurrentVersionAndKeepSecretsPrivate() throws Exception {
        String owner = token(claims -> {});
        account(HttpMethod.POST, owner, null);
        var created = json.readTree(endpoint(HttpMethod.POST, "/v1/endpoints", owner, validEndpoint()).getBody());
        String path = "/v1/endpoints/" + created.get("endpoint").get("endpointId").asText();
        var updated = endpoint(HttpMethod.PATCH, path, owner, "{\"version\":1,\"enabled\":false,\"url\":\"https://new.example.test/events\",\"eventTypes\":[\"invoice.failed\"]}");
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(updated.getBody()).get("version").asLong()).isEqualTo(2);
        assertThat(json.readTree(updated.getBody()).get("enabled").asBoolean()).isFalse();
        assertThat(updated.getBody()).doesNotContain("signingSecret", "secretCiphertext");
        assertThat(endpoint(HttpMethod.PATCH, path, owner, "{\"version\":1,\"enabled\":true}").getStatusCode().value()).isEqualTo(409);
        assertThat(endpoint(HttpMethod.PATCH, path, owner, "{\"enabled\":true}").getStatusCode().value()).isEqualTo(400);
        var rotated = endpoint(HttpMethod.POST, path + "/rotate-secret", owner, "{\"version\":2}");
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        var rotation = json.readTree(rotated.getBody());
        assertThat(rotation.get("signingSecret").asText()).hasSize(43).isNotEqualTo(created.get("signingSecret").asText());
        assertThat(rotation.get("endpoint").get("keyVersion").asInt()).isEqualTo(2);
        assertThat(endpoint(HttpMethod.GET, path, owner, null).getBody()).doesNotContain(rotation.get("signingSecret").asText(), "secretCiphertext");
        assertThat(endpoint(HttpMethod.DELETE, path + "?version=2", owner, null).getStatusCode().value()).isEqualTo(409);
        assertThat(endpoint(HttpMethod.DELETE, path + "?version=3", owner, null).getStatusCode().value()).isEqualTo(204);
        assertThat(endpoint(HttpMethod.GET, path, owner, null).getStatusCode().value()).isEqualTo(404);
        assertThat(json.readTree(endpoint(HttpMethod.GET, "/v1/endpoints", owner, null).getBody()).get("items").size()).isZero();
    }

    @Test void foreignOwnersCannotMutateEndpoints() throws Exception {
        String one = token(claims -> {}), two = token(claims -> claims.subject("owner-two"));
        account(HttpMethod.POST, one, null); account(HttpMethod.POST, two, null);
        String id = json.readTree(endpoint(HttpMethod.POST, "/v1/endpoints", one, validEndpoint()).getBody()).get("endpoint").get("endpointId").asText();
        String path = "/v1/endpoints/" + id;
        assertThat(endpoint(HttpMethod.PATCH, path, two, "{\"version\":1,\"enabled\":false}").getStatusCode().value()).isEqualTo(404);
        assertThat(endpoint(HttpMethod.POST, path + "/rotate-secret", two, "{\"version\":1}").getStatusCode().value()).isEqualTo(404);
        assertThat(endpoint(HttpMethod.DELETE, path + "?version=1", two, null).getStatusCode().value()).isEqualTo(404);
        assertThat(endpoint(HttpMethod.GET, path, one, null).getStatusCode().value()).isEqualTo(200);
    }
    private ResponseEntity<String> endpoint(HttpMethod method, String route, String token, String body) {
        HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token);
        if (body != null) headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(route, method, new HttpEntity<>(body, headers), String.class);
    }

    private void assertUnauthorized(String token) {
        assertThat(request(token).getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<String> request(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("owner-one")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .audience("webhook-api").claim("azp", "webhook-dashboard").claim("typ", "Bearer");
    }

    private static String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var claims = claims();
        change.accept(claims);
        return sign(claims.build(), SIGNING_KEY);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
