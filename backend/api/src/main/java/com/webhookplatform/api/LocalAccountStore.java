package com.webhookplatform.api;

import com.webhookplatform.adapters.DynamoAccountRepository;
import com.webhookplatform.application.AccountRepository;
import com.webhookplatform.application.EndpointRepository;
import com.webhookplatform.application.SecretProtector;
import com.webhookplatform.adapters.DynamoEndpointRepository;
import com.webhookplatform.adapters.LocalSecretProtector;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
@Profile("local")
class LocalAccountStore {
    @Bean(destroyMethod = "close")
    DynamoDbClient localDynamo(@Value("${webhook.store.endpoint}") URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() != 4566) {
            throw new IllegalArgumentException("The local store must use http://127.0.0.1:4566");
        }
        return DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(8)))
                .build();
    }

    @Bean AccountRepository accountRepository(DynamoDbClient db, @Value("${webhook.store.table}") String table) {
        return new DynamoAccountRepository(db, table);
    }
    @Bean EndpointRepository endpointRepository(DynamoDbClient db, @Value("${webhook.store.table}") String table) {
        return new DynamoEndpointRepository(db, table);
    }
    @Bean SecretProtector localSecretProtector(@Value("${WEBHOOK_LOCAL_SIGNING_KEY}") String key) {
        return new LocalSecretProtector(key);
    }
}
