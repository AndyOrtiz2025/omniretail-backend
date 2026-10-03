package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PasswordChangeControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String CHANGE = "/api/v1/auth/password/change";
    private static final String CUSTOMER_PASSWORD = "Cliente123!";
    private static final String NEW_CUSTOMER_PASSWORD = "Nueva123!";
    private static final String EMPLOYEE_PASSWORD = "Empleado1234!";
    private static final String NEW_EMPLOYEE_PASSWORD = "NuevaSegura123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void wrongCurrentPasswordIsReportedOnCurrentPassword() throws Exception {
        Account customer = customer(tenant());
        String token = login(customer, CUSTOMER_PASSWORD);

        for (String current : List.of("Incorrecta123!", "", "x".repeat(100))) {
            change(token, current, NEW_CUSTOMER_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fields.currentPassword").value("La contraseña actual no es correcta."))
                    .andExpect(jsonPath("$.fields.newPassword").doesNotExist());
        }
        change(token, null, NEW_CUSTOMER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.currentPassword").value("La contraseña actual no es correcta."));

        assertPasswordIs(customer, CUSTOMER_PASSWORD);
    }

    @Test
    void samePasswordIsRejected() throws Exception {
        Account customer = customer(tenant());
        String token = login(customer, CUSTOMER_PASSWORD);

        change(token, CUSTOMER_PASSWORD, CUSTOMER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.newPassword").value("La nueva contraseña debe ser diferente a la actual."))
                .andExpect(jsonPath("$.fields.currentPassword").doesNotExist());

        assertPasswordIs(customer, CUSTOMER_PASSWORD);
    }

    @Test
    void weakPasswordIsReportedOnNewPassword() throws Exception {
        Account customer = customer(tenant());
        String token = login(customer, CUSTOMER_PASSWORD);

        change(token, CUSTOMER_PASSWORD, "debil")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").value(
                        "La contraseña debe tener entre 8 y 24 caracteres e incluir una mayúscula, una minúscula, un "
                                + "número y un carácter especial."));
        change(token, CUSTOMER_PASSWORD, customer.user().getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").value("La contraseña no puede ser igual al correo electrónico."));
        change(token, CUSTOMER_PASSWORD, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").value("La contraseña es obligatoria."));

        assertPasswordIs(customer, CUSTOMER_PASSWORD);
    }

    @Test
    void successKeepsCurrentSessionAndRevokesTheOthers() throws Exception {
        Account customer = customer(tenant());
        String current = login(customer, CUSTOMER_PASSWORD);
        String other = login(customer, CUSTOMER_PASSWORD);
        Instant before = Instant.now();

        change(current, CUSTOMER_PASSWORD, NEW_CUSTOMER_PASSWORD).andExpect(status().isNoContent());

        me(current).andExpect(status().isOk());
        me(other).andExpect(status().isUnauthorized());

        AuthAccount account = authAccountRepository.findByUserId(customer.user().getId()).orElseThrow();
        assertThat(passwordEncoder.matches(NEW_CUSTOMER_PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.getPasswordChangedAt()).isAfterOrEqualTo(before.minusSeconds(1));

        loginRequest(customer, CUSTOMER_PASSWORD).andExpect(status().isUnauthorized());
        me(login(customer, NEW_CUSTOMER_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void employeeNeedsTwelveCharacters() throws Exception {
        Account employee = employee(tenant());
        String token = login(employee, EMPLOYEE_PASSWORD);

        // Valida para un cliente (8+), no para un empleado.
        change(token, EMPLOYEE_PASSWORD, NEW_CUSTOMER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").value(
                        "La contraseña debe tener entre 12 y 24 caracteres e incluir una mayúscula, una minúscula, un "
                                + "número y un carácter especial."));
        assertPasswordIs(employee, EMPLOYEE_PASSWORD);

        change(token, EMPLOYEE_PASSWORD, NEW_EMPLOYEE_PASSWORD).andExpect(status().isNoContent());
        assertPasswordIs(employee, NEW_EMPLOYEE_PASSWORD);
        me(token).andExpect(status().isOk());
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(CHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_PASSWORD, NEW_CUSTOMER_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    // --- Utilidades ---

    private record Account(User user, Tenant tenant) {
    }

    private ResultActions change(String token, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(post(CHANGE)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(currentPassword, newPassword)));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", "Bearer " + token));
    }

    private String login(Account account, String password) throws Exception {
        String body = loginRequest(account, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private ResultActions loginRequest(Account account, String password) throws Exception {
        String slug = account.user().getType() == UserType.customer ? account.tenant().getSlug() : null;
        return mockMvc.perform(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"password\": %s, \"tenantSlug\": %s}"
                        .formatted(json(account.user().getEmail()), json(password), json(slug))));
    }

    private void assertPasswordIs(Account account, String password) {
        AuthAccount stored = authAccountRepository.findByUserId(account.user().getId()).orElseThrow();
        assertThat(passwordEncoder.matches(password, stored.getPasswordHash())).isTrue();
    }

    private Tenant tenant() {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private Account customer(Tenant tenant) {
        return new Account(user(tenant, UserType.customer, null, CUSTOMER_PASSWORD), tenant);
    }

    /** /auth/me exige que un empleado tenga un rol activo. */
    private Account employee(Tenant tenant) {
        Role role = Role.builder().name("Rol " + UUID.randomUUID()).status(RoleStatus.active).permissions(List.of()).build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        return new Account(user(tenant, UserType.employee, role.getId(), EMPLOYEE_PASSWORD), tenant);
    }

    private User user(Tenant tenant, UserType type, UUID roleId, String password) {
        String email = "usuario-" + UUID.randomUUID() + "@test.local";
        User user = User.builder().name("Usuario test").email(email).type(type).roleId(roleId).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .status(AccountStatus.active)
                .build());
        return user;
    }

    private static String body(String currentPassword, String newPassword) {
        return "{\"currentPassword\": %s, \"newPassword\": %s}".formatted(json(currentPassword), json(newPassword));
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
