package com.webhookplatform.application;

import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findByOwner(OwnerIdentity owner);
    Account createIfAbsent(OwnerIdentity owner, Account candidate);
}
