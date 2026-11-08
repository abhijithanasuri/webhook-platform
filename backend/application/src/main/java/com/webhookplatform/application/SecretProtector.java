package com.webhookplatform.application;

public interface SecretProtector {
    String encrypt(String tenantId, String endpointId, String plaintext);
    String decrypt(String tenantId, String endpointId, String ciphertext);
}
