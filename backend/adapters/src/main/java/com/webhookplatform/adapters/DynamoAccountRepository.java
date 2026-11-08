package com.webhookplatform.adapters;

import com.webhookplatform.application.AccountRepository;
import com.webhookplatform.application.AccountStoreUnavailable;
import com.webhookplatform.domain.Account;
import com.webhookplatform.domain.OwnerIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

public final class DynamoAccountRepository implements AccountRepository {
    private final DynamoDbClient db;
    private final String table;

    public DynamoAccountRepository(DynamoDbClient db, String table) {
        this.db = db;
        this.table = table;
    }

    @Override
    public Optional<Account> findByOwner(OwnerIdentity owner) {
        try {
            String ownerKey = ownerKey(owner);
            var lookup = db.getItem(request -> request.tableName(table)
                    .key(key(ownerKey, "ACCOUNT")).consistentRead(true)).item();
            if (lookup.isEmpty()) return Optional.empty();
            String tenantId = lookup.get("tenantId").s();
            var meta = db.getItem(request -> request.tableName(table)
                    .key(key("TENANT#" + tenantId, "META")).consistentRead(true)).item();
            if (meta.isEmpty() || !ownerKey.equals(meta.get("ownerKey").s())) {
                throw new IllegalStateException("Inconsistent account ownership record");
            }
            return Optional.of(new Account(tenantId, Instant.parse(meta.get("createdAt").s())));
        } catch (SdkException | IllegalStateException | NullPointerException | java.time.DateTimeException error) {
            throw new AccountStoreUnavailable(error);
        }
    }

    @Override
    public Account createIfAbsent(OwnerIdentity owner, Account candidate) {
        String ownerKey = ownerKey(owner);
        Map<String, AttributeValue> lookup = Map.of("PK", value(ownerKey), "SK", value("ACCOUNT"),
                "tenantId", value(candidate.tenantId()));
        Map<String, AttributeValue> meta = Map.of("PK", value("TENANT#" + candidate.tenantId()),
                "SK", value("META"), "tenantId", value(candidate.tenantId()),
                "createdAt", value(candidate.createdAt().toString()), "ownerKey", value(ownerKey),
                "ownerSub", value(owner.subject()), "ownerIssuer", value(owner.issuer()));
        var transaction = TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                .transactItems(put(lookup), put(meta)).build();
        SdkException lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                db.transactWriteItems(transaction);
                return candidate;
            } catch (SdkException error) {
                lastError = error;
                // Includes an uncertain response after commit: resolve it through the durable owner lookup.
                Optional<Account> winner = findByOwner(owner);
                if (winner.isPresent()) return winner.get();
                if (attempt < 3) {
                    try { Thread.sleep(25L * (attempt + 1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AccountStoreUnavailable(interrupted);
                    }
                }
            }
        }
        throw new AccountStoreUnavailable(lastError);
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder().tableName(table).item(item)
                .conditionExpression("attribute_not_exists(PK)").build()).build();
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("PK", value(pk), "SK", value(sk));
    }

    private static AttributeValue value(String text) { return AttributeValue.builder().s(text).build(); }

    private static String ownerKey(OwnerIdentity owner) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] { owner.issuer(), owner.subject() }) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "OWNER#" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
