package com.omniretail.backend.administration.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BusinessConfigControllerTest {

    private static final String BASE_URL = "/api/v1/administration/business-config";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @MockitoBean
    private TenantEntitlementResolver entitlementResolver;

    @BeforeEach
    void setUp() {
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void getReturnsDefaultsForNewTenant() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.preset").value("custom"))
                .andExpect(jsonPath("$.supportsInventory").value(true))
                .andExpect(jsonPath("$.supportsLots").value(false))
                .andExpect(jsonPath("$.supportsSerials").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.stock").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.lot").value(false));
    }

    @Test
    void putUpdatesCapabilitiesAndPersists() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"preset":"pharmacy","supportsInventory":true,"supportsLots":true,"supportsExpiration":true,"supportsSerials":false,"supportsMultipleLocations":true,"supportsUnitsAndPackaging":true,"supportsProductAttributes":true,"supportsKits":true,"supportsServices":true,"allowedPosPaymentMethods":["cash","card","transfer"],"defaultProductTracking":{"stock":true,"lot":true,"expiration":true,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("pharmacy"))
                .andExpect(jsonPath("$.supportsLots").value(true))
                .andExpect(jsonPath("$.supportsExpiration").value(true))
                .andExpect(jsonPath("$.allowedPosPaymentMethods.length()").value(3))
                .andExpect(jsonPath("$.defaultProductTracking.lot").value(true));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("pharmacy"))
                .andExpect(jsonPath("$.supportsLots").value(true))
                .andExpect(jsonPath("$.defaultProductTracking.stock").value(true));
    }

    @Test
    void putWithPresetNotMatchingDefaultsFallsBackToCustom() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"preset":"pharmacy","supportsInventory":true,"supportsLots":false,"supportsExpiration":false,"supportsSerials":false,"supportsMultipleLocations":false,"supportsUnitsAndPackaging":false,"supportsProductAttributes":false,"supportsKits":false,"supportsServices":false,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("custom"));
    }

    @Test
    void disablingInventoryTurnsOffDependentCapabilitiesAndTracking() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"preset":"custom","supportsInventory":false,"supportsLots":true,"supportsExpiration":true,"supportsSerials":true,"supportsMultipleLocations":true,"supportsUnitsAndPackaging":true,"supportsProductAttributes":false,"supportsKits":false,"supportsServices":true,"defaultProductTracking":{"stock":true,"lot":true,"expiration":true,"serial":true}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsInventory").value(false))
                .andExpect(jsonPath("$.supportsLots").value(false))
                .andExpect(jsonPath("$.supportsExpiration").value(false))
                .andExpect(jsonPath("$.supportsSerials").value(false))
                .andExpect(jsonPath("$.supportsMultipleLocations").value(false))
                .andExpect(jsonPath("$.supportsUnitsAndPackaging").value(true))
                .andExpect(jsonPath("$.supportsServices").value(true))
                .andExpect(jsonPath("$.defaultProductTracking.stock").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.lot").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.expiration").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.serial").value(false));
    }

    @Test
    void trackingWithoutCapabilityReturnsBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String lotWithoutCapability =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":false,"supportsExpiration":false,"supportsSerials":false,"defaultProductTracking":{"stock":true,"lot":true,"expiration":false,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotWithoutCapability))
                .andExpect(status().isBadRequest());

        String serialWithoutCapability =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":false,"supportsExpiration":false,"supportsSerials":false,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":true}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serialWithoutCapability))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enablingCapabilityOutsidePlanReturnsCapabilityRequired() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        given(entitlementResolver.resolve(tenant.getId()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        String lotsBody =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":true,"supportsExpiration":false,"supportsSerials":false,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotsBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        String serialsBody =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":false,"supportsExpiration":false,"supportsSerials":true,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serialsBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void capabilityAlreadyEnabledCanBeKeptAfterPlanDowngrade() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String lotsBody =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":true,"supportsExpiration":false,"supportsSerials":false,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":false}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotsBody))
                .andExpect(status().isOk());

        given(entitlementResolver.resolve(tenant.getId()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotsBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsLots").value(true));
    }

    @Test
    void configIsIsolatedPerTenant() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA);
        String tokenB = tokenFor(tenantB);

        String body =
                """
                {"preset":"custom","supportsInventory":true,"supportsLots":false,"supportsExpiration":false,"supportsSerials":true,"supportsKits":true,"defaultProductTracking":{"stock":true,"lot":false,"expiration":false,"serial":true}}
                """;

        mockMvc.perform(put(BASE_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsSerials").value(true));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantB.getId().toString()))
                .andExpect(jsonPath("$.supportsSerials").value(false))
                .andExpect(jsonPath("$.supportsKits").value(false))
                .andExpect(jsonPath("$.defaultProductTracking.serial").value(false));
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build();
        return tenantRepository.save(tenant);
    }

    private String tokenFor(Tenant tenant) {
        return tokenFor(tenant, List.of("admin.business_config.manage"));
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        Session session = Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        session = sessionRepository.save(session);

        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
