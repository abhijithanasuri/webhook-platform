package com.webhookplatform.api;

import com.webhookplatform.application.*;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EndpointConfiguration {
    @Bean EndpointService endpointService(AccountService accounts, EndpointRepository endpoints, SecretProtector secrets) {
        return new EndpointService(accounts, endpoints, secrets, Clock.systemUTC());
    }
}
