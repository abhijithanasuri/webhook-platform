package com.webhookplatform.api;

import com.webhookplatform.application.AccountRepository;
import com.webhookplatform.application.AccountService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AccountConfiguration {
    @Bean AccountService accountService(AccountRepository accounts) {
        return new AccountService(accounts, Clock.systemUTC());
    }
}
