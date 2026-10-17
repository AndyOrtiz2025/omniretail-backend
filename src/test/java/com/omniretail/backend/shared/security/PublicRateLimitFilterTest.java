package com.omniretail.backend.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitFilterTest {

    @Test
    void limitsTheSensitiveRouteAndReturnsRetryAfter() throws Exception {
        PublicRateLimitFilter filter = filter(2);
        AtomicInteger forwarded = new AtomicInteger();

        MockHttpServletResponse first = send(filter, "/api/v1/auth/login", "203.0.113.8", null, forwarded);
        MockHttpServletResponse second = send(filter, "/api/v1/auth/login", "203.0.113.8", null, forwarded);
        MockHttpServletResponse blocked = send(filter, "/api/v1/auth/login", "203.0.113.8", null, forwarded);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(forwarded.get()).isEqualTo(2);
    }

    @Test
    void distinguishesTrustedProxyClientAddressesButIgnoresSpoofedHeadersFromPublicPeers() throws Exception {
        PublicRateLimitFilter filter = filter(1);
        AtomicInteger forwarded = new AtomicInteger();

        send(filter, "/api/v1/auth/login", "172.18.0.2", "198.51.100.8", forwarded);
        MockHttpServletResponse otherClient = send(filter, "/api/v1/auth/login", "172.18.0.2", "198.51.100.9", forwarded);
        send(filter, "/api/v1/auth/login", "203.0.113.8", "198.51.100.10", forwarded);
        MockHttpServletResponse spoofed = send(filter, "/api/v1/auth/login", "203.0.113.8", "198.51.100.11", forwarded);

        assertThat(otherClient.getStatus()).isEqualTo(200);
        assertThat(spoofed.getStatus()).isEqualTo(429);
        assertThat(forwarded.get()).isEqualTo(3);
    }

    @Test
    void limitsEmailVerifyAsRecovery() throws Exception {
        assertRecoveryLimitBlocks("/api/v1/auth/email/verify");
    }

    @Test
    void limitsEmailResendAsRecovery() throws Exception {
        assertRecoveryLimitBlocks("/api/v1/auth/email/resend");
    }

    @Test
    void leavesNonSensitivePublicRoutesUntouched() throws Exception {
        PublicRateLimitFilter filter = filter(1);
        AtomicInteger forwarded = new AtomicInteger();

        MockHttpServletResponse response = send(
                filter, "GET", "/api/v1/public/ferreteria/products", "203.0.113.8", null, forwarded);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get()).isEqualTo(1);
    }

    /** Con el limite de recuperacion en 2 (ver {@link #filter}), la 3.a peticion POST responde 429. */
    private static void assertRecoveryLimitBlocks(String path) throws Exception {
        PublicRateLimitFilter filter = filter(10);
        AtomicInteger forwarded = new AtomicInteger();

        MockHttpServletResponse first = send(filter, path, "203.0.113.8", null, forwarded);
        MockHttpServletResponse second = send(filter, path, "203.0.113.8", null, forwarded);
        MockHttpServletResponse blocked = send(filter, path, "203.0.113.8", null, forwarded);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(forwarded.get()).isEqualTo(2);
    }

    private static PublicRateLimitFilter filter(int loginPerWindow) {
        return new PublicRateLimitFilter(new PublicRateLimitProperties(
                true, 60, 100, loginPerWindow, 2, 2, 2, 2, 2), new ObjectMapper());
    }

    private static MockHttpServletResponse send(PublicRateLimitFilter filter, String path, String remoteAddress,
            String forwardedFor, AtomicInteger forwarded) throws Exception {
        return send(filter, "POST", path, remoteAddress, forwardedFor, forwarded);
    }

    private static MockHttpServletResponse send(PublicRateLimitFilter filter, String method, String path,
            String remoteAddress, String forwardedFor, AtomicInteger forwarded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> forwarded.incrementAndGet());
        return response;
    }
}
