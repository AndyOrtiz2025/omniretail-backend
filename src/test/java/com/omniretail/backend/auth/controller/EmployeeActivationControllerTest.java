package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmployeeInvitationRepository;
import com.omniretail.backend.auth.service.EmployeeInvitationService;
import com.omniretail.backend.auth.service.PasswordPolicy;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.security.EmployeeInviteResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** RED escrito antes de la ruta; no ejecutado por restriccion explicita. Usa la seguridad real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class EmployeeActivationControllerTest {

    private static final String ACTIVATE = "/api/v1/auth/activate-employee";
    private static final String PASSWORD = "NuevaSegura123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuthAccountRepository accountRepository;
    @Autowired
    private EmployeeInvitationRepository invitationRepository;
    @Autowired
    private EmployeeInvitationService invitationService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void activatesWithoutJwtAndReturnsOnlyExactMessage() throws Exception {
        EmployeeInviteResult invite = invite();

        activate(invite.invitationToken(), PASSWORD).andExpect(status().isOk())
                .andExpect(content().string("{\"message\":\"Cuenta activada correctamente.\"}"));

        AuthAccount account = accountRepository.findByUserId(invite.userId()).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.active);
        assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.getPasswordChangedAt()).isNotNull();
        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(invite.userId())
                .orElseThrow().getAcceptedAt()).isEqualTo(account.getPasswordChangedAt());
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        // Una ruta vecina no se vuelve publica al agregar el permiso exacto.
        mockMvc.perform(post("/api/v1/auth/activate-employee/other")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankTokenReturnsValidationErrorOnToken() throws Exception {
        activate(" ", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.token").exists());
    }

    @Test
    void blankPasswordReturnsValidationErrorOnNewPassword() throws Exception {
        activate("some-token", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.newPassword").exists());
    }

    @Test
    void missingTokenAndPasswordReturnBothValidationFields() throws Exception {
        mockMvc.perform(post(ACTIVATE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.token").exists())
                .andExpect(jsonPath("$.fields.newPassword").exists());
    }

    @Test
    void weakPasswordDoesNotMutatePendingAccountOrConsumeTokenAndAllowsRetry() throws Exception {
        EmployeeInviteResult invite = invite();
        AuthAccount before = accountRepository.findByUserId(invite.userId()).orElseThrow();
        before.setFailedLoginAttempts(4);
        Instant lockedUntil = Instant.now().plusSeconds(300);
        before.setLockedUntil(lockedUntil);
        accountRepository.save(before);
        AuthAccount persistedBefore = accountRepository.findByUserId(invite.userId()).orElseThrow();

        activate(invite.invitationToken(), "Nueva123!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.newPassword").value(PasswordPolicy.EMPLOYEE.requirementsMessage()));

        AuthAccount after = accountRepository.findByUserId(invite.userId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AccountStatus.password_reset_required);
        assertThat(after.getPasswordHash()).isEqualTo(persistedBefore.getPasswordHash());
        assertThat(after.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(after.getLockedUntil()).isEqualTo(persistedBefore.getLockedUntil());
        assertThat(after.getPasswordChangedAt()).isNull();
        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(invite.userId())
                .orElseThrow().getAcceptedAt()).isNull();

        activate(invite.invitationToken(), PASSWORD).andExpect(status().isOk());
        AuthAccount active = accountRepository.findByUserId(invite.userId()).orElseThrow();
        assertThat(active.getFailedLoginAttempts()).isZero();
        assertThat(active.getLockedUntil()).isNull();
    }

    @Test
    void invalidReusedExpiredAndSupersededTokensHaveSamePublicError() throws Exception {
        EmployeeInviteResult used = invite();
        activate(used.invitationToken(), PASSWORD).andExpect(status().isOk());
        EmployeeInviteResult expired = invite();
        jdbcTemplate.update("UPDATE employee_invitations SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), expired.userId());
        EmployeeInviteResult superseded = invite();
        User employee = userRepository.findById(superseded.userId()).orElseThrow();
        invitationService.inviteEmployee(employee.getTenantId(), employee.getId());

        for (String token : List.of("unknown-token", used.invitationToken(),
                expired.invitationToken(), superseded.invitationToken())) {
            activate(token, PASSWORD).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"))
                    .andExpect(jsonPath("$.message").value("Este enlace no es válido o ya expiró."))
                    .andExpect(jsonPath("$.fields").doesNotExist());
        }
        assertThat(accountRepository.findByUserId(expired.userId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.password_reset_required);
    }

    private ResultActions activate(String token, String password) throws Exception {
        return mockMvc.perform(post(ACTIVATE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password)));
    }

    private EmployeeInviteResult invite() {
        Tenant tenant = tenantRepository.save(Tenant.builder().name("Tienda activacion")
                .slug("activacion-" + UUID.randomUUID()).status(TenantStatus.active)
                .defaultCurrency("GTQ").timezone("America/Guatemala").build());
        User employee = User.builder().name("Ana Empleada").type(UserType.employee)
                .email("empleado-" + UUID.randomUUID() + "@test.local").build();
        employee.setTenantId(tenant.getId());
        employee = userRepository.save(employee);
        return invitationService.inviteEmployee(tenant.getId(), employee.getId());
    }
}
