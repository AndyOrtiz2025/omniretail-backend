package com.omniretail.backend.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {

    @Test
    void ignoresAClientSuppliedForwardedHeaderFromAnUntrustedPeer() {
        MockHttpServletRequest request = request("203.0.113.20", "198.51.100.10");

        assertThat(ClientAddressResolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void acceptsTheAddressOverwrittenByTheInternalProxyAndUsesOnlyItsFirstEntry() {
        MockHttpServletRequest request = request("172.18.0.3", "198.51.100.10, 172.18.0.2");

        assertThat(ClientAddressResolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void fallsBackToTheTrustedProxySocketAddressForAMalformedForwardedHeader() {
        MockHttpServletRequest request = request("172.18.0.3", "198.51.100.10<script>");

        assertThat(ClientAddressResolver.resolve(request)).isEqualTo("172.18.0.3");
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
