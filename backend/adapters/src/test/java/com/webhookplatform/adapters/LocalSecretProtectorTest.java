package com.webhookplatform.adapters;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalSecretProtectorTest {
    private final LocalSecretProtector secrets = new LocalSecretProtector(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void encryptionUsesFreshNonceAndRoundTripsOnlyWithMatchingContext() {
        String first = secrets.encrypt("tenant-one", "endpoint-one", "synthetic-secret");
        String second = secrets.encrypt("tenant-one", "endpoint-one", "synthetic-secret");
        assertThat(first).isNotEqualTo(second).doesNotContain("synthetic-secret");
        assertThat(secrets.decrypt("tenant-one", "endpoint-one", first)).isEqualTo("synthetic-secret");
        assertThatThrownBy(() -> secrets.decrypt("tenant-two", "endpoint-one", first)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt("tenant-one", "endpoint-two", first)).isInstanceOf(IllegalStateException.class);
    }
    @Test void wrongKeyAndMalformedCiphertextAreRejected() {
        String encrypted = secrets.encrypt("tenant", "endpoint", "synthetic-secret");
        byte[] other = new byte[32]; other[0] = 1;
        var wrongKey = new LocalSecretProtector(Base64.getEncoder().encodeToString(other));
        assertThatThrownBy(() -> wrongKey.decrypt("tenant", "endpoint", encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt("tenant", "endpoint", "v2.invalid")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalSecretProtector(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
    }
}
