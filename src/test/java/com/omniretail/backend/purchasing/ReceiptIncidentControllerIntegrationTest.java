package com.omniretail.backend.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReceiptIncidentControllerIntegrationTest {

    private static final String BASE = "/api/v1/purchasing/receipts";
    private static final String MANAGE_INCIDENTS = "receiving.incidents.manage";
    private static final String READ_RECEIPTS = "receiving.receipts.read";
    private static final String CREATE_RECEIPTS = "receiving.receipts.create";
    private static final String CONFIRM_RECEIPTS = "receiving.receipts.confirm";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @MockitoBean private SessionService sessions;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(sessions.isActive(any(), any())).willReturn(true);
        allowOnlyPermissions();
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void createsGeneralIncidentOnDraftAndListsWithHistoricalReceiptPermissions() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS);

        UUID first = createIncident(
                fixture.receipt(), token, "{\"incidentType\":\"missing\",\"notes\":\"  Faltante general  \"}");
        createIncident(
                fixture.receipt(), token, "{\"incidentType\":\"other\",\"notes\":\"Seguimiento\"}");

        given(entitlements.resolve(fixture.tenant()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        for (String readPermission : List.of(READ_RECEIPTS, CREATE_RECEIPTS, CONFIRM_RECEIPTS)) {
            allowOnlyPermissions(readPermission);
            mvc.perform(get(BASE + "/" + fixture.receipt() + "/incidents")
                            .header("Authorization", token)
                            .param("page", "0")
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(1))
                    .andExpect(jsonPath("$.totalItems").value(2))
                    .andExpect(jsonPath("$.items.length()").value(1));
        }
        assertThat(jdbc.queryForMap(
                        "SELECT status, quantity_affected, notes, branch_id FROM receipt_incidents WHERE id = ?",
                        first))
                .containsEntry("status", "open")
                .containsEntry("quantity_affected", null)
                .containsEntry("notes", "Faltante general")
                .containsEntry("branch_id", fixture.branch());
        assertNoInventoryEffects(fixture.tenant());
    }

    @Test
    void createsItemIncidentOnConfirmedReceiptWithoutChangingInventory() throws Exception {
        Fixture fixture = fixture(true);
        allowOnlyPermissions(MANAGE_INCIDENTS);
        jdbc.update(
                "UPDATE goods_receipts SET status = 'confirmed', received_at = now() WHERE id = ?",
                fixture.receipt());

        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(fixture.receiptItem(), "2.500")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goodsReceiptId").value(fixture.receipt().toString()))
                .andExpect(jsonPath("$.goodsReceiptItemId").value(fixture.receiptItem().toString()))
                .andExpect(jsonPath("$.incidentType").value("damaged"))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.quantityAffected").value(2.500))
                .andExpect(jsonPath("$.createdByUserId").value(fixture.actor().user().toString()))
                .andExpect(jsonPath("$.resolvedByUserId").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());

        assertNoInventoryEffects(fixture.tenant());
    }

    @Test
    void rejectsForeignReceiptItemAndInvalidQuantityCombinations() throws Exception {
        Fixture fixture = fixture(true);
        ReceiptLine other = addReceipt(fixture, "draft");
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS);

        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(other.item(), "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_ITEM_MISMATCH"));
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"damaged\",\"goodsReceiptItemId\":\""
                                + fixture.receiptItem() + "\",\"notes\":\"Sin cantidad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_QUANTITY_REQUIRED"));
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(fixture.receiptItem(), "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(fixture.receiptItem(), "10.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_QUANTITY_EXCEEDS_ITEM"));
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"quantityAffected\":1,\"notes\":\"General\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_QUANTITY_NOT_ALLOWED"));
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\""
                                + "x".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());

        Fixture integerUnit = fixture(false);
        mvc.perform(post(BASE + "/" + integerUnit.receipt() + "/incidents")
                        .header("Authorization", token(integerUnit.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(integerUnit.receiptItem(), "1.500")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_INVALID_QUANTITY"));
        assertThat(incidentCount(fixture.tenant())).isZero();
        assertThat(incidentCount(integerUnit.tenant())).isZero();
    }

    @Test
    void appliesNotesLimitAfterNormalization() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        String validNotes = "x".repeat(1000);
        allowOnlyPermissions(MANAGE_INCIDENTS);

        UUID incident = createIncident(
                fixture.receipt(),
                token,
                "{\"incidentType\":\"other\",\"notes\":\"   " + validNotes + "   \"}");
        assertThat(jdbc.queryForObject(
                        "SELECT notes FROM receipt_incidents WHERE id = ?", String.class, incident))
                .isEqualTo(validNotes);

        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"   "
                                + "x".repeat(1001) + "   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_INVALID_NOTES"));
    }

    @Test
    void tenantAndBranchIsolationAreEnforced() throws Exception {
        Fixture owner = fixture(true);
        Fixture outsider = fixture(true);
        allowOnlyPermissions(MANAGE_INCIDENTS, READ_RECEIPTS);

        mvc.perform(post(BASE + "/" + owner.receipt() + "/incidents")
                        .header("Authorization", token(outsider.actor()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"Cruce\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GOODS_RECEIPT_NOT_FOUND"));
        mvc.perform(get(BASE + "/" + owner.receipt() + "/incidents")
                        .header("Authorization", token(outsider.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GOODS_RECEIPT_NOT_FOUND"));
        mvc.perform(post(BASE + "/" + outsider.receipt() + "/incidents")
                        .header("Authorization", token(outsider.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(itemBody(owner.receiptItem(), "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_ITEM_NOT_FOUND"));

        UUID otherBranch = addBranch(owner.tenant());
        jdbc.update("UPDATE goods_receipts SET branch_id = ? WHERE id = ?", otherBranch, owner.receipt());
        jdbc.update("UPDATE roles SET branch_scope = 'selected' WHERE id = ?", owner.actor().role());
        jdbc.update("UPDATE users SET allowed_branch_ids = '{}'::uuid[] WHERE id = ?", owner.actor().user());
        mvc.perform(get(BASE + "/" + owner.receipt() + "/incidents")
                        .header("Authorization", token(owner.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
        mvc.perform(post(BASE + "/" + owner.receipt() + "/incidents")
                        .header("Authorization", token(owner.actor()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"Sin acceso\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
        assertThat(incidentCount(owner.tenant())).isZero();
    }

    @Test
    void managePermissionAndReceivingCapabilityAreRequiredForCreateAndResolve() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS);
        UUID incident = createIncident(
                fixture.receipt(), token, "{\"incidentType\":\"other\",\"notes\":\"Pendiente\"}");

        allowOnlyPermissions();
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"Sin permiso\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        allowOnlyPermissions(MANAGE_INCIDENTS);
        given(entitlements.resolve(fixture.tenant()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        mvc.perform(post(BASE + "/" + fixture.receipt() + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"incidentType\":\"other\",\"notes\":\"Sin capability\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        assertThat(incidentStatus(incident)).isEqualTo("open");
    }

    @Test
    void postAndResolveUseExactManagePermissionContract() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS);

        UUID incident = createIncident(
                fixture.receipt(), token, "{\"incidentType\":\"other\",\"notes\":\"Contrato\"}");
        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"));
    }

    @Test
    void resolveStoresActorAndTimestampRejectsSecondResolveAndDoesNotTouchStock() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS);
        UUID incident = createIncident(
                fixture.receipt(), token, itemBody(fixture.receiptItem(), "3"));

        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.resolvedByUserId").value(fixture.actor().user().toString()))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        assertThat(jdbc.queryForMap(
                        "SELECT status, resolved_by_user_id, resolved_at FROM receipt_incidents WHERE id = ?",
                        incident))
                .containsEntry("status", "resolved")
                .containsEntry("resolved_by_user_id", fixture.actor().user());
        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_INCIDENT_ALREADY_RESOLVED"));
        assertNoInventoryEffects(fixture.tenant());
    }

    @Test
    void openIncidentBlocksConfirmationBeforeAnyStockEffect() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS, CONFIRM_RECEIPTS);
        createIncident(
                fixture.receipt(), token, "{\"incidentType\":\"missing\",\"notes\":\"Pendiente\"}");

        mvc.perform(post(BASE + "/" + fixture.receipt() + "/confirm")
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_HAS_OPEN_INCIDENTS"));

        assertThat(receiptStatus(fixture.receipt())).isEqualTo("draft");
        assertThat(orderStatus(fixture.order())).isEqualTo("approved");
        assertNoInventoryEffects(fixture.tenant());
    }

    @Test
    void resolvedIncidentAllowsConfirmation() throws Exception {
        Fixture fixture = fixture(true);
        String token = token(fixture.actor());
        allowOnlyPermissions(MANAGE_INCIDENTS, CONFIRM_RECEIPTS);
        UUID incident = createIncident(
                fixture.receipt(), token, itemBody(fixture.receiptItem(), "1"));
        mvc.perform(patch(BASE + "/incidents/" + incident + "/resolve")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        assertNoInventoryEffects(fixture.tenant());

        mvc.perform(post(BASE + "/" + fixture.receipt() + "/confirm")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));

        assertThat(balance(fixture)).isEqualByComparingTo("10");
        assertThat(movementCount(fixture.tenant())).isOne();
    }

    private UUID createIncident(UUID receiptId, String token, String body) throws Exception {
        String json = mvc.perform(post(BASE + "/" + receiptId + "/incidents")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int valueStart = json.indexOf("\"id\":\"") + 6;
        return UUID.fromString(json.substring(valueStart, json.indexOf('"', valueStart)));
    }

    private void allowOnlyPermissions(String... allowedPermissions) {
        reset(permissions);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        for (String permission : allowedPermissions) {
            given(permissions.hasPermission(any(), any(), eq(permission))).willReturn(true);
        }
    }

    private Fixture fixture(boolean allowsDecimals) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID supplierProduct = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        UUID receiptItem = UUID.randomUUID();
        String suffix = tenant.toString();

        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Incident test', ?)", tenant, "inc-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO roles (id, tenant_id, name, branch_scope, permissions)
                VALUES (?, ?, ?, 'all', '{}')
                """, role, tenant, "Rol " + role);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id)
                VALUES (?, ?, 'Receptor', ?, 'employee', 'active', ?, ?)
                """, user, tenant, user + "@test.local", role, branch);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category,
                tenant,
                "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', ?, 'active')
                """, unit, tenant, "U-" + suffix.substring(0, 8), allowsDecimals);
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, category_id, base_unit_id, tracking_stock)
                VALUES (?, ?, ?, 'Producto', ?, ?, true)
                """, product, tenant, "SKU-" + suffix, category, unit);
        jdbc.update(
                "INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')",
                supplier,
                tenant);
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, ?, 1, 1, 0, 1)
                """, supplierProduct, tenant, supplier, product, unit);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot,
                     status, subtotal, total, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'Proveedor', 'approved', 10, 10, ?)
                """, order, tenant, branch, "OC-" + order, supplier, user);
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, 'Producto', 'SKU', 10, ?, 'u', 1, 1, 10)
                """, orderItem, tenant, order, supplierProduct, product, unit);
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')
                """, location, tenant, branch, "L-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO goods_receipts
                    (id, tenant_id, branch_id, purchase_order_id, number, status)
                VALUES (?, ?, ?, ?, ?, 'draft')
                """, receipt, tenant, branch, order, "REC-" + receipt);
        jdbc.update("""
                INSERT INTO goods_receipt_items
                    (id, tenant_id, goods_receipt_id, purchase_order_item_id, product_id,
                     location_id, received_quantity, unit_id, unit_symbol_snapshot,
                     purchase_to_base_factor, base_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?, 10, ?, 'u', 1, 10, 1)
                """, receiptItem, tenant, receipt, orderItem, product, location, unit);
        return new Fixture(
                tenant,
                branch,
                new Actor(tenant, user, role, branch),
                unit,
                product,
                order,
                orderItem,
                location,
                receipt,
                receiptItem);
    }

    private ReceiptLine addReceipt(Fixture fixture, String status) {
        UUID receipt = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO goods_receipts
                    (id, tenant_id, branch_id, purchase_order_id, number, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, receipt, fixture.tenant(), fixture.branch(), fixture.order(), "REC-" + receipt, status);
        jdbc.update("""
                INSERT INTO goods_receipt_items
                    (id, tenant_id, goods_receipt_id, purchase_order_item_id, product_id,
                     location_id, received_quantity, unit_id, unit_symbol_snapshot,
                     purchase_to_base_factor, base_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?, 10, ?, 'u', 1, 10, 1)
                """,
                item,
                fixture.tenant(),
                receipt,
                fixture.orderItem(),
                fixture.product(),
                fixture.location(),
                fixture.unit());
        return new ReceiptLine(receipt, item);
    }

    private UUID addBranch(UUID tenant) {
        UUID branch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Secundaria', 'store', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        return branch;
    }

    private String token(Actor actor) {
        User user = User.builder()
                .name("Receptor")
                .email(actor.user() + "@test.local")
                .type(UserType.employee)
                .roleId(actor.role())
                .branchId(actor.branch())
                .build();
        user.setTenantId(actor.tenant());
        ReflectionTestUtils.setField(user, "id", actor.user());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(actor.user())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String itemBody(UUID itemId, String quantity) {
        return """
                {"incidentType":"damaged","goodsReceiptItemId":"%s",
                 "quantityAffected":%s,"notes":"Producto danado"}
                """.formatted(itemId, quantity);
    }

    private long incidentCount(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM receipt_incidents WHERE tenant_id = ?", Long.class, tenant);
    }

    private String incidentStatus(UUID incident) {
        return jdbc.queryForObject(
                "SELECT status FROM receipt_incidents WHERE id = ?", String.class, incident);
    }

    private String receiptStatus(UUID receipt) {
        return jdbc.queryForObject(
                "SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt);
    }

    private String orderStatus(UUID order) {
        return jdbc.queryForObject(
                "SELECT status FROM purchase_orders WHERE id = ?", String.class, order);
    }

    private long movementCount(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?", Long.class, tenant);
    }

    private long balanceCount(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_balances WHERE tenant_id = ?", Long.class, tenant);
    }

    private BigDecimal balance(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                BigDecimal.class,
                fixture.tenant(),
                fixture.branch(),
                fixture.product(),
                fixture.location());
    }

    private void assertNoInventoryEffects(UUID tenant) {
        assertThat(movementCount(tenant)).isZero();
        assertThat(balanceCount(tenant)).isZero();
    }

    private record Actor(UUID tenant, UUID user, UUID role, UUID branch) {}

    private record ReceiptLine(UUID receipt, UUID item) {}

    private record Fixture(
            UUID tenant,
            UUID branch,
            Actor actor,
            UUID unit,
            UUID product,
            UUID order,
            UUID orderItem,
            UUID location,
            UUID receipt,
            UUID receiptItem) {}
}
