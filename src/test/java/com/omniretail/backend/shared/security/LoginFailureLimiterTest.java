package com.omniretail.backend.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginFailureLimiterTest {

    private static final String IP = "203.0.113.8";
    private static final String EMAIL = "ana@test.local";

    private final MutableClock clock = new MutableClock();

    @Test
    void fiveFailuresBlockTheSameIpAndEmailWithRetryAfterUntilTheWindowEnds() {
        LoginFailureLimiter limiter = limiter(100);

        for (int failure = 1; failure <= 4; failure++) {
            limiter.recordFailure(IP, EMAIL);
            assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
        }
        limiter.recordFailure(IP, EMAIL);

        clock.advance(Duration.ofSeconds(100));
        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(800));
    }

    @Test
    void theBlockExpiresWithTheWindow() {
        LoginFailureLimiter limiter = limiter(100);
        failTimes(limiter, IP, EMAIL, 5);

        clock.advance(Duration.ofSeconds(900));

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
        // La ventana vencida empieza de cero: un fallo nuevo no vuelve a bloquear.
        limiter.recordFailure(IP, EMAIL);
        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void clearResetsTheCounter() {
        LoginFailureLimiter limiter = limiter(100);
        failTimes(limiter, IP, EMAIL, 4);

        limiter.clear(IP, EMAIL);
        failTimes(limiter, IP, EMAIL, 4);

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void otherIpsAndOtherEmailsAreIndependent() {
        LoginFailureLimiter limiter = limiter(100);
        failTimes(limiter, IP, EMAIL, 5);

        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL)).isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> limiter.checkAllowed("198.51.100.4", EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed(IP, "otro@test.local")).doesNotThrowAnyException();
    }

    @Test
    void whenTheTrackedKeysAreFullExpiredEntriesAreEvictedAndNewKeysAreOtherwiseNotTracked() {
        LoginFailureLimiter limiter = limiter(1);
        limiter.recordFailure(IP, EMAIL);

        failTimes(limiter, IP, "otro@test.local", 5);
        assertThatCode(() -> limiter.checkAllowed(IP, "otro@test.local")).doesNotThrowAnyException();

        clock.advance(Duration.ofSeconds(900));
        failTimes(limiter, IP, "otro@test.local", 5);
        assertThatThrownBy(() -> limiter.checkAllowed(IP, "otro@test.local"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void disabledNeverBlocks() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(new LoginFailureLimitProperties(false, 5, 900, 100), clock);
        failTimes(limiter, IP, EMAIL, 10);

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    private LoginFailureLimiter limiter(int maxTrackedKeys) {
        return new LoginFailureLimiter(new LoginFailureLimitProperties(true, 5, 900, maxTrackedKeys), clock);
    }

    private static void failTimes(LoginFailureLimiter limiter, String ip, String email, int times) {
        for (int failure = 0; failure < times; failure++) {
            limiter.recordFailure(ip, email);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
