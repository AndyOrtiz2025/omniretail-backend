package com.omniretail.backend.shared.security;

/** Se traduce al 429 {@code RATE_LIMITED} de {@link RateLimitedResponse} con su cabecera {@code Retry-After}. */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(RateLimitedResponse.MESSAGE);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
