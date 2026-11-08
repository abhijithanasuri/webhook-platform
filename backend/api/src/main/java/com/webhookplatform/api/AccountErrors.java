package com.webhookplatform.api;

import com.webhookplatform.application.AccountStoreUnavailable;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AccountController.class)
class AccountErrors {
    private static final Logger LOG = LoggerFactory.getLogger(AccountErrors.class);

    @ExceptionHandler(AccountStoreUnavailable.class)
    ResponseEntity<Map<String, String>> unavailable(AccountStoreUnavailable failure) {
        String category = failure.getCause() instanceof ResourceNotFoundException ? "TABLE_MISSING"
                : failure.getCause() instanceof SdkClientException ? "CLIENT_OR_CONNECTION"
                : failure.getCause() instanceof DynamoDbException ? "DYNAMODB_SERVICE"
                : "DATA_OR_INTERNAL";
        String id = UUID.randomUUID().toString();
        // Deliberately exclude raw exception messages, request data, owner IDs, and credentials.
        LOG.warn("Account storage unavailable category={} requestId={}", category, id);
        return ResponseEntity.status(503).header("X-Request-Id", id).body(Map.of(
                "code", "ACCOUNT_STORE_UNAVAILABLE", "message", "Account storage is temporarily unavailable", "requestId", id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> invalidRequest(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return error(status, status == 404 ? "ACCOUNT_NOT_FOUND" : "INVALID_REQUEST",
                status == 404 ? "Account not found" : "Account creation takes no request body");
    }

    private ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        String id = UUID.randomUUID().toString();
        return ResponseEntity.status(status).header("X-Request-Id", id)
                .body(Map.of("code", code, "message", message, "requestId", id));
    }
}
