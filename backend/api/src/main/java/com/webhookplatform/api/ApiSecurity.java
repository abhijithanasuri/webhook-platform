package com.webhookplatform.api;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurity {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Bearer tokens only: no cookie/session authentication is accepted.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }

    @Bean
    NimbusJwtDecoder jwtDecoder(
            @Value("${webhook.security.issuer}") String issuer,
            @Value("${webhook.security.jwks}") String jwks,
            @Value("${webhook.security.client-id}") String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        var defaultClaims = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            var mapped = defaultClaims.convert(claims);
            // Spring infers iat from exp when absent; this API requires the signed claim itself.
            if (!claims.containsKey("iat")) mapped.remove("iat");
            return mapped;
        });
        OAuth2TokenValidator<Jwt> accessToken = token -> {
            boolean valid = token.getSubject() != null && !token.getSubject().isBlank()
                    && token.getExpiresAt() != null
                    && token.getIssuedAt() != null
                    && "Bearer".equals(token.getClaimAsString("typ"))
                    && clientId.equals(token.getClaimAsString("azp"))
                    && token.getAudience() != null
                    && token.getAudience().contains("webhook-api");
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer), accessToken));
        return decoder;
    }
}
