package com.webhookplatform.application;

public class AccountStoreUnavailable extends RuntimeException {
    public AccountStoreUnavailable(Throwable cause) { super("Account storage unavailable", cause); }
}
