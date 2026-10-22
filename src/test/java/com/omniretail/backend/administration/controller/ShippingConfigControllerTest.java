package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.ShippingChannel;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.ShippingConfigRepository;
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
class ShippingConfigControllerTest {

    private static final String SHIPPING_URL = "/api/v1/administration/shipping-config";

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

    @Autowired
    private ShippingConfigRepository shippingConfigRepository;

    @MockitoBean
    private TenantEntitlementResolver entitlementResolver;

    @BeforeEach
    void setUp() {
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(SHIPPING_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.ecommerce_config.manage"));

        mockMvc.perform(get(SHIPPING_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void getWithoutSavedConfigReturnsChannelDefaults() throws Exception {
        Tenant tenant = persistTenant();

        mockMvc.perform(get(SHIPPING_URL).header("Authorization", bearer(tokenFor(tenant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ecommerce.enabled").value(true))
                .andExpect(jsonPath("$.ecommerce.deliveryFee").value(25.00))
                .andExpect(jsonPath("$.ecommerce.freeShippingThreshold").value(300.00))
                .andExpect(jsonPath("$.pos.enabled").value(false))
                .andExpect(jsonPath("$.pos.deliveryFee").value(0.00))
                .andExpect(jsonPath("$.pos.freeShippingThreshold").value(nullValue()));
    }

    @Test
    void worksWithoutEcommerceCapability() throws Exception {
        Tenant tenant = persistTenant();
        given(entitlementResolver.resolve(tenant.getId()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.pos)));

        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(tokenFor(tenant)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "25.00", "300.00", "true", "15.00", "null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pos.deliveryFee").value(15.00));
    }

    @Test
    void putPersistsBothChannelsAndUpdatesInPlace() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "30", "500", "true", "15.5", "null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ecommerce.deliveryFee").value(30.00))
                .andExpect(jsonPath("$.ecommerce.freeShippingThreshold").value(500.00))
                .andExpect(jsonPath("$.pos.enabled").value(true))
                .andExpect(jsonPath("$.pos.deliveryFee").value(15.50))
                .andExpect(jsonPath("$.pos.freeShippingThreshold").value(nullValue()));

        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("false", "0", "null", "true", "20", "250")))
                .andExpect(status().isOk());

        assertThat(shippingConfigRepository.findByTenantId(tenant.getId())).hasSize(2);
        var pos = shippingConfigRepository
                .findByTenantIdAndChannel(tenant.getId(), ShippingChannel.pos)
                .orElseThrow();
        assertThat(pos.getDeliveryFee()).isEqualByComparingTo("20.00");
        assertThat(pos.getFreeShippingThreshold()).isEqualByComparingTo("250.00");
        var ecommerce = shippingConfigRepository
                .findByTenantIdAndChannel(tenant.getId(), ShippingChannel.ecommerce)
                .orElseThrow();
        assertThat(ecommerce.isEnabled()).isFalse();
        assertThat(ecommerce.getFreeShippingThreshold()).isNull();
    }

    @Test
    void putRejectsNegativeOrMissingAmounts() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "-1", "300", "false", "0", "null")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "25", "-5", "false", "0", "null")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "null", "300", "false", "0", "null")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ecommerce\":{\"enabled\":true,\"deliveryFee\":25}}"))
                .andExpect(status().isBadRequest());

        assertThat(shippingConfigRepository.findByTenantId(tenant.getId())).isEmpty();
    }

    @Test
    void configIsIsolatedPerTenant() throws Exception {
        Tenant first = persistTenant();
        Tenant second = persistTenant();

        mockMvc.perform(put(SHIPPING_URL)
                        .header("Authorization", bearer(tokenFor(first)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("true", "40", "null", "true", "10", "null")))
                .andExpect(status().isOk());

        mockMvc.perform(get(SHIPPING_URL).header("Authorization", bearer(tokenFor(second))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ecommerce.deliveryFee").value(25.00))
                .andExpect(jsonPath("$.pos.enabled").value(false));
    }

    private static String body(
            String ecommerceEnabled, String ecommerceFee, String ecommerceThreshold,
            String posEnabled, String posFee, String posThreshold) {
        return "{\"ecommerce\":{\"enabled\":" + ecommerceEnabled
                + ",\"deliveryFee\":" + ecommerceFee
                + ",\"freeShippingThreshold\":" + ecommerceThreshold
                + "},\"pos\":{\"enabled\":" + posEnabled
                + ",\"deliveryFee\":" + posFee
                + ",\"freeShippingThreshold\":" + posThreshold + "}}";
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
        return tokenFor(tenant, List.of("admin.shipping_config.manage"));
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
