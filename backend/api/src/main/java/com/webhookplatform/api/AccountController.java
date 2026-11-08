package com.webhookplatform.api;

import com.webhookplatform.application.AccountService;
import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountController {
    private final AccountService accounts;
    AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping("/v1/account")
    Account create(@AuthenticationPrincipal Jwt token, @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account creation takes no request body");
        }
        return accounts.create(owner(token));
    }

    @GetMapping("/v1/account")
    Account get(@AuthenticationPrincipal Jwt token) {
        return accounts.find(owner(token)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private OwnerIdentity owner(Jwt token) {
        return new OwnerIdentity(token.getIssuer().toString(), token.getSubject());
    }
}
