package com.webhookplatform.adapters;

import com.webhookplatform.application.SecretProtector;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local development only. AWS must use KMS rather than this environment-supplied key. */
public final class LocalSecretProtector implements SecretProtector {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public LocalSecretProtector(String encodedKey) {
        byte[] raw = Base64.getDecoder().decode(encodedKey);
        if (raw.length != 32) throw new IllegalArgumentException("Local signing key must be 32 bytes");
        key = new SecretKeySpec(raw, "AES");
    }
    public String encrypt(String tenantId, String endpointId, String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, tenantId, endpointId, iv);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("Local secret encryption failed", error); }
    }
    public String decrypt(String tenantId, String endpointId, String ciphertext) {
        try {
            String[] parts = ciphertext.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Invalid local ciphertext");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, tenantId, endpointId, Base64.getUrlDecoder().decode(parts[1]));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) { throw new IllegalStateException("Local secret decryption failed", error); }
    }
    private Cipher cipher(int mode, String tenant, String endpoint, byte[] iv) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD((tenant.length() + ":" + tenant + endpoint.length() + ":" + endpoint).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
