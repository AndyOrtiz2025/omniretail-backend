package com.omniretail.backend.pos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SaleReturnControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private SessionRepository sessions;
    @MockitoBean private SessionService sessionService;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(true);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void requiresAuthenticationForCreateAndList() throws Exception {
        mockMvc.perform(post("/api/v1/pos/sales/{id}/returns", UUID.randomUUID())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/pos/returns").param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresReturnPermissions() throws Exception {
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        Fixture fixture = fixture();
        mockMvc.perform(post("/api/v1/pos/sales/{id}/returns", fixture.saleId())
                        .header("Authorization", fixture.bearer()).contentType(APPLICATION_JSON)
                        .content(returnBody(fixture.saleItemId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsSaleRestoresStockAndMarksSaleReturned() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/pos/sales/{id}/returns", fixture.saleId())
                        .header("Authorization", fixture.bearer()).contentType(APPLICATION_JSON)
                        .content(returnBody(fixture.saleItemId())))
                .andExpect(status().isOk());

        BigDecimal stock = jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE id = ?", BigDecimal.class, fixture.balanceId());
        String saleStatus = jdbc.queryForObject("SELECT status FROM sales WHERE id = ?", String.class, fixture.saleId());
        assertThat(stock).isEqualByComparingTo("1.000");
        assertThat(saleStatus).isEqualTo("returned");
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID saleItemId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tienda " + suffix, "returns-" + suffix);
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'store', 'active')",
                branchId, tenantId, "RET-" + suffix, "Sucursal " + suffix);
        jdbc.update("INSERT INTO roles (id, tenant_id, name, permissions, branch_scope, status) VALUES (?, ?, ?, ARRAY['pos.returns.create', 'pos.returns.read'], 'assigned', 'active')",
                roleId, tenantId, "Rol " + suffix);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id, allowed_branch_ids) VALUES (?, ?, 'Cajero', ?, 'employee', 'active', ?, ?, CAST(? AS uuid[]))",
                userId, tenantId, userId + "@returns.test", roleId, branchId, "{" + branchId + "}");
        jdbc.update("INSERT INTO sessions (id, user_id, active_branch_id, remember_me, expires_at) VALUES (?, ?, ?, false, now() + interval '1 hour')",
                sessionId, userId, branchId);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, 'UND', 'Unidad', 'und', 'unit', false, 'active')",
                unitId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id, sale_price, status, tracking_stock, channel_pos) VALUES (?, ?, ?, ?, 'physical', ?, ?, 10.00, 'published', true, true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        jdbc.update("INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, 0.000, 0.000)",
                balanceId, tenantId, branchId, productId);
        jdbc.update("INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at, opening_amount) VALUES (?, ?, ?, ?, 'RET', 'open', now(), 100.00)",
                shiftId, tenantId, branchId, userId);
        jdbc.update("INSERT INTO sales (id, tenant_id, branch_id, number, cash_shift_id, status, subtotal, discount_total, tax_total, total, created_by_user_id) VALUES (?, ?, ?, 'POS-RET', ?, 'completed', 10.00, 0.00, 0.00, 10.00, ?)",
                saleId, tenantId, branchId, shiftId, userId);
        jdbc.update("INSERT INTO sale_items (id, sale_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, discount, subtotal) VALUES (?, ?, ?, 'SKU', 'Producto', 1.000, 10.00, 0.00, 10.00)",
                saleItemId, saleId, productId);
        jdbc.update("INSERT INTO payments (id, tenant_id, sale_id, method, status, amount, currency) VALUES (?, ?, ?, 'cash', 'approved', 10.00, 'GTQ')",
                UUID.randomUUID(), tenantId, saleId);

        User user = users.findById(userId).orElseThrow();
        Session session = sessions.findById(sessionId).orElseThrow();
        return new Fixture(saleId, saleItemId, balanceId, "Bearer " + jwtService.generateToken(user, session));
    }

    private String returnBody(UUID saleItemId) {
        return "{\"reason\":\"Producto devuelto\",\"lines\":[{\"saleItemId\":\""
                + saleItemId + "\",\"quantity\":1.000}]}";
    }

    private record Fixture(UUID saleId, UUID saleItemId, UUID balanceId, String bearer) {}
}
