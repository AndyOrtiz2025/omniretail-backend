package com.omniretail.backend.inventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryCountControllerTest {

    private static final String SNAPSHOT = "/api/v1/inventory/counts/snapshot";
    private static final String RECONCILE = "/api/v1/inventory/counts/reconcile";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private SessionService sessionService;
    @MockitoBean private PermissionResolver permissionResolver;
    @MockitoBean private TenantEntitlementResolver entitlementResolver;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), anyString())).willReturn(true);
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get(SNAPSHOT)
                        .param("branchId", UUID.randomUUID().toString())
                        .param("productId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RECONCILE).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void snapshotRequiresStockReadPermission() throws Exception {
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq("inventory.stock.read")))
                .willReturn(false);

        mockMvc.perform(get(SNAPSHOT)
                        .header("Authorization", token())
                        .param("branchId", UUID.randomUUID().toString())
                        .param("productId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void reconcileRequiresAdjustmentCreatePermissionNotJustStockRead() throws Exception {
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq("inventory.adjustment.create")))
                .willReturn(false);

        mockMvc.perform(post(RECONCILE)
                        .header("Authorization", token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + UUID.randomUUID()
                                + "\",\"reason\":\"Conteo\",\"expectedQuantity\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void reconcileValidatesPayloadBeforeTouchingInventory() throws Exception {
        mockMvc.perform(post(RECONCILE)
                        .header("Authorization", token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Conteo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String token() {
        User user = User.builder()
                .name("Usuario inventario")
                .email("inventario-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .branchId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }
}
