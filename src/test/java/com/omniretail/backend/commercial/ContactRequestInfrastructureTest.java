package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.shared.notification.OutgoingMailProperties;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Configuracion obligatoria, tope de cuerpo y verificador anti-robots: reglas sin base de datos ni red. */
class ContactRequestInfrastructureTest {

    private static final String SECRET = "secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres";

    private static ContactRequestProperties properties(
            String team, String secret, ContactRequestProperties.Captcha captcha) {
        return new ContactRequestProperties(
                true, team, secret, 1000, null, null, null, null, null, null, null, null, null, null, captcha);
    }

    // ------------------------------------------------------------ configuracion explicita

    @Test
    void completeConfigurationHasNoProblems() {
        assertThat(ContactRequestConfiguration.problems(
                        properties("equipo@marjym.test", SECRET, null),
                        new OutgoingMailProperties("MARJYM <no-reply@marjym.test>"),
                        "smtp.example.test"))
                .isEmpty();
    }

    @Test
    void incompleteConfigurationNamesTheMissingPropertiesButNeverTheirValues() {
        List<String> problems = ContactRequestConfiguration.problems(
                properties("", "corto", null), new OutgoingMailProperties(" "), "");

        assertThat(problems).hasSize(4);
        assertThat(String.join(",", problems))
                .contains("team-notification-to", "ip-hash-secret", "app.mail.from", "spring.mail.host")
                .doesNotContain("corto");
    }

    @Test
    void anAddressListOrMalformedTeamAddressIsRejected() {
        for (String team : List.of("a@b.co,c@d.co", "sin-arroba", "a b@c.co")) {
            assertThat(ContactRequestConfiguration.problems(
                            properties(team, SECRET, null), new OutgoingMailProperties("x@y.co"), "host"))
                    .anyMatch(problem -> problem.contains("team-notification-to"));
        }
    }

    @Test
    void anEnabledCaptchaNeedsASecretAndAnHttpsVerifier() {
        ContactRequestProperties.Captcha noSecret = new ContactRequestProperties.Captcha(true, "", null, null);
        ContactRequestProperties.Captcha insecure =
                new ContactRequestProperties.Captcha(true, "s", "http://verificador.example", null);

        assertThat(ContactRequestConfiguration.problems(
                        properties("a@b.co", SECRET, noSecret), new OutgoingMailProperties("x@y.co"), "host"))
                .anyMatch(problem -> problem.contains("captcha.secret"));
        assertThat(ContactRequestConfiguration.problems(
                        properties("a@b.co", SECRET, insecure), new OutgoingMailProperties("x@y.co"), "host"))
                .anyMatch(problem -> problem.contains("captcha.verify-url"));
    }

    @Test
    void disabledIsTheDefaultOfTheProperties() {
        ContactRequestProperties defaults = new ContactRequestProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.captcha().enabled()).isFalse();
        assertThat(defaults.maxBodyBytes()).isEqualTo(16_384);
    }

    // ------------------------------------------------------------ tope del cuerpo

    @Test
    void aBodyAboveTheLimitOrWithoutDeclaredLengthNeverReachesTheController() throws Exception {
        ContactRequestBodyLimitFilter filter = new ContactRequestBodyLimitFilter(
                properties("a@b.co", SECRET, null));
        AtomicInteger reached = new AtomicInteger();
        FilterChain chain = (request, response) -> reached.incrementAndGet();

        MockHttpServletRequest large = post(new byte[1500]);
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        filter.doFilter(large, tooLarge, chain);

        MockHttpServletRequest chunked = post(null);
        MockHttpServletResponse lengthRequired = new MockHttpServletResponse();
        filter.doFilter(chunked, lengthRequired, chain);

        MockHttpServletRequest fine = post(new byte[900]);
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(fine, accepted, chain);

        assertThat(tooLarge.getStatus()).isEqualTo(413);
        assertThat(tooLarge.getContentAsString()).contains("REQUEST_TOO_LARGE");
        assertThat(lengthRequired.getStatus()).isEqualTo(411);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(reached.get()).isOne();
    }

    @Test
    void otherPathsAndMethodsAreNotInspected() throws Exception {
        ContactRequestBodyLimitFilter filter = new ContactRequestBodyLimitFilter(
                properties("a@b.co", SECRET, null));
        AtomicInteger reached = new AtomicInteger();
        FilterChain chain = (request, response) -> reached.incrementAndGet();
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        other.setContent(new byte[5000]);
        MockHttpServletRequest get = new MockHttpServletRequest("GET", ContactRequestBodyLimitFilter.PATH);

        filter.doFilter(other, new MockHttpServletResponse(), chain);
        filter.doFilter(get, new MockHttpServletResponse(), chain);

        assertThat(reached.get()).isEqualTo(2);
    }

    private static MockHttpServletRequest post(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ContactRequestBodyLimitFilter.PATH);
        if (content != null) {
            request.setContent(content);
        }
        return request;
    }

    // ------------------------------------------------------------ anti-robots

    @Test
    void theCaptchaVerifierFailsClosedWithoutTokenAndWhenTheVerifierIsUnreachable() {
        ContactRequestProperties.Captcha settings = new ContactRequestProperties.Captcha(
                true, "secreto", "https://127.0.0.1:1/verify", Duration.ofMillis(500));
        TurnstileCaptchaVerifier verifier = new TurnstileCaptchaVerifier(settings, JsonMapper.builder().build());

        assertThat(verifier.verify(null, "203.0.113.1")).isFalse();
        assertThat(verifier.verify("  ", "203.0.113.1")).isFalse();
        assertThat(verifier.verify("token-cualquiera", "203.0.113.1")).isFalse();
    }
}
