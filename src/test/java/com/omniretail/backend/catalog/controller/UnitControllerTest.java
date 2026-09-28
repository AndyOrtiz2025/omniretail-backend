package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UnitControllerTest {

    private static final String UNITS = "/api/v1/catalog/units";
    private static final String READ_PERMISSION = "catalog.units.read";
    private static final String MANAGE_PERMISSION = "catalog.units.manage";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private PermissionResolver permissionResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), anyString()))
                .willReturn(true);
    }

    @Test
    void listWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(UNITS)).andExpect(status().isUnauthorized());
    }

    @Test
    void listWithoutReadPermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyReadPermission();

        mockMvc.perform(get(UNITS).header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void getByIdWithoutReadPermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyReadPermission();

        mockMvc.perform(get(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void postWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "Unidad", "und", "unit", false, "active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void putWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(put(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "unit", false, "active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void deleteWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(delete(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void emptyListReturnsEmptyPage() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(get(UNITS).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void listOnlyReturnsCurrentTenantUnits() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID expected = insertUnit(tenantA, "UND-A", "Unidad A", "a", "unit", false, "active");
        insertUnit(tenantB, "UND-B", "Unidad B", "b", "unit", false, "active");

        mockMvc.perform(get(UNITS).header("Authorization", token(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.items[0].tenantId").value(tenantA.toString()));
    }

    @Test
    void listReturnsPageResponseShape() throws Exception {
        UUID tenantId = insertTenant();
        insertUnit(tenantId, "A", "Alpha", "a", "unit", false, "active");
        insertUnit(tenantId, "B", "Bravo", "b", "unit", false, "active");

        mockMvc.perform(get(UNITS)
                        .header("Authorization", token(tenantId))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void activeStatusFilterOnlyReturnsActiveUnits() throws Exception {
        UUID tenantId = insertTenant();
        UUID active = insertUnit(tenantId, "ACT", "Activa", "a", "unit", false, "active");
        insertUnit(tenantId, "ARC", "Archivada", "x", "unit", false, "archived");

        mockMvc.perform(get(UNITS)
                        .header("Authorization", token(tenantId))
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(active.toString()))
                .andExpect(jsonPath("$.items[0].status").value("active"));
    }

    @Test
    void archivedStatusFilterOnlyReturnsArchivedUnits() throws Exception {
        UUID tenantId = insertTenant();
        insertUnit(tenantId, "ACT", "Activa", "a", "unit", false, "active");
        UUID archived = insertUnit(
                tenantId, "ARC", "Archivada", "x", "unit", false, "archived");

        mockMvc.perform(get(UNITS)
                        .header("Authorization", token(tenantId))
                        .param("status", "archived"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(archived.toString()))
                .andExpect(jsonPath("$.items[0].status").value("archived"));
    }

    @Test
    void defaultSortOrdersByNameAscending() throws Exception {
        UUID tenantId = insertTenant();
        insertUnit(tenantId, "Z", "Zulu", "z", "unit", false, "active");
        insertUnit(tenantId, "A", "Alpha", "a", "unit", false, "active");
        insertUnit(tenantId, "M", "Medio", "m", "unit", false, "active");

        mockMvc.perform(get(UNITS).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Alpha"))
                .andExpect(jsonPath("$.items[1].name").value("Medio"))
                .andExpect(jsonPath("$.items[2].name").value("Zulu"));
    }

    @Test
    void paginationReturnsRequestedPage() throws Exception {
        UUID tenantId = insertTenant();
        insertUnit(tenantId, "A", "Alpha", "a", "unit", false, "active");
        insertUnit(tenantId, "B", "Bravo", "b", "unit", false, "active");
        insertUnit(tenantId, "C", "Charlie", "c", "unit", false, "active");

        mockMvc.perform(get(UNITS)
                        .header("Authorization", token(tenantId))
                        .param("page", "2")
                        .param("size", "1")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Bravo"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void invalidStatusReturnsBadRequest() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(get(UNITS)
                        .header("Authorization", token(tenantId))
                        .param("status", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturnsCompleteUnitResponse() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(
                tenantId, "KG", "Kilogramo", "kg", "weight", true, "active");

        mockMvc.perform(get(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(unitId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.code").value("KG"))
                .andExpect(jsonPath("$.name").value("Kilogramo"))
                .andExpect(jsonPath("$.symbol").value("kg"))
                .andExpect(jsonPath("$.category").value("weight"))
                .andExpect(jsonPath("$.allowsDecimals").value(true))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void getByIdForNonexistentUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(get(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Unidad no encontrada."));
    }

    @Test
    void getByIdForAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID unitB = insertUnit(tenantB, "B", "Unidad B", "b", "unit", false, "active");

        mockMvc.perform(get(UNITS + "/" + unitB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void createUnitTrimsEditableTextFields() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                "UND", "  Unidad  ", " und ", "unit", true, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.code").value("UND"))
                .andExpect(jsonPath("$.name").value("Unidad"))
                .andExpect(jsonPath("$.symbol").value("und"))
                .andExpect(jsonPath("$.category").value("unit"))
                .andExpect(jsonPath("$.allowsDecimals").value(true))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void createNormalizesCodeToUppercase() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("kg", "Kilogramo", "kg", "weight", true, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("KG"));
    }

    @Test
    void createReplacesCodeWhitespaceWithHyphens() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                "  caja   grande  ", "Caja grande", "cja", "unit", false, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CAJA-GRANDE"));
    }

    @Test
    void createDefaultsAllowsDecimalsToFalse() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "Unidad", "und", "unit", null, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowsDecimals").value(false));
    }

    @Test
    void createDefaultsStatusToActive() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "Unidad", "und", "unit", false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void duplicateCodeInSameTenantReturnsConflict() throws Exception {
        UUID tenantId = insertTenant();
        insertUnit(tenantId, "CAJA-GRANDE", "Existente", "cja", "unit", false, "active");

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                " caja grande ", "Otra", "otra", "unit", false, "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_CODE_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Ya existe una unidad con ese código."));
    }

    @Test
    void sameCodeInDifferentTenantsIsAllowed() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        insertUnit(tenantB, "KG", "Kilogramo B", "kg", "weight", true, "active");

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("kg", "Kilogramo A", "kg", "weight", true, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("KG"));
    }

    @Test
    void createRejectsInvalidName() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "   ", "und", "unit", false, "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsInvalidSymbol() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "Unidad", "   ", "unit", false, "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsMissingCategory() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody("UND", "Unidad", "und", null, false, "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsCodeThatBecomesTooLongAfterNormalization() throws Exception {
        UUID tenantId = insertTenant();
        String expandingCode = "\u00DF".repeat(11);

        mockMvc.perform(post(UNITS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                expandingCode, "Unidad", "und", "unit", false, "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_CODE_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("El código de la unidad no es válido."));
    }

    @Test
    void updateReplacesEditableFields() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Anterior", "ant", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(
                                "  Kilogramo  ", " kg ", "weight", true, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kilogramo"))
                .andExpect(jsonPath("$.symbol").value("kg"))
                .andExpect(jsonPath("$.category").value("weight"))
                .andExpect(jsonPath("$.allowsDecimals").value(true))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void updateKeepsCodeUnchanged() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "STABLE", "Unidad", "und", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Renombrada", "ren", "unit", false, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("STABLE"));

        assertThat(unitCode(unitId)).isEqualTo("STABLE");
    }

    @Test
    void updateUnitFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID unitB = insertUnit(tenantB, "B", "Unidad B", "b", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitB)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Ajena", "a", "unit", false, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void updateNonexistentUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(put(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "unit", false, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void updateCanChangeAllowsDecimals() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "unit", true, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowsDecimals").value(true));
    }

    @Test
    void updateCanChangeCategory() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "volume", false, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("volume"));
    }

    @Test
    void updateCanArchiveUnit() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "active");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "unit", false, "archived")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void updateCanReactivateUnit() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "archived");

        mockMvc.perform(put(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Unidad", "und", "unit", false, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void deleteArchivesUnitAndReturnsNoContent() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "active");

        mockMvc.perform(delete(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        assertThat(unitStatus(unitId)).isEqualTo("archived");
    }

    @Test
    void deleteAlreadyArchivedUnitIsIdempotent() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "archived");

        mockMvc.perform(delete(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        assertThat(unitStatus(unitId)).isEqualTo("archived");
    }

    @Test
    void deleteNonexistentUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(delete(UNITS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void deleteUnitFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID unitB = insertUnit(tenantB, "B", "Unidad B", "b", "unit", false, "active");

        mockMvc.perform(delete(UNITS + "/" + unitB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void deleteAllowsArchivingUnitWithAssociatedProduct() throws Exception {
        UUID tenantId = insertTenant();
        UUID unitId = insertUnit(tenantId, "UND", "Unidad", "und", "unit", false, "active");
        insertProduct(tenantId, unitId);

        mockMvc.perform(delete(UNITS + "/" + unitId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        assertThat(unitStatus(unitId)).isEqualTo("archived");
    }

    private void denyReadPermission() {
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);
    }

    private void denyManagePermission() {
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(MANAGE_PERMISSION)))
                .willReturn(false);
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        return tenantId;
    }

    private UUID insertUnit(
            UUID tenantId,
            String code,
            String name,
            String symbol,
            String category,
            boolean allowsDecimals,
            String status) {
        UUID unitId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                unitId,
                tenantId,
                code,
                name,
                symbol,
                category,
                allowsDecimals,
                status);
        return unitId;
    }

    private void insertProduct(UUID tenantId, UUID unitId) {
        UUID categoryId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Categoría', ?, 'active')
                """,
                categoryId,
                tenantId,
                "category-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
    }

    private String unitCode(UUID unitId) {
        return jdbcTemplate.queryForObject(
                "SELECT code FROM units WHERE id = ?",
                String.class,
                unitId);
    }

    private String unitStatus(UUID unitId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM units WHERE id = ?",
                String.class,
                unitId);
    }

    private String token(UUID tenantId) {
        User user = User.builder()
                .name("Usuario catálogo")
                .email("catalogo-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenantId);
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String createBody(
            String code,
            String name,
            String symbol,
            String category,
            Boolean allowsDecimals,
            String status) {
        return """
                {
                  "code": "%s",
                  "name": "%s",
                  "symbol": "%s",
                  "category": %s,
                  "allowsDecimals": %s,
                  "status": %s
                }
                """
                .formatted(
                        code,
                        name,
                        symbol,
                        jsonString(category),
                        allowsDecimals,
                        jsonString(status));
    }

    private static String updateBody(
            String name,
            String symbol,
            String category,
            boolean allowsDecimals,
            String status) {
        return """
                {
                  "name": "%s",
                  "symbol": "%s",
                  "category": "%s",
                  "allowsDecimals": %s,
                  "status": "%s"
                }
                """
                .formatted(name, symbol, category, allowsDecimals, status);
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
