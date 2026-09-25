package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void tokenCarriesExpectedClaimsAndExpiresWithSession() {
        User user = user(UserType.employee, UUID.randomUUID(), UUID.randomUUID());
        Session session = session(user);

        Jwt jwt = jwtDecoder.decode(jwtService.generateToken(user, session));

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(user.getTenantId().toString());
        assertThat(jwt.getClaimAsString("userType")).isEqualTo("employee");
        assertThat(jwt.getClaimAsString("roleId")).isEqualTo(user.getRoleId().toString());
        assertThat(jwt.getClaimAsString("branchId")).isEqualTo(user.getBranchId().toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.getId().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("omniretail-backend");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isEqualTo(session.getExpiresAt());
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "password", "permissions");
    }

    @Test
    void optionalClaimsAreOmittedWhenNull() {
        User user = user(UserType.customer, null, null);

        Jwt jwt = jwtDecoder.decode(jwtService.generateToken(user, session(user)));

        assertThat(jwt.getClaims()).doesNotContainKeys("roleId", "branchId");
        assertThat(jwt.getClaimAsString("userType")).isEqualTo("customer");
    }

    @Test
    void rejectsSessionOfAnotherUser() {
        User user = user(UserType.customer, null, null);
        Session foreign = session(user);
        foreign.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.generateToken(user, foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static User user(UserType type, UUID roleId, UUID branchId) {
        User user = User.builder()
                .name("Test")
                .email("test@omniretail.local")
                .type(type)
                .roleId(roleId)
                .branchId(branchId)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        return user;
    }

    private static Session session(User user) {
        return Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                // JWT maneja segundos: se trunca para comparar exp con expiresAt sin perder precision.
                .expiresAt(Instant.now().plus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
                .build();
    }
}
