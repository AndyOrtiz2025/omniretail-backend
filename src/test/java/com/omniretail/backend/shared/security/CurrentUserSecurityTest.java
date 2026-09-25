package com.omniretail.backend.shared.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CurrentUserSecurityTest.WhoAmIController.class})
class CurrentUserSecurityTest {

    private static final String WHO_AM_I = "/api/v1/test-support/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtEncoder jwtEncoder;

    /** Aqui solo se prueban los claims; la validacion de sesion contra la BD se cubre en AuthControllerTest. */
    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void everySessionIsActive() {
        given(sessionService.isActive(any(), any())).willReturn(true);
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(WHO_AM_I)).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenExposesTenantFromJwt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        User user = User.builder().name("Ana").email("ana@omniretail.local").type(UserType.employee)
                .roleId(UUID.randomUUID()).branchId(UUID.randomUUID()).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenantId);
        Session session = Session.builder().id(UUID.randomUUID()).userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();

        mockMvc.perform(get(WHO_AM_I)
                        .header("Authorization", bearer(jwtService.generateToken(user, session)))
                        // Un tenant en header o query no debe tener ningun efecto.
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.userType").value("employee"));
    }

    @Test
    void tokenWithoutTenantIsUnauthorized() throws Exception {
        JwtClaimsSet claims = baseClaims(Instant.now().plus(1, ChronoUnit.HOURS)).build();

        mockMvc.perform(get(WHO_AM_I).header("Authorization", bearer(encode(claims))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutSessionIdIsUnauthorized() throws Exception {
        JwtClaimsSet claims = baseClaims(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("tenantId", UUID.randomUUID().toString())
                .claims(c -> c.remove("sid"))
                .build();

        mockMvc.perform(get(WHO_AM_I).header("Authorization", bearer(encode(claims))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        JwtClaimsSet claims = baseClaims(Instant.now().minus(1, ChronoUnit.HOURS))
                .issuedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .claim("tenantId", UUID.randomUUID().toString())
                .build();

        mockMvc.perform(get(WHO_AM_I).header("Authorization", bearer(encode(claims))))
                .andExpect(status().isUnauthorized());
    }

    /** Claims validos salvo {@code tenantId}, que cada test agrega o no. */
    private static JwtClaimsSet.Builder baseClaims(Instant expiresAt) {
        return JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuer("omniretail-backend")
                .issuedAt(Instant.now())
                .expiresAt(expiresAt)
                .claim("userType", "customer")
                .claim("sid", UUID.randomUUID().toString());
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @RestController
    public static class WhoAmIController {

        private final CurrentUser currentUser;

        WhoAmIController(CurrentUser currentUser) {
            this.currentUser = currentUser;
        }

        /** ApiPathConfig antepone {@code /api/v1} a todo controller del paquete base. */
        @GetMapping("/test-support/me")
        public AuthenticatedUser me() {
            return currentUser.require();
        }
    }
}
