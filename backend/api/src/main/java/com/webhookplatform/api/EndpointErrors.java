package com.webhookplatform.api;

import com.webhookplatform.application.EndpointFailure;
import com.webhookplatform.application.AccountStoreUnavailable;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = EndpointController.class)
class EndpointErrors {
    private static final Logger LOG = LoggerFactory.getLogger(EndpointErrors.class);
    @ExceptionHandler(EndpointFailure.class)
    ResponseEntity<Map<String, String>> failure(EndpointFailure error) {
        return response(error.status(), error.code(), error.getMessage());
    }
    @ExceptionHandler(AccountStoreUnavailable.class)
    ResponseEntity<Map<String, String>> accountUnavailable() {
        return response(503, "ACCOUNT_STORE_UNAVAILABLE", "Account storage is temporarily unavailable");
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> malformed() { return failure(EndpointFailure.invalid()); }
    private ResponseEntity<Map<String, String>> response(int status, String code, String message) {
        String id = UUID.randomUUID().toString();
        if (status == 503) LOG.warn("Endpoint operation unavailable code={} requestId={}", code, id);
        return ResponseEntity.status(status).header("X-Request-Id", id).body(Map.of("code", code, "message", message, "requestId", id));
    }
}
