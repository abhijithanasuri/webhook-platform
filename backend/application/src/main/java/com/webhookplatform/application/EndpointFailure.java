package com.webhookplatform.application;

public final class EndpointFailure extends RuntimeException {
    private final String code;
    private final int status;
    public EndpointFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public EndpointFailure(Throwable cause) {
        super("Endpoint storage is temporarily unavailable", cause);
        this.status = 503; this.code = "ENDPOINT_STORE_UNAVAILABLE";
    }
    public int status() { return status; }
    public String code() { return code; }
    public static EndpointFailure invalid() {
        return new EndpointFailure(400, "INVALID_ENDPOINT", "Provide a valid HTTPS destination and up to twenty unique exact event types");
    }
}
