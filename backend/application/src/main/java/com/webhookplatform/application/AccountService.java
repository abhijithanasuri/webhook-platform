package com.webhookplatform.application;

import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public final class AccountService {
    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    public Optional<Account> find(OwnerIdentity owner) { return accounts.findByOwner(owner); }

    public Account create(OwnerIdentity owner) {
        return accounts.findByOwner(owner).orElseGet(() -> accounts.createIfAbsent(owner,
                new Account("acct_" + UUID.randomUUID(), clock.instant())));
    }
}
