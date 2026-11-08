package com.webhookplatform.api;

import com.webhookplatform.application.EndpointService;
import com.webhookplatform.domain.Endpoint;
import com.webhookplatform.domain.OwnerIdentity;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/endpoints")
class EndpointController {
    private final EndpointService endpoints;
    EndpointController(EndpointService endpoints) { this.endpoints = endpoints; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@AuthenticationPrincipal Jwt token, @RequestBody CreateRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var created = endpoints.create(owner(token), request.url(), request.eventTypes(), request.enabled());
        return new Created(view(created.endpoint()), created.signingSecret());
    }
    @GetMapping("/{id}")
    View get(@AuthenticationPrincipal Jwt token, @PathVariable String id) { return view(endpoints.get(owner(token), id)); }
    @PatchMapping("/{id}")
    View update(@AuthenticationPrincipal Jwt token, @PathVariable String id, @RequestBody UpdateRequest request) {
        return view(endpoints.update(owner(token), id, request.version(), request.url(), request.eventTypes(), request.enabled()));
    }
    @PostMapping("/{id}/rotate-secret")
    Created rotate(@AuthenticationPrincipal Jwt token, @PathVariable String id, @RequestBody VersionRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var result = endpoints.rotate(owner(token), id, request.version());
        return new Created(view(result.endpoint()), result.signingSecret());
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt token, @PathVariable String id, @RequestParam long version) {
        endpoints.delete(owner(token), id, version);
    }
    @GetMapping
    Page list(@AuthenticationPrincipal Jwt token, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor) {
        var page = endpoints.list(owner(token), limit, cursor);
        return new Page(page.items().stream().map(EndpointController::view).toList(), EndpointService.cursor(page.nextAfterId()));
    }
    private OwnerIdentity owner(Jwt token) { return new OwnerIdentity(token.getIssuer().toString(), token.getSubject()); }
    private static View view(Endpoint endpoint) { return new View(endpoint.endpointId(), endpoint.url(), endpoint.eventTypes(),
            endpoint.enabled(), endpoint.version(), endpoint.createdAt(), endpoint.keyVersion()); }
    record CreateRequest(String url, List<String> eventTypes, Boolean enabled) {}
    record UpdateRequest(Long version, String url, List<String> eventTypes, Boolean enabled) {}
    record VersionRequest(Long version) {}
    record View(String endpointId, String url, List<String> eventTypes, boolean enabled, long version, Instant createdAt, int keyVersion) {}
    record Created(View endpoint, String signingSecret) {}
    record Page(List<View> items, String nextCursor) {}
}
