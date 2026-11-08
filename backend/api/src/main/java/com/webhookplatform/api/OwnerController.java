package com.webhookplatform.api;

import com.webhookplatform.application.CurrentOwner;
import com.webhookplatform.domain.OwnerIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OwnerController {
    private final CurrentOwner currentOwner = new CurrentOwner();

    /** Authentication diagnostic; account creation and tenant mapping follow separately. */
    @GetMapping("/v1/me")
    public OwnerIdentity currentOwner(@AuthenticationPrincipal Jwt token) {
        return currentOwner.identify(token.getIssuer().toString(), token.getSubject());
    }
}
