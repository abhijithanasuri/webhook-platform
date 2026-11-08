package com.webhookplatform.application;

import com.webhookplatform.domain.Endpoint;
import com.webhookplatform.domain.OwnerIdentity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class EndpointService {
    private final AccountService accounts;
    private final EndpointRepository endpoints;
    private final SecretProtector secrets;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EndpointService(AccountService accounts, EndpointRepository endpoints, SecretProtector secrets, Clock clock) {
        this.accounts = accounts; this.endpoints = endpoints; this.secrets = secrets; this.clock = clock;
    }

    public Created create(OwnerIdentity owner, String url, List<String> eventTypes, Boolean enabled) {
        String tenant = tenant(owner);
        validate(url, eventTypes);
        String id = "ep_" + UUID.randomUUID();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Endpoint candidate = new Endpoint(tenant, id, url, eventTypes, enabled == null || enabled,
                1, clock.instant(), secrets.encrypt(tenant, id, secret), 1);
        Endpoint saved = endpoints.create(candidate);
        return new Created(saved, secret);
    }

    public Endpoint get(OwnerIdentity owner, String id) {
        String tenant = tenant(owner);
        if (!validId(id)) throw notFound();
        return endpoints.find(tenant, id).orElseThrow(EndpointService::notFound);
    }

    public Endpoint update(OwnerIdentity owner, String id, Long version, String url, List<String> types, Boolean enabled) {
        Endpoint current = get(owner, id);
        checkVersion(current, version);
        if (url == null && types == null && enabled == null) throw EndpointFailure.invalid();
        String nextUrl = url == null ? current.url() : url;
        List<String> nextTypes = types == null ? current.eventTypes() : types;
        validate(nextUrl, nextTypes);
        return endpoints.replace(new Endpoint(current.tenantId(), id, nextUrl, nextTypes,
                enabled == null ? current.enabled() : enabled, current.version() + 1, current.createdAt(),
                current.secretCiphertext(), current.keyVersion()), current.version());
    }

    public Created rotate(OwnerIdentity owner, String id, Long version) {
        Endpoint current = get(owner, id);
        checkVersion(current, version);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Endpoint saved = endpoints.replace(new Endpoint(current.tenantId(), id, current.url(), current.eventTypes(),
                current.enabled(), current.version() + 1, current.createdAt(),
                secrets.encrypt(current.tenantId(), id, secret), current.keyVersion() + 1), current.version());
        return new Created(saved, secret);
    }

    public void delete(OwnerIdentity owner, String id, Long version) {
        Endpoint current = get(owner, id);
        checkVersion(current, version);
        endpoints.delete(current);
    }

    private static void checkVersion(Endpoint current, Long version) {
        if (version == null || version < 1) throw EndpointFailure.invalid();
        if (current.version() != version) throw new EndpointFailure(409, "ENDPOINT_VERSION_CONFLICT", "Read the endpoint again before changing it");
    }

    public EndpointRepository.Page list(OwnerIdentity owner, int limit, String cursor) {
        String tenant = tenant(owner);
        if (limit < 1 || limit > 100) throw EndpointFailure.invalid();
        String afterId = null;
        if (cursor != null) {
            if (cursor.length() > 128) throw EndpointFailure.invalid();
            try { afterId = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException error) { throw EndpointFailure.invalid(); }
            if (!validId(afterId)) throw EndpointFailure.invalid();
        }
        return endpoints.list(tenant, limit, afterId);
    }

    public static String cursor(String id) {
        return id == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private String tenant(OwnerIdentity owner) {
        return accounts.find(owner).orElseThrow(() -> new EndpointFailure(409, "ACCOUNT_REQUIRED", "Create an account first")).tenantId();
    }

    public static void validate(String url, List<String> types) {
        if (url == null || url.length() > 2048 || types == null || types.size() > 20
                || types.stream().anyMatch(type -> type == null || !type.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}"))
                || new HashSet<>(types).size() != types.size()) throw EndpointFailure.invalid();
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            // Initial configuration policy accepts DNS names, not IP literals or local hostnames.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !host.matches("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}")) {
                throw EndpointFailure.invalid();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw EndpointFailure.invalid();
        } catch (IllegalArgumentException error) { throw EndpointFailure.invalid(); }
    }

    private static boolean validId(String id) { return id != null && id.matches("ep_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static EndpointFailure notFound() { return new EndpointFailure(404, "ENDPOINT_NOT_FOUND", "Endpoint not found"); }
    public record Created(Endpoint endpoint, String signingSecret) {}
}
